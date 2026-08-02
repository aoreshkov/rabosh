package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObservation
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.SegmentSummary
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The indexes a store has, and the sidecars that carry them.
 *
 * ```kotlin
 * val indexes = IndexCatalog(directory)
 * DocumentStore.open(directory, StoreOptions(segmentObserver = indexes)).use { store ->
 *     indexes.attach(store)
 *     val handle = indexes.createIndex(store, IndexDefinition.inverted("$.team"))
 *     store.snapshot().use { snapshot ->
 *         indexes.read(store, handle, snapshot).use { reader ->
 *             println(IndexQuery.keysEqualTo(store, reader, IndexTerm.ofString("analytics")))
 *         }
 *     }
 * }
 * ```
 *
 * **An index is per-segment immutable sidecar files, and that single constraint produces everything
 * this class does.** Creating one over existing data writes new files against segments that are
 * already written, so no document is rewritten and [createIndex] over ten million documents costs a
 * scan rather than a rebuild. A query uses the sidecars that exist and scans where they do not, so an
 * index is usable *while it is still building*, with no cutover. [dropIndex] deletes files. And a
 * compaction replaces the sidecars of the segments it consumed with sidecars for the segments it
 * produced, as a consequence of the merge rather than as a step anybody has to remember.
 *
 * **There is no compaction-time merge of posting lists, and that is a decision rather than a gap.**
 * Ordinals are positions within a segment, so a compaction renumbers every one of them; merging two
 * input posting lists would mean remapping every ordinal in both. Reading the term back out of the
 * document — which the compaction is already holding, already decoded, on a pass it was making anyway
 * — is strictly less work than that. So compaction awareness here is structural: each output segment
 * opens its own observation, and `retain` prunes the inputs.
 *
 * **Two objects and the order matters, as it does for the catalog — and here it is three.** The
 * observer has to be installed in `StoreOptions` before the store opens, because a flush can begin
 * the moment it does; [attach] loads the sidecars and covers whatever they do not; and [close] must
 * be called, because nothing else releases the mappings and on Windows a mapped file cannot be
 * deleted at all. That is honest and it is not ergonomic. Smoothing it over is `rabosh-api`'s job.
 *
 * **Thread safety.** Safe from any thread. The store's maintenance thread drives [beginSegment],
 * `complete` and [retain] while a reader may be inside [read]; one segment's accumulation happens on
 * one thread and is not shared. Since phase 15 this catalog has a thread of its own as well — see
 * [createIndexInBackground] — which drives exactly the same three callbacks and is subject to exactly
 * the same rules.
 */
public class IndexCatalog(
    /** The store directory sidecars live in. The same directory the store was opened on. */
    public val directory: Path,
    /** Tuning. See [IndexOptions]. */
    public val options: IndexOptions = IndexOptions.DEFAULT,
) : SegmentObserver, AutoCloseable {

    private val lock = ReentrantLock()
    private val open = HashMap<Long, SegmentIndex>()

    /**
     * Segments that have left the tree but are still being read.
     *
     * A retired segment owns its own files: they are deleted by its last reader letting go, not by
     * whoever retired it. Between those two moments it is no longer in [open], and without this
     * [sweep] — which deletes by *name*, from a directory listing — would take the files out from
     * under a reader that is inside them. Entries leave as soon as their last reference does.
     */
    private val retiring = ArrayList<SegmentIndex>()
    private val failures = ArrayList<Throwable>()
    private var registry = RegistryContents.EMPTY
    private var liveSegments: Set<Long> = emptySet()
    private var attached = false
    private var closed = false

    /**
     * The one thread background builds run on, created on demand and dropped by [stopBackgroundBuilds].
     *
     * **Serial, and that is the design rather than a simplification.** One pass covers *every* index
     * a segment is missing, so two passes running at once would race to write the same sidecar and
     * gain nothing for it; queueing them means the second finds the first's work already done and
     * skips it. Segments could be built in parallel *within* one pass, which is a real opportunity and
     * a memory-profile decision — it is carried as an open question beside parallel query execution,
     * not taken here.
     *
     * **A platform daemon thread**, for the reasons `Maintenance` in the storage core is one: it
     * spends its life blocked on file IO, there is exactly one of it, and everything it does is
     * restartable — so being killed costs work and never data.
     */
    private var builder: ExecutorService? = null

    /** Builds queued or running, so [stopBackgroundBuilds] can reach every one of them. */
    private val builds = ArrayList<IndexBuild>()

    /**
     * Run at the top of every segment of a background pass. `null` in every build that is not a test.
     *
     * A seam, and it exists for the reason `:rabosh-bench:holdJmhLock` does: the interesting state
     * here is a build stopped *part way*, and there is no way to reach it deterministically from
     * outside — a cancel issued from a test thread lands wherever the machine's speed puts it, which
     * on a fast one is reliably "after the build already finished". A test that only ever exercised
     * the completed path while claiming to test cancellation is precisely the shape this project
     * refuses. With this, `IndexBackgroundBuildTest` cancels on a known segment and asserts an exact
     * count of what was covered.
     *
     * `internal`, so it is not in the published ABI, and `@Volatile` because it is set on the caller's
     * thread and read on the worker's.
     */
    @Volatile
    internal var backgroundSegmentHook: ((Long) -> Unit)? = null

    /** Whether [attach] has been called. Nothing is read before it has. */
    public val isAttached: Boolean get() = lock.withLock { attached }

    /**
     * Failures raised inside this catalog's own callbacks, in order.
     *
     * A sidecar that could not be written, one that would not decode, a segment whose term budget was
     * exceeded. None of them propagates into the write path — derived data must not cost a document —
     * and each leaves its segment simply not covered, which `IndexReader.coverage` reports and
     * [rebuild] fixes.
     */
    public val problems: List<Throwable> get() = lock.withLock { failures.toList() }

    // --- attachment ------------------------------------------------------------------------------

    /**
     * Loads the registry and the sidecars, builds whatever is missing, and starts maintaining them.
     *
     * Idempotent and cheap to repeat: a segment already covered by every defined index is skipped
     * without being read. Segments that are not are read once, in key order, exactly as a compaction
     * would read them — see `DocumentStore.backfill`.
     *
     * @param backfill whether to build sidecars for segments that have none. Passing `false` attaches
     *   to whatever is already on disk and returns immediately, which is what a caller who does not
     *   want a blocking scan of a large store wants — the segments left uncovered are scanned by
     *   queries in the meantime, and a later `attach` finishes the job. Coverage reports the state
     *   honestly either way.
     * @throws CorruptIndexException if a sidecar will not decode and [IndexOptions.damagedSidecars]
     *   is [DamagedIndexPolicy.REPORT].
     */
    @JvmOverloads
    public fun attach(store: DocumentStore, backfill: Boolean = true) {
        checkOpen()
        lock.withLock { registry = IndexRegistry.read(directory) ?: RegistryContents.EMPTY }
        loadSidecars(store.liveSegmentNumbers)
        if (backfill) store.backfill(this)
        // Asked for rather than remembered. What `retain` last reported may predate a flush or a
        // compaction that landed during the scan, and reclamation is about to run against this set.
        val live = store.liveSegmentNumbers
        lock.withLock {
            liveSegments = live
            attached = true
        }
        prune(live)
        sweep(live)
    }

    /**
     * Discards every sidecar and rebuilds them from the segments, keeping the index definitions.
     *
     * The escape hatch that makes this layer's relaxed attitude to sidecar durability defensible: a
     * posting list is derived, so damage costs a scan rather than data. The **definitions** are not
     * derived and are deliberately kept — losing those to a repair would turn a corrupt file into a
     * lost instruction, which is the one thing the registry's durability rule exists to prevent.
     */
    public fun rebuild(store: DocumentStore) {
        checkOpen()
        // A repair that raced a build would delete files the build was mid-way through replacing, and
        // the survivor would be whichever finished last.
        stopBackgroundBuilds()
        val held = lock.withLock {
            attached = false
            val all = open.values.toList()
            open.clear()
            all
        }
        for (segment in held) {
            segment.retire(segment.files())
            segment.release()
        }
        for (name in listSidecarNames()) runCatching { Files.deleteIfExists(directory.resolve(name)) }
        attach(store)
    }

    // --- the indexes -----------------------------------------------------------------------------

    /** The defined indexes, ascending by id. */
    public fun indexes(): List<IndexHandle> = lock.withLock { registry.indexes.toList() }

    /** The index with this id, or `null`. */
    public fun index(id: Int): IndexHandle? = lock.withLock { registry.indexes.firstOrNull { it.id == id } }

    /**
     * Defines an index and builds it over everything already written.
     *
     * Returns the existing handle if this store already has an index over the same path and kind:
     * creating a second copy of one is a mistake rather than an intent, and giving it a new id would
     * double the sidecars silently.
     *
     * **The definition is made durable before a single posting file exists**, and that order is the
     * whole of the crash story. A crash here leaves an index that is defined and uncovered, which is
     * a state every query already handles by scanning. The other order would leave posting files for
     * an index nothing knows about — files nothing would ever read and nothing would ever delete.
     *
     * Runs the build on the calling thread. Documents still in a memtable are not indexed; call
     * `DocumentStore.flush` first if complete coverage is wanted immediately.
     */
    public fun createIndex(store: DocumentStore, definition: IndexDefinition): IndexHandle {
        checkOpen()
        checkAttached()
        val existing = lock.withLock { registry.indexes.firstOrNull { it.definition == definition } }
        if (existing != null) return existing

        val handle = define(store, definition)
        store.backfill(this)
        return handle
    }

    /**
     * Defines an index and builds it on the catalog's own thread, returning at once.
     *
     * ```kotlin
     * val build = indexes.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
     * // queries work immediately, scanning what the index does not cover yet
     * build.await()
     * ```
     *
     * The returned [IndexBuild.handle] is usable straight away, for the reason [createIndex] documents:
     * the definition is made durable before a single posting file exists, so there is a registered
     * index before there is anything to build it from. Coverage grows from none to complete with no
     * cutover, which is what per-segment sidecars have always allowed and what a blocking build was
     * simply never able to expose.
     *
     * **Unlike [createIndex], this runs a pass even when the definition already exists**, and the
     * difference is the whole of the resumption story. A build that was cancelled, or that a crash cut
     * short, leaves an index defined and partly covered; calling this again with the same definition
     * skips every segment already covered — [beginSegment] answers `null` for those without reading
     * them — and finishes the rest. There is no separate "resume" verb because there is no separate
     * state to resume from.
     *
     * Documents still in a memtable are not indexed, exactly as for [createIndex]; call
     * `DocumentStore.flush` first if complete coverage is wanted.
     */
    public fun createIndexInBackground(store: DocumentStore, definition: IndexDefinition): IndexBuild {
        checkOpen()
        checkAttached()
        val existing = lock.withLock { registry.indexes.firstOrNull { it.definition == definition } }
        return submit(store, IndexBuild(existing ?: define(store, definition)))
    }

    /**
     * Builds sidecars for whatever is not yet covered, on the catalog's own thread, returning at once.
     *
     * The non-blocking half of [attach]: `attach(store, backfill = false)` takes what is on disk and
     * returns immediately, and this covers the rest without holding anybody up. That pairing is what
     * lets a store with a large uncovered backlog open instantly and converge afterwards, instead of
     * choosing between a slow open and an index that never finishes building.
     *
     * Its [IndexBuild.handle] is `null`: covering what the sidecars do not is a fact about the catalog
     * rather than about any one index.
     */
    public fun buildIndexesInBackground(store: DocumentStore): IndexBuild {
        checkOpen()
        checkAttached()
        return submit(store, IndexBuild(null))
    }

    /**
     * Cancels every background build and waits for the one in flight to reach a segment boundary.
     *
     * Called by [close], and public because a lifecycle owner has to be able to stop the worker
     * *before* it closes the store the worker is scanning — which is what `Rabosh.close` does. After
     * this the catalog still works: a later [createIndexInBackground] starts a fresh worker.
     *
     * **Cancelled rather than waited out.** A flush must be allowed to finish because it holds data
     * the log would otherwise have to keep; an index build holds nothing at all, so abandoning one
     * costs a rescan and no more. That asymmetry between data and derived data is the same one that
     * lets a sidecar go unforced before the manifest names its segment.
     */
    public fun stopBackgroundBuilds() {
        val (executor, running) = lock.withLock { builder to builds.toList() }
        // Cancelled before the shutdown, so a queued build finds the flag already set and finishes
        // without scanning anything rather than being started and then stopped.
        for (build in running) build.cancel()
        if (executor == null) return
        // `shutdown`, never `shutdownNow`. Interrupting this worker would interrupt it inside a
        // `FileChannel` read — and an interrupted channel is a *closed* channel, which would take out
        // a segment the store is still using. Every build is already cancelled by the loop above, so
        // the queue drains at once and the running one stops at its next segment.
        executor.shutdown()
        val stopped = executor.awaitTermination(BUILD_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        lock.withLock { if (builder === executor) builder = null }
        if (!stopped) {
            // A bound rather than a deadline, for the reason `Maintenance.CLOSE_TIMEOUT_MILLIS` is one:
            // waiting forever would make closing depend on how much data happened to be being indexed.
            // Reported rather than thrown — the caller asked to stop, and a slow segment is not their
            // error; the thread is a daemon and its own `reopen` releases whatever it mapped.
            observerFailed(
                IndexStateException(
                    "a background index build in $directory did not stop within " +
                        "$BUILD_SHUTDOWN_TIMEOUT_SECONDS seconds",
                ),
            )
        }
    }

    /** Registers [definition] durably and hands back its handle. The registry write is the crash story. */
    private fun define(store: DocumentStore, definition: IndexDefinition): IndexHandle {
        val updated = lock.withLock {
            registry = registry.with(IndexHandle(registry.nextIndexId, definition, store.sequence))
            registry
        }
        IndexRegistry.write(directory, updated)
        return updated.indexes.last { it.definition == definition }
    }

    /** Queues [build] on the worker, starting one if this is the first. */
    private fun submit(store: DocumentStore, build: IndexBuild): IndexBuild {
        val executor = lock.withLock {
            // Reentrant, so the state cannot change between the check and the queueing below.
            checkOpen()
            builds.add(build)
            builder ?: Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("rabosh-index-build-", 0).factory(),
            ).also { builder = it }
        }
        try {
            executor.execute { run(store, build) }
        } catch (rejected: RejectedExecutionException) {
            // The worker shut down between the two statements. Cancelled rather than failed: nobody
            // asked for anything that went wrong, and nothing was built.
            lock.withLock { builds.remove(build) }
            build.finish(IndexBuildState.CANCELLED, null)
        }
        return build
    }

    /** One pass, on the worker thread. */
    private fun run(store: DocumentStore, build: IndexBuild) {
        try {
            if (build.isCancelled || lock.withLock { closed }) {
                build.finish(IndexBuildState.CANCELLED)
                return
            }
            // Read here rather than at submission, so a queued build sizes itself against the store it
            // will actually scan rather than the one that existed when it was asked for.
            build.begin(store.liveSegmentNumbers.size)
            store.backfill(BuildPass(build))
            build.finish(if (build.isCancelled) IndexBuildState.CANCELLED else IndexBuildState.COMPLETED)
        } catch (thrown: Throwable) {
            // Recorded in `problems` as well as on the build: a caller who never looks at the build
            // still gets the catalog's usual account of what went wrong.
            observerFailed(thrown)
            build.finish(IndexBuildState.FAILED, thrown)
        } finally {
            lock.withLock { builds.remove(build) }
        }
    }

    /**
     * One background pass, wrapping this catalog to count and to stop.
     *
     * A wrapper rather than a flag read inside [beginSegment], because that method is also driven by
     * every flush and every compaction on the store's maintenance thread — counting those as progress
     * would make a build's percentage depend on how busy the store was, and cancelling there would
     * stop a flush from writing its sidecar, which nobody asked for.
     *
     * Cancellation needs nothing from the storage core: `DocumentStore.backfill` skips a segment whose
     * `beginSegment` answers `null` without opening a cursor, so "stop" costs one map lookup per
     * remaining segment. The contract that already lets a covered segment be skipped is the contract
     * that lets a build be stopped.
     */
    private inner class BuildPass(private val build: IndexBuild) : SegmentObserver {
        override fun beginSegment(segmentNumber: Long): SegmentObservation? {
            if (build.isCancelled) return null
            val observation = this@IndexCatalog.beginSegment(segmentNumber)
            // The seam sits *after* the observation exists, because that is the only interesting
            // instant: its targets are fixed, nothing is written yet, and a `dropIndex` arriving now
            // is the one that would leave a posting file for an index nobody has. A hook before this
            // line would see a registry the observation had not read yet, which is a window with
            // nothing in it.
            backgroundSegmentHook?.invoke(segmentNumber)
            if (build.isCancelled) {
                // Nothing to undo — an observation writes nothing until it completes — so the segment
                // is simply not counted, and a later build finds it uncovered and does it properly.
                observation?.abandon()
                return null
            }
            build.segmentVisited()
            if (observation == null) return null
            return CountedObservation(observation) { build.segmentBuilt() }
        }

        override fun retain(liveSegments: Set<Long>): Unit = this@IndexCatalog.retain(liveSegments)

        override fun observerFailed(cause: Throwable): Unit = this@IndexCatalog.observerFailed(cause)
    }

    /** An observation that tells a build when it is over, whichever way it ended. */
    private class CountedObservation(
        private val delegate: SegmentObservation,
        private val onFinished: () -> Unit,
    ) : SegmentObservation {
        override fun observe(userKey: Key, sequence: Long, document: Variant?): Unit =
            delegate.observe(userKey, sequence, document)

        override fun complete(summary: SegmentSummary) {
            try {
                delegate.complete(summary)
            } finally {
                onFinished()
            }
        }

        override fun abandon() {
            try {
                delegate.abandon()
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Removes an index and deletes its posting files.
     *
     * The base sidecars survive: they carry the key block and the present bitmap, which belong to the
     * segment rather than to any index, and rebuilding them is exactly the cost the split into two
     * files exists to avoid.
     *
     * A file still being read is unmapped and deleted when the last reader closes, so this returns
     * without waiting. Its id is never handed out again — a stale posting file left by a crash must
     * not be readable as some later index's postings.
     */
    public fun dropIndex(handle: IndexHandle) {
        checkOpen()
        val updated = lock.withLock {
            if (registry.indexes.none { it.id == handle.id }) return
            registry = registry.without(handle.id)
            registry
        }
        IndexRegistry.write(directory, updated)

        val held = lock.withLock {
            val all = open.values.toList()
            open.clear()
            all
        }
        for (segment in held) {
            segment.retire(
                listOf(
                    directory.resolve(postingFileName(segment.segmentNumber, handle.id)),
                    directory.resolve(columnFileName(segment.segmentNumber, handle.id)),
                ),
            )
            segment.release()
        }
        // Reopened without the dropped index in hand, so its posting file is never mapped again even
        // if a reader is still holding the previous mapping and has delayed the delete.
        for (segment in held) {
            reopen(segment.segmentNumber, updated.indexes)
        }
        for (name in listSidecarNames()) {
            val id = postingNumbers(name)?.second ?: columnNumbers(name)?.second
            if (id == handle.id) runCatching { Files.deleteIfExists(directory.resolve(name)) }
        }
    }

    /**
     * Opens a reader over [handle] at [snapshot], pinning every sidecar it may consult.
     *
     * Close it. On Windows a mapped file cannot be deleted, so a reader left open blocks reclamation
     * of every segment it touched — which `IndexLifecycleTest` asserts in both directions.
     */
    public fun read(store: DocumentStore, handle: IndexHandle, snapshot: Snapshot): IndexReader {
        require(handle.kind == IndexKind.INVERTED) {
            "index #${handle.id} is a ${handle.kind}; open it with readColumn"
        }
        val pinned = pin(store)
        return IndexReader(handle, snapshot, options, pinned.live, pinned.segments, pinned.unflushed)
    }

    /**
     * Opens a reader over the shredded column [handle] at [snapshot], pinning every sidecar it needs.
     *
     * Close it, for the reason [read]'s result must be closed.
     */
    public fun readColumn(store: DocumentStore, handle: IndexHandle, snapshot: Snapshot): ColumnReader {
        require(handle.kind == IndexKind.SHREDDED_COLUMN) {
            "index #${handle.id} is a ${handle.kind}; open it with read"
        }
        val pinned = pin(store)
        return ColumnReader(handle, snapshot, options, pinned.live, pinned.segments, pinned.unflushed)
    }

    private class Pinned(val live: Set<Long>, val segments: List<SegmentIndex>, val unflushed: Boolean)

    private fun pin(store: DocumentStore): Pinned {
        checkOpen()
        checkAttached()
        // The memtable is checked *before* the live segment set is read, and that order is what makes
        // `IndexReader.isAuthoritative` sound: anything landing in a memtable afterwards carries a
        // sequence above this snapshot's, and anything the snapshot can already see is in a segment
        // the set read below will name.
        val stats = store.stats
        val unflushed = stats.memtableEntries > 0 || stats.sealedMemtables > 0
        val live = store.liveSegmentNumbers

        val pinned = ArrayList<SegmentIndex>(live.size)
        lock.withLock {
            for (number in live) {
                val segment = open[number] ?: continue
                if (segment.acquire()) pinned.add(segment)
            }
        }
        return Pinned(live, pinned, unflushed)
    }

    /**
     * Releases every mapping, after stopping any background build.
     *
     * The build is stopped **first**, and it has to be: a pass that was still running would go on
     * writing sidecars and calling [reopen] into a catalog that has already let go of them. [reopen]
     * would notice and orphan them, so nothing would break — but the segment's work would be thrown
     * away and, on Windows, a mapping could still be live when a caller tries to remove the directory.
     * Stopping is bounded by one segment, because that is the granularity a build can be cancelled at.
     *
     * Nothing is deleted. A catalog *releases* rather than retires, because shutting down is not
     * departing and hanging deletion off "the last reference went" would make closing a catalog delete
     * the sidecars of a store that is merely stopping.
     */
    override fun close() {
        // Outside the lock and before the state flips: the worker takes this lock on every segment,
        // and waiting for it while holding it is the one way this deadlocks.
        stopBackgroundBuilds()
        val held = lock.withLock {
            if (closed) return
            closed = true
            attached = false
            val all = open.values.toList()
            open.clear()
            all
        }
        // Released, not retired. Shutting down is not departing, and hanging deletion off "the last
        // reference went" alone would make closing a catalog delete the sidecars of a live store.
        for (segment in held) segment.release()
    }

    override fun toString(): String = lock.withLock {
        "IndexCatalog($directory, ${registry.indexes.size} index(es), ${open.size} segment(s)" +
            (if (attached) ")" else ", detached)")
    }

    // --- SegmentObserver -------------------------------------------------------------------------

    override fun beginSegment(segmentNumber: Long): SegmentObservation? = lock.withLock {
        if (closed) return null
        val existing = open[segmentNumber]
        val missing = registry.indexes.filter { existing == null || it.id !in existing.indexIds }
        // "I already have this one" is what makes a repeated attach, and a createIndex over a store
        // that is mostly covered, cost only the segments that are new.
        if (existing != null && missing.isEmpty()) return null
        Collecting(segmentNumber, writeBase = existing == null, targets = missing)
    }

    override fun retain(liveSegments: Set<Long>) {
        lock.withLock {
            this.liveSegments = liveSegments
            // Deletion is deferred until `attach`. This is called during `DocumentStore.open`, which
            // is before any sidecar has been read — deleting on that first call would delete exactly
            // the derived data attaching is about to load.
            if (!attached) return
        }
        prune(liveSegments)
    }

    override fun observerFailed(cause: Throwable) {
        lock.withLock { failures.add(cause) }
    }

    // --- accumulation ----------------------------------------------------------------------------

    /**
     * Accumulates one segment.
     *
     * The *k*-th call to [observe] is ordinal *k*. That numbering is shared with
     * `DocumentStore.backfill` through the core's distinct-key filter, which is why a sidecar written
     * by a flush and the same sidecar rebuilt by a backfill are byte-identical rather than merely
     * equivalent — and why the suite can compare files instead of comparing contents.
     *
     * Each index accumulates separately and fails separately: a throw or a budget overflow while
     * building one leaves the others intact and marks only that one uncovered.
     */
    private inner class Collecting(
        private val segmentNumber: Long,
        private val writeBase: Boolean,
        private val targets: List<IndexHandle>,
    ) : SegmentObservation {
        private val base = BaseSidecarBuilder()
        private val extractor = TermExtractor(targets.map { it.path }, options)

        // One builder per target, of whichever kind the index is. Both are fed from the same walk,
        // which is what keeps an inverted index and a column over the same path agreeing about what
        // that path contains.
        private val postings = arrayOfNulls<PostingBuilder>(targets.size)
        private val columns = arrayOfNulls<ColumnBuilder>(targets.size)

        init {
            targets.forEachIndexed { index, handle ->
                when (handle.kind) {
                    IndexKind.INVERTED -> postings[index] = PostingBuilder(options.maxTermsPerSegment)
                    IndexKind.SHREDDED_COLUMN -> columns[index] = ColumnBuilder(options)
                }
            }
        }

        override fun observe(userKey: Key, sequence: Long, document: Variant?) {
            val ordinal = base.count
            // A tombstone takes an ordinal and contributes no values. It has to take one: dropping it
            // would make the numbering depend on which versions a segment happens to hold, and the
            // write path and the backfill path would stop agreeing.
            base.observe(userKey, sequence, isPut = document != null)
            if (document == null || extractor.isEmpty) return
            extractor.extract(document) { pathIndex, value ->
                postings[pathIndex]?.let { builder ->
                    val term = IndexTerm.of(value, options)
                    if (term == null) builder.addPresenceOnly(ordinal) else builder.add(term.bytes, ordinal)
                }
                columns[pathIndex]?.add(ordinal, value)
            }
        }

        /**
         * Writes the sidecars, then makes sure none of them belongs to an index that has been dropped.
         *
         * The targets were chosen when this observation began, and a segment takes a while to scan —
         * so a [dropIndex] landing in between would leave a `.pst` for an index nothing knows about:
         * a file no reader would ever open and no sweep would ever remove, which is precisely the
         * residue the registry's durability rule exists to prevent. A background build makes that
         * window ordinary rather than exotic, but it was always reachable, because a flush on the
         * store's maintenance thread is inside this method just as often.
         *
         * Checked twice rather than locked once. Holding the catalog's lock across a segment's worth
         * of file writes would block every reader's `pin` for the duration, so the write goes ahead
         * against the registry as it was and anything that left it meanwhile is deleted afterwards.
         * Deleting is safe at that point precisely because [reopen] is given the *current* handles and
         * therefore never maps a dropped index's file.
         *
         * The two checks cover different windows and only the first is arranged by a test.
         * `IndexConcurrencyTest` drops an index between an observation beginning and its writes, which
         * the check below catches; a drop landing *inside* the writes is the narrower window, it
         * cannot be scheduled from outside, and the cleanup after [reopen] is what covers it. The same
         * position `Rabosh.close` takes about its own ordering: the suite pins the invariant and the
         * argument lives here.
         */
        override fun complete(summary: SegmentSummary) {
            val bytes = base.build(segmentNumber)
            if (writeBase) BaseSidecarFile.write(directory, segmentNumber, bytes)
            val defined = lock.withLock { registry.indexes.mapTo(HashSet()) { it.id } }
            for ((position, handle) in targets.withIndex()) {
                if (handle.id !in defined) continue
                // Each index accumulates and fails separately, so a budget hit or a throw while
                // building one leaves the others intact and marks only that one uncovered.
                postings[position]?.let { builder ->
                    if (builder.overflowed) {
                        // Not covered rather than partially covered. A dictionary holding some of a
                        // path's values, with no record of which ones, is an index that returns wrong
                        // answers and nothing downstream could tell it apart from a complete one.
                        uncovered(handle, "more than ${options.maxTermsPerSegment} distinct values")
                        return@let
                    }
                    PostingFileIo.write(
                        directory,
                        segmentNumber,
                        handle.id,
                        builder.build(
                            segmentNumber = segmentNumber,
                            indexId = handle.id,
                            path = handle.path.toString(),
                            documentCount = base.count,
                            largestSequence = base.largestSequence,
                        ),
                    )
                }

                columns[position]?.let { builder ->
                    val encoded = builder.build(
                        segmentNumber = segmentNumber,
                        indexId = handle.id,
                        path = handle.path.toString(),
                        documentCount = base.count,
                        largestSequence = base.largestSequence,
                    )
                    if (encoded == null) {
                        // Either a budget was hit or nothing at the path was shreddable. Both mean
                        // not covered, and a query scans the segment rather than trusting a column
                        // that would have to claim it held values it does not.
                        uncovered(handle, builder.overflowReason ?: "no shreddable value at the path")
                        return@let
                    }
                    ColumnFileIo.write(directory, segmentNumber, handle.id, encoded)
                }
            }
            val handles = lock.withLock { registry.indexes.toList() }
            reopen(segmentNumber, handles)
            // Anything dropped while those writes were happening. `reopen` was given `handles`, so a
            // dropped index's file was never mapped and deleting it now cannot pull it from a reader.
            val survivors = handles.mapTo(HashSet()) { it.id }
            for (handle in targets) {
                if (handle.id in survivors) continue
                runCatching { PostingFileIo.delete(directory, segmentNumber, handle.id) }
                runCatching { ColumnFileIo.delete(directory, segmentNumber, handle.id) }
            }
        }

        override fun abandon() {
            // Nothing to undo: nothing is written until `complete`, and nothing is published until
            // the sidecars are on disk.
        }

        private fun uncovered(handle: IndexHandle, reason: String) {
            observerFailed(
                IndexStateException(
                    "segment $segmentNumber is not covered by index #${handle.id} over ${handle.path}: $reason",
                ),
            )
        }
    }

    // --- internals -------------------------------------------------------------------------------

    /** Maps a segment's sidecars afresh and installs them, dropping whatever was there before. */
    private fun reopen(segmentNumber: Long, handles: List<IndexHandle>) {
        val fresh = try {
            SegmentIndex.open(directory, segmentNumber, handles)
        } catch (damaged: IndexException) {
            observerFailed(damaged)
            null
        }
        // Both decisions are made under the one lock. Deciding outside it whether a freshly opened
        // segment was installed would race `close`, and the two outcomes of that race are a leaked
        // mapping and a double release — the second of which closes an arena twice.
        var orphaned: SegmentIndex? = null
        val previous = lock.withLock {
            when {
                closed -> {
                    orphaned = fresh
                    null
                }

                fresh == null -> open.remove(segmentNumber)
                else -> open.put(segmentNumber, fresh)
            }
        }
        // Released without retiring: the files are still live, they have merely been remapped.
        previous?.release()
        orphaned?.release()
    }

    private fun loadSidecars(live: Set<Long>) {
        val handles = lock.withLock { registry.indexes.toList() }
        for (number in live) {
            if (lock.withLock { number in open }) continue
            val segment = try {
                SegmentIndex.open(directory, number, handles)
            } catch (damaged: IndexException) {
                when (options.damagedSidecars) {
                    DamagedIndexPolicy.REPORT -> throw damaged
                    DamagedIndexPolicy.REBUILD -> {
                        observerFailed(damaged)
                        deleteSidecarsOf(number, handles)
                        null
                    }
                }
            } ?: continue
            val previous = lock.withLock { open.put(number, segment) }
            previous?.release()
        }
    }

    /**
     * Drops sidecars for segments that have left the tree.
     *
     * Called from [retain], which runs on every change to the set of live segments, so it works from
     * what is already open and touches the filesystem only for the files it deletes. A directory
     * listing here would put one on every flush and every compaction; that is [sweep]'s job, and
     * [attach] is the one place it is needed.
     */
    private fun prune(live: Set<Long>) {
        val horizon = deletionHorizon(live)
        val departed = lock.withLock {
            open.keys.filter { it !in live && it < horizon }.mapNotNull { open.remove(it) }
        }
        for (segment in departed) {
            segment.retire(segment.files())
            // Recorded before the release, because a reader holding this segment means the release
            // below does not delete anything and nothing else may either. See [retiring].
            lock.withLock { retiring.add(segment) }
            segment.release()
        }
        forgetReclaimed()
    }

    /** Drops retired segments whose last reader has gone; their files are already deleted. */
    private fun forgetReclaimed() {
        lock.withLock { retiring.removeAll { !it.isAlive } }
    }

    /**
     * Deletes sidecars nothing will read again, including ones this catalog never opened.
     *
     * The residue of a store that ran for a while with no index catalog attached, or of a crash
     * between writing a sidecar and naming its segment. Only a directory listing can find those, so
     * this is the one place that pays for one.
     */
    private fun sweep(live: Set<Long>) {
        val horizon = deletionHorizon(live)
        forgetReclaimed()
        val ids = lock.withLock { registry.indexes.mapTo(HashSet()) { it.id } }
        val held = lock.withLock { retiring.mapTo(HashSet()) { it.segmentNumber } }
        for (name in listSidecarNames()) {
            if (name.endsWith(".tmp")) {
                // A temporary file is the residue of a crash between writing and renaming. The real
                // file is either complete under its own name or absent; either way this is not it.
                runCatching { Files.deleteIfExists(directory.resolve(name)) }
                continue
            }
            val sidecar = postingNumbers(name) ?: columnNumbers(name)
            val segmentNumber = sidecar?.first ?: baseSegmentNumber(name) ?: continue
            if (lock.withLock { segmentNumber in open } || segmentNumber in held) {
                // Mapped: `prune` and `dropIndex` own its reclamation. A segment in `held` has left
                // the tree and is still being read, and deleting its files here would take them from
                // under a reader that is inside them — its own last reference does that job.
                continue
            }
            val dead = (segmentNumber !in live && isOrphaned(segmentNumber, horizon)) ||
                (sidecar != null && sidecar.second !in ids)
            if (dead) runCatching { Files.deleteIfExists(directory.resolve(name)) }
        }
    }

    /**
     * Whether a segment that is not in the live set is genuinely gone.
     *
     * [deletionHorizon] alone is not enough here, and a crash is what shows it. A kill can leave
     * sidecars for segments the manifest never named — numbered, necessarily, *above* everything the
     * live set mentions, because they were the newest thing being written. The horizon protects
     * exactly those, so they would survive every sweep until an ordinary flush happened to raise it.
     * For a ten-million-document index that is a lot of disk to keep on the strength of a heuristic.
     *
     * The exact test is whether the segment's own file is there. A store deletes unreferenced `.seg`
     * files as it opens, so a missing one means nothing can ever read this sidecar again; and a
     * segment being written *right now* has its file on disk before its sidecar is, so a flush racing
     * this sweep is not mistaken for an orphan. That leaves the horizon doing what it is actually
     * good at — [prune], where the live set may be arbitrarily stale and no file listing is wanted.
     *
     * The cost is knowing one filename that belongs to `rabosh-core`. It is a permanent on-disk
     * name, this layer already files its own sidecars against segment numbers, and the alternative is
     * a rule that guesses.
     */
    private fun isOrphaned(segmentNumber: Long, horizon: Long): Boolean =
        segmentNumber < horizon ||
            !Files.exists(directory.resolve(String.format(java.util.Locale.ROOT, "%010d.seg", segmentNumber)))

    /**
     * Deletes every sidecar belonging to [segmentNumber], of either kind.
     *
     * Both kinds, unconditionally, rather than the kind each handle claims. A repair runs because
     * something did not decode, and a file that will not decode is exactly the file whose recorded
     * kind cannot be trusted. Missing one here would leave `DamagedIndexPolicy.REBUILD` reopening the
     * same damaged file forever.
     */
    private fun deleteSidecarsOf(segmentNumber: Long, handles: List<IndexHandle>) {
        BaseSidecarFile.delete(directory, segmentNumber)
        for (handle in handles) {
            PostingFileIo.delete(directory, segmentNumber, handle.id)
            ColumnFileIo.delete(directory, segmentNumber, handle.id)
        }
    }

    private fun listSidecarNames(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        val names = ArrayList<String>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.contains(BASE_SUFFIX) || name.contains(POSTING_SUFFIX) || name.contains(COLUMN_SUFFIX)) {
                    names.add(name)
                }
            }
        }
        names.sort()
        return names
    }

    private fun checkOpen() {
        if (lock.withLock { closed }) throw IndexStateException("the index catalog for $directory is closed")
    }

    private fun checkAttached() {
        if (!lock.withLock { attached }) {
            throw IndexStateException("the index catalog for $directory has not been attached to a store")
        }
    }

    private companion object {
        /**
         * How long [stopBackgroundBuilds] waits for the worker to reach a segment boundary.
         *
         * Generous, because the thing being waited for is one segment's scan and a segment can be
         * large; bounded, because closing must not depend on how much data happened to be indexing.
         * Overrunning it is reported through [problems] rather than thrown: a build abandoned here
         * costs a rescan and nothing else, which is the whole licence derived data operates under.
         */
        const val BUILD_SHUTDOWN_TIMEOUT_SECONDS = 60L
    }
}

/**
 * The number below which "not in the live set" may be read as "gone".
 *
 * Segment numbers come from a counter that only rises, so a sidecar numbered **above** everything in
 * a live set is not a departed segment — it is one this set is too old to mention. That happens
 * whenever the set in hand predates a flush or a compaction, which a long backfill makes ordinary
 * rather than exotic. Deleting such a file would destroy an index for a segment that is alive and
 * that nothing will rewrite; keeping it costs a file until a newer set arrives. The same asymmetry
 * the compactor's tombstone rule makes: keeping too long costs space, dropping too early costs data.
 *
 * An **empty** live set says nothing about how far the counter has run, so it deletes nothing.
 */
internal fun deletionHorizon(live: Set<Long>): Long = if (live.isEmpty()) 0 else live.max()
