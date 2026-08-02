package app.oreshkov.rabosh.core

import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The in-memory half of the tree, as one reader sees it.
 *
 * A sealed memtable is kept together with the number of the log its writes began in, because that
 * is what a flush needs to know before it can delete anything: the segment it writes accounts for
 * every commit in that log and every log below it, and not one commit more.
 */
internal class SealedMemtable(val memtable: Memtable, val logNumber: Long)

/** The memtables a reader must consult, and the order it must consult them in. */
internal class StoreState(
    val active: Memtable,
    val activeLogNumber: Long,
    /** Oldest sealed first, so a flush takes the head and a read walks it backwards. */
    val sealed: List<SealedMemtable>,
)

/**
 * A fixed view of the store, taken at a sequence number.
 *
 * **It pins three things, and each for its own reason.** The sequence number is the filter: nothing
 * committed after it is visible. The [Version] keeps the segments the view needs from being deleted
 * by a compaction that has already replaced them. The [StoreState] keeps the memtables reachable,
 * which matters precisely when a flush has moved their contents into a segment the *newer* version
 * knows about and this one does not.
 *
 * A snapshot also holds back compaction: an older version of a key may not be dropped while a
 * snapshot that could still ask for it exists. Holding one open for a long time therefore costs
 * disk space, which is the honest trade and the reason this is [AutoCloseable] rather than
 * something the collector tidies up.
 *
 * ```kotlin
 * store.snapshot().use { snapshot ->
 *     store.get(key, snapshot)          // unchanged by anything written from here on
 * }
 * ```
 */
public class Snapshot internal constructor(
    /** The sequence number this view is taken at. Nothing newer is visible through it. */
    public val sequence: Long,
    internal val version: Version,
    internal val state: StoreState,
    private val registry: SnapshotRegistry,
) : AutoCloseable {

    @Volatile
    internal var open: Boolean = true
        private set

    /**
     * The segments the version this view pinned is made of.
     *
     * **The universe a reader at this snapshot may reason about, and it is not the store's live
     * set.** A compaction installs a new version and retires the old one, but this snapshot still
     * holds — and still reads through — the segments it pinned. A caller that asked
     * [DocumentStore.liveSegmentNumbers] instead and then scanned those numbers would scan files this
     * view cannot see and skip files it can, which for a query means documents silently missing from
     * an answer.
     *
     * So a layer that partitions the work of a read — an index answering for some segments and
     * [DocumentStore.scanSegments] reading the rest — must take the partition over *this* set.
     */
    public val segmentNumbers: Set<Long>
        get() {
            checkOpen()
            return version.segments().mapTo(HashSet()) { it.number }
        }

    /**
     * Whether this view may see documents that are not in a segment.
     *
     * Conservative in one direction only: the memtables are the objects this snapshot pinned and they
     * may have grown since, so this can report `true` for a view whose visible entries are all in
     * segments. It never reports `false` for a view that can see an unflushed document, which is the
     * direction that would cost a reader a result.
     */
    public val hasUnflushedDocuments: Boolean
        get() {
            checkOpen()
            return !state.active.isEmpty() || state.sealed.any { !it.memtable.isEmpty() }
        }

    /** Releases the segments and the compaction headroom this view was holding. Idempotent. */
    override fun close() {
        if (!open) return
        open = false
        registry.release(this)
        version.release()
    }

    internal fun checkOpen() {
        if (!open) throw StoreClosedException("snapshot at sequence $sequence is closed")
    }

    override fun toString(): String = "Snapshot(sequence=$sequence, open=$open)"
}

/**
 * The live snapshots, kept only so that compaction can ask for the oldest one.
 *
 * That single number — [oldestSequence] — is what separates a version of a key that may be dropped
 * from one that must be kept. A multiset rather than a set: two readers may take a snapshot at the
 * same sequence, and the first of them to close must not free the second's view.
 */
internal class SnapshotRegistry {
    private val lock = ReentrantLock()
    private val counts = TreeMap<Long, Int>()

    fun register(snapshot: Snapshot) {
        lock.withLock { counts.merge(snapshot.sequence, 1, Int::plus) }
    }

    fun release(snapshot: Snapshot) {
        lock.withLock {
            val remaining = counts.getOrDefault(snapshot.sequence, 0) - 1
            if (remaining <= 0) {
                counts.remove(snapshot.sequence)
            } else {
                counts[snapshot.sequence] = remaining
            }
        }
    }

    /**
     * The oldest sequence any live snapshot can still see, or [unpinned] when there are none.
     *
     * **The caller must pass the store's current sequence, not a sentinel.** Compaction marks the
     * newest version of a key with [Long.MAX_VALUE] to mean "nothing has been emitted for this key
     * yet", and then asks whether that marker is at or below this bound. A bound of [Long.MAX_VALUE]
     * makes the answer yes, and the newest version of every key in the compaction is dropped as if
     * it had been superseded. The parameter is here so that trap cannot be walked into: there is no
     * argument-free form.
     */
    fun oldestSequence(unpinned: Long): Long = lock.withLock {
        if (counts.isEmpty()) unpinned else counts.firstKey()
    }

    val count: Int get() = lock.withLock { counts.values.sum() }
}
