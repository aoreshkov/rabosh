package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A durable, ordered store of JSON documents keyed by byte string.
 *
 * A commit is appended to a checksummed write-ahead log and then applied to an in-memory sorted
 * table; when that table reaches [StoreOptions.memtableMaxBytes] it is sealed and a new log begins,
 * and a background pass writes it out as an immutable sorted segment. Segments are merged downwards
 * through levels as they accumulate. A read consults the memtables newest first, then level 0's
 * segments newest first, then one segment per level below that — stopping at the first version it
 * finds, including a tombstone, which is an answer rather than a reason to keep looking.
 *
 * **The guarantee.** After any interruption, reopening the store yields exactly the acknowledged
 * prefix of the commits: every commit whose [write] returned is present, no commit that had not
 * returned is present, and nothing in between is missing. Under [Durability.SYNC] that holds across
 * power loss; under [Durability.BUFFERED] it holds across process death and [sync] is what extends
 * it to the machine.
 *
 * **Concurrency: one writer, many readers.** Writes are serialised on an internal lock — the engine
 * assumes a single writing thread, and the lock is there so that a mistaken second one gets
 * contention rather than a corrupt log. Reads take no lock and may run on any number of threads
 * while a write is in progress.
 *
 * A batch is atomic to a reader as well as to recovery: the sequence a read is bounded by is
 * published only once every operation in the batch is in the memtable, so **one view of the store
 * never shows part of a batch**. The unit is the view, not the call — a run of separate [get] calls
 * is a run of separate reads, each at whatever sequence the store had reached. Take a [Snapshot] to
 * read several keys as one.
 *
 * ```kotlin
 * DocumentStore.open(Path.of("data")).use { store ->
 *     store.put(Key.of("user:1"), Variant.fromJson("""{"name":"ada"}"""))
 *     store.get(Key.of("user:1"))?.select("$.name")?.stringValue()   // "ada"
 *
 *     store.snapshot().use { snapshot ->
 *         store.scan(from = Key.of("user:"), snapshot = snapshot).use { cursor ->
 *             while (cursor.next()) println(cursor.key)
 *         }
 *     }
 * }
 * ```
 */
public class DocumentStore private constructor(
    /** The directory this store owns. */
    public val directory: Path,
    /** The options it was opened with. */
    public val options: StoreOptions,
    private val directoryLock: DirectoryLock,
    private val versions: VersionSet,
    private var log: LogWriter,
    activeMemtable: Memtable,
    activeLogNumber: Long,
    nextLogNumber: Long,
    lastSequence: Long,
) : AutoCloseable {

    private val writeLock = ReentrantLock()

    /**
     * Held by whoever is flushing or compacting, which is at most one thread at a time.
     *
     * Separate from [writeLock] on purpose: writing a segment takes as long as the data is large,
     * and a writer must not queue behind it. What this lock protects is the choice of *which*
     * memtable to flush and which files to merge — two threads picking the same one would write the
     * same data twice under different file numbers.
     */
    private val maintenanceLock = ReentrantLock()

    /**
     * Logs are numbered in a space of their own, not out of the manifest's file counter.
     *
     * Segments and manifests share one counter so that a file whose number the live version does
     * not know is unambiguously an orphan. Logs cannot join them: recovery reasons about *the
     * newest log* and about sealed logs being complete, and both of those are statements about a
     * contiguous run. A gap where a segment took a number would make "the newest" ambiguous for no
     * gain — a log is already identified as obsolete by being below the manifest's log number, not
     * by being absent from a set.
     */
    private var nextLogNumber: Long = nextLogNumber
    private val snapshots = SnapshotRegistry()
    private val picker = CompactionPicker(options)

    @Volatile
    private var state = StoreState(activeMemtable, activeLogNumber, emptyList())

    @Volatile
    private var lastSequence: Long = lastSequence

    /**
     * The newest sequence a reader may see.
     *
     * Distinct from [lastSequence], and that gap is what makes a batch atomic to readers: the log
     * and the memtable are updated first and this is published afterwards, so a reader bounded by
     * it either sees all of a batch or none of it.
     */
    @Volatile
    private var visibleSequence: Long = lastSequence

    @Volatile
    private var closed = false

    /**
     * The IO failure that stopped this store from writing, if one has happened.
     *
     * Once set, writes are refused; see [StoreFailedException] for why carrying on is not an option.
     */
    @Volatile
    private var failure: Throwable? = null

    private val maintenance: Maintenance? = if (options.backgroundMaintenance) {
        Maintenance("rabosh-maintenance-${directory.fileName}", ::runMaintenance, ::markFailed)
    } else {
        null
    }

    /** Sequence number of the last committed operation; `0` for a store nothing has been written to. */
    public val sequence: Long get() = lastSequence

    /** A snapshot of the store's current sizes. */
    public val stats: StoreStats
        get() {
            val current = state
            val version = versions.current
            return StoreStats(
                lastSequence = lastSequence,
                memtableBytes = current.active.approximateBytes,
                memtableEntries = current.active.entryCount,
                sealedMemtables = current.sealed.size,
                logNumber = log.number,
                logBytes = log.bytesWritten,
                segmentCount = version.segmentCount,
                segmentBytes = version.totalBytes,
                segmentsPerLevel = (0..LEVEL_COUNT).map(version::countAt),
                liveSnapshots = snapshots.count,
            )
        }

    /** Commits [document] under [key], replacing any current version. */
    public fun put(key: Key, document: Variant): Unit = write(WriteBatch().put(key, document))

    /** Commits a deletion of [key]. Deleting an absent key is legal and writes a tombstone. */
    public fun delete(key: Key): Unit = write(WriteBatch().delete(key))

    /**
     * Commits [batch] as one record. An empty batch does nothing.
     *
     * Single-key [put] and [delete] go through this same path rather than a fast path of their own,
     * so there is exactly one place where the order of *log first, memtable second* is decided. That
     * order is the guarantee: a memtable holding an entry the log does not is the one arrangement
     * that can survive a crash as an unacknowledged write that is nevertheless present.
     */
    public fun write(batch: WriteBatch) {
        val operations = batch.operations()
        if (operations.isEmpty()) return

        writeLock.withLock {
            checkWritable()
            val firstSequence = lastSequence + 1
            val finalSequence = firstSequence + operations.size - 1
            if (finalSequence > SegmentFormat.MAX_SEQUENCE) {
                throw IllegalStateException(
                    "the store has reached the largest sequence number a segment can record " +
                        "(${SegmentFormat.MAX_SEQUENCE})",
                )
            }
            guardIo {
                log.append(operations, firstSequence)
                if (options.durability == Durability.SYNC) log.sync()
            }

            val active = state.active
            operations.forEachIndexed { index, operation ->
                apply(active, firstSequence + index, operation)
            }
            lastSequence = finalSequence
            // Published last, and that is the point: until it moves, no reader can see any part of
            // this batch.
            visibleSequence = finalSequence

            if (active.approximateBytes >= options.memtableMaxBytes) guardIo { rotateLocked() }
        }
    }

    /**
     * The current version of [key], or `null` if it is absent or deleted.
     *
     * The returned [Variant] is a view over the stored bytes and copies nothing; reading one field of
     * a large document costs the field. When the document comes from a segment, that view is over a
     * mapped file, and it stays valid because the version it came from is released only after this
     * call returns.
     */
    public fun get(key: Key): Variant? {
        checkOpen()
        return lookup(key, visibleSequence, state, null)
    }

    /** The version of [key] that [snapshot] sees, or `null` if it was absent or deleted then. */
    public fun get(key: Key, snapshot: Snapshot): Variant? {
        checkOpen()
        snapshot.checkOpen()
        return lookup(key, snapshot.sequence, snapshot.state, snapshot.version)
    }

    /**
     * A fixed view of the store as it is now.
     *
     * Every read through it sees the same data however much is written afterwards, and compaction
     * will not drop a version the snapshot could still ask for. Close it when done — an open
     * snapshot costs disk space, because that is what holding old versions means.
     */
    public fun snapshot(): Snapshot {
        checkOpen()
        val version = versions.acquireCurrent()
        val snapshot = Snapshot(visibleSequence, version, state, snapshots)
        snapshots.register(snapshot)
        return snapshot
    }

    /**
     * An ordered walk over the documents in `[from, to]`, both bounds inclusive and both optional.
     *
     * Without a [snapshot] the cursor takes one of its own and closes it with itself, so a scan is
     * consistent whether or not the caller asked for that. Deleted keys produce nothing, and a key
     * appears once however many times it has been written.
     */
    public fun scan(
        from: Key? = null,
        to: Key? = null,
        snapshot: Snapshot? = null,
    ): DocumentCursor = cursor(from, to, prefix = null, snapshot = snapshot)

    /**
     * An ordered walk over every document whose key begins with [prefix].
     *
     * ```kotlin
     * store.scanPrefix(Key.of("receipt/")).use { cursor ->
     *     while (cursor.next()) println(cursor.key)
     * }
     * ```
     *
     * **This exists because the range spelling of it does not work, and fails silently.** `[from,
     * to]` is inclusive at both ends, and a prefix range's upper end is inherently exclusive: the
     * prefix with its last byte raised is one key too generous, so scanning `[receipt/, receipt0]`
     * returns `receipt0` — a key in a neighbouring namespace. There is no inclusive bound to reach
     * for instead, because keys have no maximum length and so no greatest key carries a given
     * prefix. [Key.startsWith] carries the full argument; [Key.successor] carries the other way the
     * arithmetic goes wrong.
     *
     * An **empty prefix matches every key**, which makes this the same walk as [scan] with no
     * bounds rather than a special case to guard.
     *
     * A separate name rather than a `prefix` parameter on [scan], deliberately: as an overload,
     * `scan(k)` on an existing caller would start resolving to the prefix form — `Key` being more
     * specific than `Key?` — and silently mean something else.
     */
    public fun scanPrefix(prefix: Key, snapshot: Snapshot? = null): DocumentCursor =
        cursor(from = prefix, to = null, prefix = prefix, snapshot = snapshot)

    /** The one place a [DocumentCursor] is built, so all three bound kinds are applied identically. */
    private fun cursor(from: Key?, to: Key?, prefix: Key?, snapshot: Snapshot?): DocumentCursor {
        checkOpen()
        snapshot?.checkOpen()
        val view = snapshot ?: snapshot()
        val cursors = ArrayList<EntryCursor>()
        return try {
            cursors += MemtableCursor(view.state.active)
            for (sealed in view.state.sealed.asReversed()) cursors += MemtableCursor(sealed.memtable)
            for (table in view.version.segments()) cursors += table.cursor()
            DocumentCursor(
                MergingCursor(cursors),
                view.sequence,
                from,
                to,
                prefix,
                if (snapshot == null) view else null,
            )
        } catch (failure: Throwable) {
            closeAll(cursors)
            if (snapshot == null) view.close()
            throw failure
        }
    }

    /**
     * An ordered walk over the documents in `[from, to]` held by **the named segments only**.
     *
     * The partial half of a query plan: an index answers for the segments it covers, and this reads
     * the ones it does not. Where [scan] reads every source the view pinned, this reads the ones
     * asked for, plus the memtables unless [includeUnflushed] says otherwise.
     *
     * **What it yields is a candidate, not necessarily the version [snapshot] sees, and that is the
     * whole of its contract.** Versions are collapsed *within the named sources*, so a key whose
     * newest visible version lives in a segment that was not named — or in a memtable, when
     * [includeUnflushed] is `false` — is reported here carrying an older document, or suppressed
     * behind a tombstone that a newer write has already undone, or not reported at all. A caller must
     * re-resolve each key through [get] against the same snapshot unless it can show that the named
     * sources are the whole of what that key could be in. That is the rule an index hit already
     * obeys, arrived at from the other side.
     *
     * Segment numbers [snapshot]'s version does not hold are ignored, and that is not leniency: the
     * live set moves under a long-lived snapshot, so a caller working from a set it obtained
     * elsewhere will legitimately name a compaction output this view never had. Anything such a
     * segment holds is either invisible at this sequence or still reachable through the sources this
     * view *does* pin. [Snapshot.segmentNumbers] is the set that is actually here.
     *
     * The snapshot is required, where [scan] takes one optionally. A set of segment numbers only
     * means something relative to a pinned version, and taking one internally would let a caller name
     * segments from a version they never saw.
     */
    public fun scanSegments(
        segmentNumbers: Set<Long>,
        snapshot: Snapshot,
        includeUnflushed: Boolean = true,
        from: Key? = null,
        to: Key? = null,
    ): DocumentCursor {
        checkOpen()
        snapshot.checkOpen()
        val cursors = ArrayList<EntryCursor>()
        return try {
            if (includeUnflushed) {
                cursors += MemtableCursor(snapshot.state.active)
                for (sealed in snapshot.state.sealed.asReversed()) cursors += MemtableCursor(sealed.memtable)
            }
            for (table in snapshot.version.segments()) {
                if (table.number in segmentNumbers) cursors += table.cursor()
            }
            DocumentCursor(MergingCursor(cursors), snapshot.sequence, from, to, prefix = null, ownedSnapshot = null)
        } catch (failure: Throwable) {
            closeAll(cursors)
            throw failure
        }
    }

    /**
     * Forces every commit so far to stable storage.
     *
     * A no-op under [Durability.SYNC], where each commit is already forced. Under
     * [Durability.BUFFERED] this is the durability barrier: the point at which a bulk load may be
     * reported as complete.
     */
    public fun sync() {
        writeLock.withLock {
            checkWritable()
            guardIo { log.sync() }
        }
    }

    /**
     * Seals the active memtable and starts a new log.
     *
     * Called automatically when the memtable reaches [StoreOptions.memtableMaxBytes]; exposed
     * because tests and benchmarks need to reach the boundary deliberately rather than by writing
     * 64 MiB of documents. A no-op when the active memtable is empty, so calling it in a loop does
     * not litter the directory with logs.
     */
    public fun rotate() {
        writeLock.withLock {
            checkWritable()
            guardIo { rotateLocked() }
        }
    }

    /**
     * Seals the active memtable and writes every sealed one out as a segment.
     *
     * Returns when the segments are on the platter and the manifest names them — whether or not
     * maintenance runs in the background, because the writing is done on the calling thread either
     * way. That is what makes it usable as a barrier: before measuring anything, before copying a
     * store directory somewhere else, or before asserting on the shape of the tree.
     *
     * It does **not** compact. Any compaction the flush made due is left to [compact] or to the
     * background thread, so that "everything is in a segment" and "the tree is in shape" stay two
     * separate statements a caller can ask for one at a time.
     */
    public fun flush() {
        checkWritable()
        rotate()
        maintenanceLock.withLock {
            do {
                val flushed = flushOneMemtable()
            } while (flushed)
        }
        maintenance?.schedule()
    }

    /**
     * Deletes every key in `[from, to]`, both bounds inclusive, and returns how many.
     *
     * ```kotlin
     * val retired = store.deleteRange(Key.of("event:2026-07-01"), Key.of("event:2026-07-31"))
     * store.compact()   // tombstones are reclaimed by compaction, not by this call
     * ```
     *
     * **This is the loop a caller would otherwise write, written once by the party that knows the
     * rules.** Retention by key range is the whole of what a staging buffer and an archive need, and
     * getting it right by hand means knowing four things that are not on any signature: that the scan
     * must be scoped by a [Snapshot] or a concurrent compaction can change what it sees, that the
     * deletes belong in a [WriteBatch] rather than being issued one at a time, that a tombstone is
     * reclaimed by compaction and not by the delete, and that a tombstone may only be dropped at the
     * bottom-most level below the oldest live snapshot. Three of those four are invariants a caller
     * should never have had to learn.
     *
     * **Deliberately the cheap shape, and it is worth knowing that it is a choice.** This emits point
     * deletes in bounded batches — no new operation id, no format change, no change to compaction,
     * no new invariant. A real LSM *range tombstone* is the other design and the format has room for
     * it, but it would change what a merge emits, what `EntryCursor` collapses and, most seriously,
     * the tombstone-drop rule, which is on the short list of invariants that fail by returning a
     * deleted document to a reader. That is not a change to make without a measurement saying this
     * version is not enough.
     *
     * So the cost is proportional to the number of keys deleted, not to the size of the range, and it
     * writes one tombstone per key. A caller retiring a very large range should expect the write
     * amplification of exactly that.
     *
     * **Atomic per batch, not overall.** A failure part-way leaves the batches that were committed
     * committed — this is a retention loop, not a transaction, and the alternative would be one
     * commit holding every tombstone, which for a large range is a record the log cannot hold. The
     * count returned is what was actually deleted.
     *
     * The snapshot is taken here, so keys written *during* the call are not deleted: the range is
     * emptied as of the moment it was asked for, which is what makes a repeated call converge rather
     * than race a writer.
     *
     * @param from lower bound, inclusive. `null` means unbounded.
     * @param to upper bound, inclusive. `null` means unbounded.
     * @param batchSize keys per commit. The default is a compromise between the log record size and
     *   the number of forces; there is rarely a reason to change it.
     * @return the number of keys deleted.
     */
    @JvmOverloads
    public fun deleteRange(from: Key? = null, to: Key? = null, batchSize: Int = DEFAULT_DELETE_BATCH): Long {
        checkWritable()
        require(batchSize > 0) { "batchSize must be positive, not $batchSize" }
        if (from != null && to != null && from > to) return 0L

        var deleted = 0L
        // One snapshot for the whole loop. Scoping every batch's scan by its own snapshot would let a
        // compaction land between them and change what the next scan sees — which for a retention
        // loop means a key that was there when the range was asked for and is silently still there
        // afterwards.
        snapshot().use { view ->
            // Keys are collected a batch at a time rather than all at once: a range covering a whole
            // store would otherwise be a list of every key in it, on the heap, before a single
            // tombstone is written.
            var cursorFrom = from
            var exhausted = false
            while (!exhausted) {
                val keys = ArrayList<Key>(batchSize)
                scan(cursorFrom, to, view).use { cursor ->
                    while (keys.size < batchSize && cursor.next()) keys += cursor.key
                }
                if (keys.isEmpty()) break

                val batch = WriteBatch()
                for (key in keys) batch.delete(key)
                write(batch)
                deleted += keys.size

                // The next scan starts *after* the last key handled. `successor` rather than the key
                // itself, because the scan's lower bound is inclusive: restarting at the key just
                // deleted would re-scan a range whose first entry is now a tombstone, and a short
                // batch would end the loop early on a range that still has keys in it.
                if (keys.size < batchSize) exhausted = true else cursorFrom = keys.last().successor()
            }
        }
        return deleted
    }

    /**
     * Deletes every document whose key begins with [prefix], and returns how many.
     *
     * ```kotlin
     * val retired = store.deletePrefix(Key.of("session/2026-07/"))
     * ```
     *
     * [deleteRange]'s contract in every respect — point deletes under one snapshot, atomic per
     * batch and not overall, one tombstone per key, converging when called again — differing only
     * in how the keys are chosen. See [scanPrefix] for why a prefix is named rather than spelled as
     * a range, and [Key.startsWith] for why the range arithmetic a caller would otherwise write
     * retires one key too many.
     *
     * An **empty prefix deletes every document**, exactly as `deleteRange()` with no bounds does.
     *
     * @param prefix the prefix every deleted key begins with.
     * @param batchSize keys per commit, as [deleteRange].
     * @return the number of keys deleted.
     */
    @JvmOverloads
    public fun deletePrefix(prefix: Key, batchSize: Int = DEFAULT_DELETE_BATCH): Long {
        checkWritable()
        require(batchSize > 0) { "batchSize must be positive, not $batchSize" }

        var deleted = 0L
        // One snapshot for the whole loop, for the reason `deleteRange` takes one: a compaction
        // landing between batches would change what the next scan sees, so a key that was under the
        // prefix when it was asked for could be silently left behind.
        snapshot().use { view ->
            var cursorFrom = prefix
            var exhausted = false
            while (!exhausted) {
                val keys = ArrayList<Key>(batchSize)
                cursor(from = cursorFrom, to = null, prefix = prefix, snapshot = view).use { cursor ->
                    while (keys.size < batchSize && cursor.next()) keys += cursor.key
                }
                if (keys.isEmpty()) break

                val batch = WriteBatch()
                for (key in keys) batch.delete(key)
                write(batch)
                deleted += keys.size

                // `successor` for the same reason as in `deleteRange`: the lower bound is inclusive,
                // so resuming at the last key handled would re-scan a range whose head is now a
                // tombstone and end the loop early on a prefix that still has keys under it.
                if (keys.size < batchSize) exhausted = true else cursorFrom = keys.last().successor()
            }
        }
        return deleted
    }

    /**
     * Writes a consistent copy of this store into [target], which must be empty or absent.
     *
     * ```kotlin
     * val info = store.checkpoint(Path.of("backup", "2026-08-10"))
     * DocumentStore.open(info.directory).use { copy -> /* every commit up to info.sequence */ }
     * ```
     *
     * **Safe to call while writing.** The store is flushed, a snapshot is pinned, and the copy is
     * taken of what that snapshot sees — so the result holds exactly the acknowledged prefix as of
     * [CheckpointInfo.sequence], which is the store's own guarantee asserted against a second
     * directory rather than against a reopen. Writes that arrive during the call are simply above
     * that sequence and are not in the copy.
     *
     * **The segments are hard-linked where the filesystem allows it**, so a checkpoint of a large
     * store costs a directory entry per file rather than its bytes. That also means the copy shares
     * blocks with the source: it is a consistent *view*, and moving it off the machine — which is
     * what makes it a backup — is the caller's next step, not this one's.
     * [CheckpointInfo.hardLinked] says which happened.
     *
     * **Sidecars travel with their segments**, including kinds this module knows nothing about: any
     * file named after a live segment's number is copied, so a checkpoint's `.cat`, `.idx`, `.pst`
     * and `.col` files are *read* by the copy rather than rebuilt. What it does **not** carry is the
     * index registry, which is `IndexCatalog`'s file and is copied by `Rabosh.checkpoint`; a
     * checkpoint taken through this method opens with its sidecars intact and no index defined.
     *
     * **No log is copied.** The flush is what makes that correct: every commit at or below the
     * sequence is already in a segment, so the checkpoint opens the way a cleanly closed store does.
     *
     * A failure part-way leaves [target] holding whatever had been written — there is no attempt to
     * unwind, because the checkpoint is not valid until `CURRENT` names its manifest and until then
     * the directory does not open as a store at all. **The source is never modified**, which is the
     * property the fault-injection suite asserts at every step.
     *
     * @throws java.nio.file.FileAlreadyExistsException if [target] exists and is not an empty
     *   directory. A checkpoint is never merged into a store that is already there.
     * @throws StoreClosedException if this store is closed.
     */
    public fun checkpoint(target: Path): CheckpointInfo {
        checkWritable()
        // Before the snapshot, not after: a snapshot taken first would pin a version whose memtable
        // contents are not yet in any segment, and the copy carries no log to recover them from.
        flush()
        return snapshot().use { view ->
            writeCheckpoint(directory, target, view.version, view.sequence)
        }
    }

    /**
     * Flushes, then compacts until no level is over its budget. Returns when the tree is in shape.
     *
     * The [rotate] is what makes the first half of that sentence true. Maintenance only ever sees
     * *sealed* memtables, so without it a store whose active memtable had not reached
     * [StoreOptions.memtableMaxBytes] would compact whatever was already on disk and leave every
     * recent write in memory — which is not a barrier, and not what the sentence above says.
     */
    public fun compact() {
        checkWritable()
        rotate()
        drain()
    }

    /**
     * Feeds every document of every live segment through [observer].
     *
     * This is how derived data is built over data that is **already written**, which is the whole
     * point of the project: by the time anyone knows which model or which index they want, rewriting
     * the documents is the thing they cannot afford. Nothing here rewrites anything — the segments
     * are immutable and are read, in order, exactly as a compaction would read them.
     *
     * The observer is asked about each segment through [SegmentObserver.beginSegment] and may return
     * `null` to skip one it already covers, so a repeated call costs only the segments that are new.
     * [SegmentObserver.retain] is called at the end with the segments that were live throughout.
     *
     * Runs on the calling thread and pins a version for its duration — so a compaction may proceed
     * underneath it, and the files it is reading will not be deleted while it is inside them. What
     * this does mean is that a segment compacted away *during* the backfill is still sketched, and
     * the following `retain` will tell the observer to drop it again. Correct, and cheaper than
     * blocking maintenance for the length of a full scan.
     *
     * That closing `retain` reports the segments that are live **when the scan finishes**, not the
     * ones that were live when it started. The difference is not cosmetic: an observer that deletes
     * derived data for segments outside the set it is given would, on the pinned set, delete the
     * sidecar of a segment a compaction produced *during* a long scan — a file that is live, that
     * nothing will rewrite, and that the observer has no other way to learn about.
     *
     * Documents in the memtable are not included: they are not in a segment yet, and there is no
     * per-segment unit to attach them to. Call [flush] first for a complete pass.
     */
    public fun backfill(observer: SegmentObserver) {
        checkOpen()
        val version = versions.acquireCurrent()
        try {
            for (table in version.segments()) {
                backfillSegment(observer, table)
            }
        } finally {
            version.release()
        }
        Observers.retain(observer, liveSegmentNumbers)
    }

    /**
     * The segments the live version names, right now.
     *
     * The same set [SegmentObserver.retain] is handed, available to ask for rather than only to be
     * told. A layer that keeps per-segment derived data needs it at the one moment `retain` cannot
     * cover: when it is attaching to a store that has been running without it, and has therefore
     * never been told anything.
     */
    public val liveSegmentNumbers: Set<Long>
        get() {
            checkOpen()
            val version = versions.acquireCurrent()
            return try {
                version.segments().mapTo(HashSet()) { it.number }
            } finally {
                version.release()
            }
        }

    /**
     * Replays one segment into an observation.
     *
     * The "one call per distinct user key" rule lives in [DistinctKeyFilter], shared with
     * [SegmentWriter], because the two paths have to agree: a segment sketched as it was written and
     * the same segment sketched by a backfill must produce the same counts, or "model later" would
     * quietly mean "model differently".
     */
    private fun backfillSegment(observer: SegmentObserver, table: SegmentTable) {
        val observation = Observers.begin(observer, table.number) ?: return
        val keys = DistinctKeyFilter()
        try {
            table.cursor().use { cursor ->
                cursor.seekToFirst()
                while (cursor.valid()) {
                    val userKeyLength = cursor.keyLength - SegmentFormat.TAG_BYTES
                    if (keys.isNewKey(cursor.key, userKeyLength)) {
                        observation.observe(cursor.userKey(), cursor.sequence(), cursor.document())
                    }
                    cursor.next()
                }
            }
        } catch (failure: Throwable) {
            observation.abandon()
            throw failure
        }
        observation.complete(
            SegmentSummary(
                segmentNumber = table.number,
                entryCount = table.metadata.entryCount,
                distinctKeyCount = keys.count,
                fileBytes = table.metadata.fileBytes,
            ),
        )
    }

    /**
     * Forces and closes the log, then releases the directory.
     *
     * The final force happens whatever the durability setting: a clean shutdown is exactly the point
     * at which [Durability.BUFFERED] should stop being a gamble. Idempotent.
     */
    override fun close() {
        // Stopped before the lock is taken: the worker may be inside a compaction that wants it, and
        // waiting for it there while holding it is the one way this deadlocks.
        maintenance?.close()
        writeLock.withLock {
            if (closed) return
            closed = true
            try {
                // Nothing to save if the log is already broken, and forcing it would only replace
                // the original failure with a second one.
                if (failure == null) log.sync()
            } finally {
                // Whatever the force did, the channel, the staging arena, the mapped segments and
                // the directory lock have to be released — a store that cannot be reopened is a
                // worse outcome than a store whose last bytes did not reach the platter.
                try {
                    log.close()
                } finally {
                    try {
                        versions.close()
                    } finally {
                        directoryLock.close()
                    }
                }
            }
        }
    }

    override fun toString(): String = "DocumentStore($directory, $stats)"

    // --- reads --------------------------------------------------------------------------------

    /**
     * The read order, in one place.
     *
     * Memtables newest first, then segments through the version. The first source that knows
     * anything about the key answers, tombstone included: a source further down holds an *older*
     * version by construction, and reaching it would be undoing whatever the newer one said.
     */
    private fun lookup(key: Key, maxSequence: Long, view: StoreState, pinned: Version?): Variant? {
        view.active.get(key, maxSequence)?.let { return materialise(it) }
        for (sealed in view.sealed.asReversed()) {
            sealed.memtable.get(key, maxSequence)?.let { return materialise(it) }
        }
        if (pinned != null) return pinned.get(key, maxSequence)?.document

        // Without a snapshot the version has to be pinned for the length of the lookup: the
        // returned document is a view over a mapped file, so it is materialised before the
        // reference goes.
        val version = versions.acquireCurrent()
        try {
            return version.get(key, maxSequence)?.document?.let { copyOut(it) }
        } finally {
            version.release()
        }
    }

    /**
     * Copies a document out of a mapping.
     *
     * The price of [get] not requiring a snapshot: once the version is released the mapping may be
     * unmapped by a compaction, and a `Variant` over freed memory is not a stale answer but a
     * fault. A caller who wants the zero-copy view takes a snapshot and keeps it open, which is
     * exactly the trade a snapshot exists to offer.
     */
    private fun copyOut(document: Variant): Variant =
        Variant(VariantMetadata.of(document.metadata.toByteArray()), document.toByteArray())

    /**
     * Closes the cursors a half-built scan had already opened.
     *
     * A segment cursor holds its table open, so abandoning one leaves a mapping alive — and on
     * Windows a live mapping is a file that cannot be deleted at all, which turns a failed `scan`
     * into a segment that no compaction can ever reclaim. Each close is guarded so the first failure
     * does not strand the cursors behind it.
     */
    private fun closeAll(cursors: List<EntryCursor>) {
        for (cursor in cursors) runCatching { cursor.close() }
    }

    // --- maintenance --------------------------------------------------------------------------

    /**
     * Flushes every sealed memtable, then compacts until no level is over budget.
     *
     * A loop rather than one step: a flush adds a level-0 file, which may be the one that trips the
     * level-0 trigger, and a compaction into level 1 may push level 1 over its own budget. Doing one
     * step per wake-up would leave the tree permanently one step behind.
     */
    private fun runMaintenance() {
        maintenanceLock.withLock {
            while (!closed) {
                if (flushOneMemtable()) continue
                if (compactOnce()) continue
                return
            }
        }
    }

    /** Runs maintenance to completion here, wherever "here" is. */
    private fun drain() {
        val worker = maintenance
        if (worker == null) {
            runMaintenance()
            failure?.let { throw StoreFailedException("store at $directory stopped writing after a failure", it) }
        } else {
            worker.schedule()
            worker.awaitIdle()
        }
    }

    private fun flushOneMemtable(): Boolean {
        val current = state
        val sealed = current.sealed.firstOrNull() ?: return false

        // The oldest log still needed once this one is gone: the next sealed memtable's, or the
        // active memtable's if this was the last. Computed before the flush, from the same state
        // the flush reads, so a concurrent rotation cannot make it too high.
        val nextLogNumber = current.sealed.getOrNull(1)?.logNumber ?: current.activeLogNumber
        flushMemtable(directory, options, versions, sealed, nextLogNumber)

        writeLock.withLock {
            val now = state
            state = StoreState(now.active, now.activeLogNumber, now.sealed.drop(1))
        }
        return true
    }

    private fun compactOnce(): Boolean {
        val version = versions.acquireCurrent()
        try {
            val compaction = picker.pick(version) ?: return false
            runCompaction(
                directory,
                options,
                versions,
                version,
                compaction,
                snapshots.oldestSequence(unpinned = visibleSequence),
            )
            return true
        } finally {
            version.release()
        }
    }

    // --- internals ----------------------------------------------------------------------------

    /**
     * Runs [body], and stops the store from writing again if the filesystem failed.
     *
     * Every IO failure on the log path is treated alike, including a failed rotation: rotation forces
     * the log it is sealing, so a failure there is a failure to make *acknowledged* commits durable,
     * and a half-completed rotation is not a state this design tries to repair in place.
     *
     * Failures that are not IO are left alone — a batch rejected for its size throws before a byte is
     * written and leaves the log intact, so it must not condemn the store.
     */
    private inline fun <T> guardIo(body: () -> T): T = try {
        body()
    } catch (broken: IOException) {
        failure = broken
        throw broken
    }

    private fun rotateLocked() {
        val current = state
        if (current.active.isEmpty()) return

        // Force the log being left behind before the new one exists. Recovery relies on this: a log
        // that is not the newest is treated as complete, so it must be, and the check that catches
        // any violation is that a sealed log with a torn tail is reported as corruption.
        log.sync()
        val nextNumber = nextLogNumber++
        val next = LogWriter.create(directory, nextNumber, lastSequence + 1)
        log.close()
        log = next
        state = StoreState(
            active = Memtable(),
            activeLogNumber = nextNumber,
            sealed = current.sealed + SealedMemtable(current.active, current.activeLogNumber),
        )
        maintenance?.schedule()
    }

    private fun materialise(value: MemtableValue): Variant? = when (value) {
        MemtableValue.Deleted -> null
        is MemtableValue.Present -> Variant(VariantMetadata.of(value.metadata), value.value)
    }

    private fun checkOpen() {
        if (closed) throw StoreClosedException("store at $directory is closed")
    }

    private fun checkWritable() {
        checkOpen()
        failure?.let {
            throw StoreFailedException("store at $directory stopped writing after an IO failure", it)
        }
    }

    /**
     * Puts the store into the failed state, as a real IO fault would.
     *
     * This is where a background flush or compaction reports a failure: a store whose maintenance
     * cannot run will fill its memtables and its directory, and carrying on writing into that is
     * worse than stopping.
     *
     * It began as a way to test the *policy* — writes refused, reads still served, close still
     * releasing the directory — before there was any way to produce the fault. There is now: the
     * fault-injecting filesystem in `rabosh-testkit` fails the real write, and `IoFailureTest`
     * checks the whole path rather than the state at the end of it. The hook stays because
     * maintenance needs it, not because tests do.
     */
    internal fun markFailed(cause: Throwable) {
        failure = cause
    }

    /**
     * The live version, for tests that assert on the shape of the tree rather than on its answers.
     *
     * Internal and unpinned: a caller must not hold the result, which is why nothing public returns
     * one. What a *reader* wants is [snapshot], which pins what it hands back.
     */
    internal val liveVersion: Version get() = versions.current

    public companion object {
        /**
         * Opens the store in [directory], replaying its manifest and its logs.
         *
         * Recovery reads `CURRENT` for the manifest in force, folds its edits into the set of live
         * segments, and then replays only the logs the manifest says are still needed. Each log is
         * checked as it goes to begin where the previous one ended; the newest may have an
         * interrupted tail — it is the one a dying process can have been in the middle of — and is
         * truncated back to its last complete record before it is appended to again. Any other fault
         * is reported; see [LogRecoveryMode].
         *
         * @throws StoreLockedException if another process, or another store in this one, holds it.
         * @throws CorruptLogException if a log cannot be replayed.
         * @throws CorruptManifestException if the manifest cannot be replayed.
         * @throws CorruptSegmentException if a segment the manifest names cannot be read.
         * @throws UnsupportedFormatException if the files are from a newer format version.
         * @throws NoSuchFileException if the directory is absent and
         *   [StoreOptions.createIfMissing] is `false`.
         *
         * **Outside the stable core**, and this is the only entrance to it, which is why the marker
         * is here rather than on every member of the class. `Rabosh.open` is the stable way to open
         * a database; assembling the store, the schema catalog and the index catalog by hand is
         * supported and is not a signature anything is promised about. See `STABILITY.md`.
         */
        @RaboshExperimental
        public fun open(directory: Path, options: StoreOptions = StoreOptions.DEFAULT): DocumentStore {
            prepareDirectory(directory, options)
            val lock = DirectoryLock.acquire(directory)
            try {
                return recover(directory, options, lock)
            } catch (failure: Throwable) {
                try {
                    lock.close()
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
        }

        private fun prepareDirectory(directory: Path, options: StoreOptions) {
            if (Files.isDirectory(directory)) return
            if (!options.createIfMissing) {
                throw NoSuchFileException("$directory does not exist and createIfMissing is false")
            }
            Files.createDirectories(directory)
            // The new directory's own name has to reach the platter too, or a power loss leaves a
            // store whose contents are durable and whose directory entry is not.
            directory.parent?.let(::syncDirectory)
        }

        private fun recover(
            directory: Path,
            options: StoreOptions,
            lock: DirectoryLock,
        ): DocumentStore {
            val versions = VersionSet(directory, options)
            try {
                return recoverInto(directory, options, lock, versions)
            } catch (failure: Throwable) {
                try {
                    versions.close()
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
        }

        private fun recoverInto(
            directory: Path,
            options: StoreOptions,
            lock: DirectoryLock,
            versions: VersionSet,
        ): DocumentStore {
            val files = listStoreFiles(directory)
            val logNumbers = files.filter { it.kind == StoreFileKind.LOG }.map { it.number }.sorted()
            files.firstOrNull { it.kind == StoreFileKind.UNKNOWN && it.name.endsWith(".wal") }?.let {
                throw CorruptLogException("file name is not a log number", it.name)
            }

            if (!versions.recover()) {
                // No manifest. A directory with files in it and no record of them is a state this
                // build does not recognise, and guessing at it is exactly what the engine's rule
                // about unknown data forbids.
                if (logNumbers.isNotEmpty() || files.any { it.kind == StoreFileKind.SEGMENT }) {
                    throw CorruptManifestException(
                        "the directory holds store files but no CURRENT naming a manifest",
                        CURRENT_FILE_NAME,
                    )
                }
                versions.create(firstLogNumber = FIRST_LOG_NUMBER, firstFileNumber = FIRST_FILE_NUMBER)
                val log = LogWriter.create(directory, FIRST_LOG_NUMBER, LogFormat.FIRST_SEQUENCE)
                return DocumentStore(
                    directory,
                    options,
                    lock,
                    versions,
                    log,
                    Memtable(),
                    activeLogNumber = FIRST_LOG_NUMBER,
                    nextLogNumber = FIRST_LOG_NUMBER + 1,
                    lastSequence = 0,
                )
            }

            // A segment or manifest number that exists on disk but is not in the manifest belongs to
            // a process that died before it could record it. Handing that number out again would
            // meet a file that is already there, and `CREATE_NEW` would refuse.
            versions.reserveFileNumbersAbove(
                files.filter { it.kind == StoreFileKind.SEGMENT || it.kind == StoreFileKind.MANIFEST }
                    .maxOfOrNull { it.number } ?: 0,
            )

            val needed = logNumbers.filter { it >= versions.logNumber }
            val memtable = Memtable()
            var replay: LogReplay? = null
            var expected: Long? = null
            for ((index, number) in needed.withIndex()) {
                replay = LogReader.replay(
                    path = directory.resolve(logFileName(number)),
                    number = number,
                    expectedFirstSequence = expected,
                    isNewest = index == needed.lastIndex,
                    mode = options.recoveryMode,
                ) { sequence, operation -> apply(memtable, sequence, operation) }
                expected = replay.nextSequence
            }

            deleteOrphans(directory, files, versions, needed.toSet())

            val activeLogNumber: Long
            val log: LogWriter
            val lastSequence: Long
            var nextLog = (logNumbers.maxOrNull() ?: 0) + 1
            if (replay == null) {
                // Every log has been flushed away, or there never was one. The manifest's sequence
                // is where the store left off.
                activeLogNumber = nextLog++
                lastSequence = versions.lastSequence
                log = LogWriter.create(directory, activeLogNumber, lastSequence + 1)
                versions.apply(VersionEdit().also { it.logNumber = activeLogNumber })
            } else {
                activeLogNumber = needed.last()
                lastSequence = replay.nextSequence - 1
                val path = directory.resolve(logFileName(activeLogNumber))
                log = if (replay.header == null) {
                    // The header was never fully written, which only a log created by a process that
                    // then died can be. Rewrite it with the sequence the store actually reached, so
                    // the continuity check across files keeps holding.
                    Files.delete(path)
                    LogWriter.create(directory, activeLogNumber, replay.nextSequence)
                } else {
                    LogWriter.openForAppend(path, activeLogNumber, replay.header.firstSequence, replay.validLength)
                }
            }
            versions.rememberSequence(lastSequence)
            // The oldest replayed log is the one the memtable belongs to, so a flush of it frees
            // every log that fed it rather than only the newest.
            val memtableLogNumber = needed.firstOrNull() ?: activeLogNumber
            return DocumentStore(
                directory,
                options,
                lock,
                versions,
                log,
                memtable,
                activeLogNumber = memtableLogNumber,
                nextLogNumber = nextLog,
                lastSequence = lastSequence,
            )
        }

        /**
         * Removes files no version names and no log replay needs.
         *
         * Every one of them is the residue of a process that died between writing a file and
         * recording it, so none is reachable and none will ever be read. Deleting them at open is
         * the only moment the engine can be sure of that, because it is the only moment nothing
         * else is running.
         */
        private fun deleteOrphans(
            directory: Path,
            files: List<StoreFile>,
            versions: VersionSet,
            neededLogs: Set<Long>,
        ) {
            val live = versions.liveFileNumbers()
            for (file in files) {
                val obsolete = when (file.kind) {
                    StoreFileKind.LOG -> file.number !in neededLogs
                    StoreFileKind.SEGMENT -> file.number !in live
                    StoreFileKind.MANIFEST -> file.number != versions.currentManifestNumber
                    else -> false
                }
                if (obsolete) runCatching { Files.deleteIfExists(directory.resolve(file.name)) }
            }
        }

        private fun apply(memtable: Memtable, sequence: Long, operation: Operation) {
            when (operation.kind) {
                OperationKind.PUT ->
                    memtable.put(operation.key, sequence, operation.metadata, operation.value)

                OperationKind.DELETE -> memtable.delete(operation.key, sequence)
            }
        }

        /** Logs are numbered from one, so zero can mean "no log" without ambiguity. */
        private const val FIRST_LOG_NUMBER = 1L

        /** Segments and manifests share a counter of their own; it starts at one for the same reason. */
        private const val FIRST_FILE_NUMBER = 1L

        /**
         * Keys per commit in [deleteRange].
         *
         * A compromise between two costs that move in opposite directions: a larger batch means
         * fewer `force` calls, and a smaller one means a smaller log record and less to redo if a
         * commit fails. A thousand tombstones is a few tens of kilobytes, which is comfortably inside
         * what the log frames and well past the point where the per-commit force stops dominating.
         */
        internal const val DEFAULT_DELETE_BATCH: Int = 1000
    }
}
