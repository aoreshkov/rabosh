package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.IndexKind
import java.lang.foreign.Arena
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * One segment's index sidecars, mapped and reference-counted as a unit.
 *
 * The base sidecar and every posting file over the same segment share **one [Arena]**, so they are
 * unmapped together and there is one lifetime to reason about rather than one per file. That matters
 * more than it looks: on Windows a mapped file cannot be deleted at all, so reclaiming a sidecar
 * requires the mapping to be gone first, and a per-file arena would multiply the number of places
 * that could be got wrong by the number of indexes.
 *
 * The reference discipline is `SegmentTable`'s, deliberately: an [AtomicInteger] starting at one for
 * the owner, [acquire] as a compare-and-set loop that **refuses to resurrect from zero**, and
 * [release] closing the arena before deleting anything. A plain increment would let a reader revive a
 * closed arena and fault on the next read instead of failing to acquire.
 *
 * **Deletion is deferred to the last release and happens only for files that were retired.** A
 * sidecar is retired when its segment leaves the tree, or when the index it belongs to is dropped.
 * Hanging deletion off "the last reference went" alone would make closing a catalog delete the
 * sidecars of a store that is merely shutting down — the same distinction `VersionSet.departed`
 * draws for segments, for the same reason.
 */
internal class SegmentIndex private constructor(
    val segmentNumber: Long,
    private val arena: Arena,
    val base: BaseSidecar,
    private val postings: Map<Int, PostingFile>,
    private val columns: Map<Int, ColumnFile>,
    private val paths: List<Path>,
) {
    private val references = AtomicInteger(1)

    /** Files to delete once nothing is reading them. Set by the owner before it drops its reference. */
    @Volatile
    private var retired: List<Path> = emptyList()

    /**
     * Indexes this segment is covered by, of **either** kind.
     *
     * The union matters: `IndexCatalog.beginSegment` decides what is missing from this set, so a
     * column left out would make every attach re-observe and rewrite every column it already had.
     */
    val indexIds: Set<Int> get() = postings.keys + columns.keys

    /** The largest sequence any entry in this segment carries. The soundness guard. */
    val largestSequence: Long get() = base.largestSequence

    /** Ordinals in this segment, tombstones included. */
    val documentCount: Int get() = base.documentCount

    fun postings(indexId: Int): PostingFile? = postings[indexId]

    fun column(indexId: Int): ColumnFile? = columns[indexId]

    /**
     * Takes a reference, or returns `false` if the last one has already gone.
     *
     * The compare-and-set loop is the point: a reader that observed this object in a map a moment
     * before it was closed must fail to acquire rather than bring a closed arena back.
     */
    fun acquire(): Boolean {
        while (true) {
            val current = references.get()
            if (current <= 0) return false
            if (references.compareAndSet(current, current + 1)) return true
        }
    }

    /** Marks this segment's files for deletion once the last reference goes. */
    fun retire(files: List<Path>) {
        retired = files
    }

    /**
     * Whether anything is still reading this.
     *
     * Asked by the catalog about a segment it has already retired: a retired segment owns its own
     * files until its last reader lets go, so anything else that deletes by name has to leave it
     * alone until this is `false`.
     */
    val isAlive: Boolean get() = references.get() > 0

    /** Everything this holds: the base sidecar and every posting file and column over it. */
    fun files(): List<Path> = paths

    fun release() {
        if (references.decrementAndGet() > 0) return
        // Unmap before deleting. On Windows the delete simply fails while a mapping is live, so this
        // order is what makes reclamation possible at all rather than a tidiness preference.
        arena.close()
        for (file in retired) runCatching { Files.deleteIfExists(file) }
    }

    override fun toString(): String =
        "SegmentIndex(#$segmentNumber, ${postings.size} inverted, ${columns.size} column(s), " +
            "${base.documentCount} ordinal(s))"

    companion object {
        /**
         * Maps the base sidecar for [segmentNumber] and the posting files of [indexes] over it.
         *
         * Returns `null` when there is no base sidecar, which is how "this segment is not covered"
         * arrives — never as a covered-and-empty index. A *posting* file that is missing is not a
         * failure either: it means that one index does not cover this segment yet, which is exactly
         * the state a build in progress is in.
         *
         * @throws IndexException if a file that exists will not decode.
         */
        fun open(directory: Path, segmentNumber: Long, indexes: List<IndexHandle>): SegmentIndex? {
            val arena = Arena.ofShared()
            var ok = false
            try {
                val basePath = directory.resolve(baseFileName(segmentNumber))
                val baseMapping = map(arena, basePath) ?: return null
                val base = BaseSidecar.open(
                    baseMapping.first,
                    baseMapping.second,
                    basePath.fileName.toString(),
                    segmentNumber,
                )

                val files = ArrayList<Path>()
                files.add(basePath)
                val postings = HashMap<Int, PostingFile>()
                val columns = HashMap<Int, ColumnFile>()
                for (handle in indexes) {
                    // The kind decides which sidecar to look for. An absent one is not a failure: it
                    // means that index does not cover this segment yet, which is the state a build in
                    // progress is in.
                    when (handle.kind) {
                        // Two kinds, one sidecar: a composite index's terms are tuples and its file is
                        // an ordinary posting file, so there is nothing here to tell them apart by.
                        IndexKind.INVERTED, IndexKind.COMPOSITE_TERM -> {
                            val path = directory.resolve(postingFileName(segmentNumber, handle.id))
                            val mapping = map(arena, path) ?: continue
                            postings[handle.id] = PostingFile.open(
                                mapping.first,
                                mapping.second,
                                path.fileName.toString(),
                                segmentNumber,
                                handle.id,
                                handle.path.toString(),
                                base.largestSequence,
                            )
                            files.add(path)
                        }

                        IndexKind.SHREDDED_COLUMN -> {
                            val path = directory.resolve(columnFileName(segmentNumber, handle.id))
                            val mapping = map(arena, path) ?: continue
                            columns[handle.id] = ColumnFile.open(
                                mapping.first,
                                mapping.second,
                                path.fileName.toString(),
                                segmentNumber,
                                handle.id,
                                handle.path.toString(),
                                base.largestSequence,
                            )
                            files.add(path)
                        }
                    }
                }
                ok = true
                return SegmentIndex(segmentNumber, arena, base, postings, columns, files)
            } finally {
                if (!ok) arena.close()
            }
        }

        /** Maps [path] whole, or `null` if it is not there. An empty file is not a sidecar. */
        private fun map(arena: Arena, path: Path): Pair<java.lang.foreign.MemorySegment, Int>? {
            val channel = try {
                FileChannel.open(path, StandardOpenOption.READ)
            } catch (missing: NoSuchFileException) {
                return null
            }
            // The mapping outlives the channel, so the descriptor is closed straight away: one open
            // descriptor per sidecar per segment would be a limit long before memory was.
            channel.use {
                val size = it.size()
                if (size <= 0 || size > Int.MAX_VALUE) {
                    throw CorruptIndexException(
                        "an index sidecar of $size byte(s) cannot be read",
                        path.fileName.toString(),
                    )
                }
                return it.map(FileChannel.MapMode.READ_ONLY, 0, size, arena) to size.toInt()
            }
        }
    }
}
