package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment

/**
 * A serialized bitmap, read where it lies.
 *
 * Nothing is deserialized. [open] validates the structure in one pass and keeps only the mapping, the
 * container count and the cardinality; a `contains` afterwards is a binary search of `u16` reads against
 * the file, and a `rank` is that plus one search inside one block. This is the reason the bitmap format
 * is rabosh's own rather than a library's: an index sidecar is consulted far more often than it is
 * written, and a bitmap that had to be parsed into heap objects first would pay for the whole file to
 * answer a question about one value.
 *
 * ```kotlin
 * val view = BitmapView.open(segment, offset = handle.offset, length = handle.length, file = name)
 * view.contains(ordinal)
 * view.and(other)            // materialises one 8 KB block at a time, never the whole bitmap
 * ```
 *
 * **A view must not outlive the arena that mapped its segment.** It holds no reference the runtime can
 * see, so reading one after its arena has closed is a fault rather than a stale answer — the same rule
 * `Variant` over a mapped segment follows, and the reason a `Snapshot` in the storage core is
 * `AutoCloseable`.
 *
 * **What [open] checks and what it does not.** It checks everything that decides where a byte is: the
 * version, the container count, ascending keys, increasing prefix cardinalities, known container kinds,
 * and that the blocks tile the slice exactly — offsets contiguous from the end of the directory to the
 * last byte. What it does not do is walk the values. Confirming that a bitset's population count matches
 * the cardinality the directory declares would mean reading 1024 words per block on a path whose whole
 * point is to read one, and a bitmap always arrives inside a frame that has already been checksummed —
 * phase 7's sidecar does for a bitmap what `SegmentBytes.verifyBlock` does for a segment's data block.
 * [verify] is the deep pass, for tests and for anything that wants to audit a file it did not write.
 */
public class BitmapView private constructor(
    private val bytes: IndexBytes,
    private val blockCount: Int,
    override val cardinality: Int,
) : ReadableBitmap {

    /** The size of the slice this view was opened over. */
    public val byteSize: Int get() = bytes.length

    internal val source: ContainerSource = object : ContainerSource {
        override val containerCount: Int get() = blockCount

        override fun keyAt(index: Int): Int = bytes.u16(entryAt(index), "container key")

        override fun cardinalityBefore(index: Int): Int =
            if (index >= blockCount) cardinality else bytes.u32(entryAt(index) + 4, "prefix cardinality", cardinality)

        override fun containerAt(index: Int): ReadableContainer {
            val entry = entryAt(index)
            val offset = bytes.u32(entry + 8, "container offset", bytes.length)
            val blockCardinality = cardinalityBefore(index + 1) - cardinalityBefore(index)
            return when (val kind = bytes.u8(entry + 2, "container kind")) {
                BitmapFormat.KIND_ARRAY -> MappedArrayContainer(bytes, offset, blockCardinality)
                BitmapFormat.KIND_BITSET -> MappedBitsetContainer(bytes, offset, blockCardinality)
                BitmapFormat.KIND_RUN -> MappedRunContainer(
                    bytes,
                    offset,
                    blockCardinality,
                    bytes.u32(offset, "run count", MAX_RUNS),
                )
                // Unreachable through `open`, which rejects an unknown kind before a view exists. Kept
                // because `containerAt` must not have a branch that returns something invented.
                else -> throw UnsupportedBitmapFormatException(unknownKind(bytes.file, kind))
            }
        }
    }

    /**
     * Walks every value, checking what [open] deliberately did not.
     *
     * Four claims per block: the values ascend and do not repeat, there are as many of them as the
     * directory declares, runs are separated, and the encoding in use is the one the writer would have
     * chosen. The last is what keeps "equal ordinals encode to identical bytes" true of files as well as
     * of writers — a block that decodes correctly but was encoded wastefully would break the property
     * without breaking a single answer.
     *
     * @throws CorruptBitmapException on the first claim that fails.
     */
    public fun verify() {
        for (index in 0 until blockCount) {
            val entry = entryAt(index)
            val key = source.keyAt(index)
            source.containerAt(index).verify { message ->
                bytes.corrupt("block $key: $message", entry)
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ReadableBitmap && sameOrdinals(this, other)

    override fun hashCode(): Int = ordinalHash(this)

    override fun toString(): String = describe(this, "BitmapView")

    public companion object {
        /** Runs a block can hold: every run needs an absent value after it, except the last. */
        private const val MAX_RUNS = BitmapFormat.CONTAINER_VALUES / 2

        private fun entryAt(index: Int): Int = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * index

        private fun unknownKind(file: String, kind: Int): String =
            "$file holds a bitmap block of kind $kind; this build reads kinds " +
                "${BitmapFormat.KIND_ARRAY}..${BitmapFormat.KIND_RUN}"

        /**
         * Opens the bitmap occupying `[offset, offset + length)` of [segment].
         *
         * The slice must be exactly one bitmap. That is what a sidecar records — a bitmap's bytes are
         * `encodedByteSize()` long and its handle says so — and requiring it lets the structural pass
         * confirm the blocks tile the slice, which catches truncation, a gap, an overlap and trailing
         * bytes as one check rather than four.
         *
         * @param file name of the file, carried into every failure this view can raise.
         * @throws CorruptBitmapException if the structure does not hold together.
         * @throws UnsupportedBitmapFormatException if the version or a container kind is not known here.
         */
        public fun open(segment: MemorySegment, offset: Long, length: Int, file: String): BitmapView {
            val bytes = IndexBytes(segment, offset, length, file)
            if (length < BitmapFormat.HEADER_BYTES) {
                bytes.corrupt("a bitmap is at least ${BitmapFormat.HEADER_BYTES} bytes, not $length", 0)
            }
            val version = bytes.u8(0, "bitmap version")
            if (version != BitmapFormat.VERSION) {
                throw UnsupportedBitmapFormatException(
                    "$file holds a bitmap at format version $version; " +
                        "this build reads version ${BitmapFormat.VERSION}",
                )
            }
            val blockCount = bytes.u16(2, "container count")
            if (blockCount > BitmapFormat.MAX_CONTAINERS) {
                bytes.corrupt("a bitmap holds at most ${BitmapFormat.MAX_CONTAINERS} blocks, not $blockCount", 2)
            }
            val cardinality = bytes.u32(4, "cardinality", Int.MAX_VALUE)
            val directoryEnd = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * blockCount
            if (directoryEnd > length) {
                bytes.corrupt("$blockCount block(s) need a $directoryEnd-byte directory in $length bytes", 2)
            }
            if (blockCount == 0 && cardinality != 0) {
                bytes.corrupt("a bitmap with no blocks claims $cardinality ordinal(s)", 4)
            }

            var previousKey = -1
            var expectedOffset = directoryEnd
            for (index in 0 until blockCount) {
                val entry = entryAt(index)
                val key = bytes.u16(entry, "container key")
                if (key <= previousKey) {
                    bytes.corrupt("container keys do not ascend: $key follows $previousKey", entry)
                }
                previousKey = key

                val kind = bytes.u8(entry + 2, "container kind")
                if (BitmapFormat.kindName(kind) == null) {
                    throw UnsupportedBitmapFormatException(unknownKind(file, kind))
                }

                // Only the first prefix is checked directly. There is no separate monotonicity check
                // because there cannot usefully be one: a block's cardinality *is* the difference
                // between two prefixes, so a prefix that fails to advance shows up below as a block
                // holding no ordinals, and one that goes backwards as a block holding fewer than none.
                // That is the pay-off of storing prefixes rather than per-block counts — the two
                // quantities cannot disagree, because there is only one of them.
                val prefix = bytes.u32(entry + 4, "prefix cardinality", cardinality)
                if (index == 0 && prefix != 0) {
                    bytes.corrupt("the first block's prefix cardinality is $prefix rather than 0", entry + 4)
                }

                val nextPrefix = if (index + 1 == blockCount) {
                    cardinality
                } else {
                    bytes.u32(entry + BitmapFormat.ENTRY_BYTES + 4, "prefix cardinality", cardinality)
                }
                val blockCardinality = nextPrefix - prefix
                if (blockCardinality < 1 || blockCardinality > BitmapFormat.CONTAINER_VALUES) {
                    bytes.corrupt("block $key holds $blockCardinality ordinal(s)", entry + 4)
                }

                val blockOffset = bytes.u32(entry + 8, "container offset", length)
                if (blockOffset != expectedOffset) {
                    bytes.corrupt("block $key begins at $blockOffset rather than $expectedOffset", entry + 8)
                }
                val extent = extentOf(bytes, kind, key, blockOffset, blockCardinality)
                if (blockOffset.toLong() + extent > length) {
                    bytes.corrupt("block $key needs $extent byte(s) at $blockOffset of $length", entry + 8)
                }
                expectedOffset = blockOffset + extent
            }
            if (expectedOffset != length) {
                bytes.corrupt("the blocks end at $expectedOffset in a $length-byte bitmap", 2)
            }

            return BitmapView(bytes, blockCount, cardinality)
        }

        /** Opens a bitmap held on the heap — what [ReadableBitmap.encode] produces. */
        public fun open(encoded: ByteArray, file: String = "<memory>"): BitmapView =
            open(MemorySegment.ofArray(encoded), 0, encoded.size, file)

        /** How many bytes a block occupies, which for a run list depends on a field inside it. */
        private fun extentOf(bytes: IndexBytes, kind: Int, key: Int, offset: Int, cardinality: Int): Int =
            when (kind) {
                BitmapFormat.KIND_ARRAY -> {
                    if (cardinality > BitmapFormat.ARRAY_MAX_CARDINALITY) {
                        bytes.corrupt(
                            "block $key claims $cardinality ordinal(s) in an array encoding, " +
                                "which holds at most ${BitmapFormat.ARRAY_MAX_CARDINALITY}",
                            offset,
                        )
                    }
                    BitmapFormat.arrayBytes(cardinality)
                }

                BitmapFormat.KIND_BITSET -> BitmapFormat.BITSET_BYTES

                else -> {
                    val runCount = bytes.u32(offset, "run count", MAX_RUNS)
                    if (runCount < 1) bytes.corrupt("block $key holds no runs", offset)
                    BitmapFormat.runBytes(runCount)
                }
            }
    }
}
