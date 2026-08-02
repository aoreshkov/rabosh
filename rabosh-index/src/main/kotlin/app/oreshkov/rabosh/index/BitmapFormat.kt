package app.oreshkov.rabosh.index

/**
 * The on-disk layout of a serialized bitmap.
 *
 * ```
 * bitmap    := header entry[containerCount] container*
 *
 * header    := version:u8 reserved:u8 containerCount:u16 cardinality:u32      (8 bytes)
 * entry     := key:u16 kind:u8 reserved:u8 cardinalityBefore:u32 offset:u32   (12 bytes)
 *
 * container := ARRAY  low:u16[cardinality]                                    kind 1
 *            | BITSET word:u64[1024]                                          kind 2
 *            | RUN    runCount:u32 (start:u16 lengthMinusOne:u16)[runCount]   kind 3
 * ```
 *
 * Little-endian throughout, matching the log, the manifest, the segment, the sketch sidecar and the
 * Variant encoding, so the whole engine has one byte order. `offset` is relative to the **start of
 * the bitmap block**, never to the start of the file, so a block is position-independent and can be
 * embedded at any offset in an index sidecar. **These constants are permanent**: add, never renumber.
 *
 * The shape is the one Roaring settled on — split a 32-bit value into a 16-bit key and a 16-bit
 * remainder, and store each 65 536-value block in whichever of three encodings is smallest. The
 * library was offered and declined, and owning the bytes is what that buys: five choices below are
 * things a library's format does not do, and each of them matters to a read path that runs off a
 * mapped file.
 *
 * **`cardinalityBefore` is an exclusive prefix sum, not a per-container count.** A container's own
 * cardinality is the next entry's prefix minus its own, with the header's total closing the last one,
 * so cardinality is stored in exactly one place and cannot disagree with itself. More importantly it
 * makes [ReadableBitmap.rank] and [ReadableBitmap.select] **O(log containerCount)**: binary search
 * the directory by key or by prefix, then search once inside one container. Stock Roaring walks
 * containers for both. Four bytes per block is what that costs.
 *
 * **The directory is fixed-width and always present.** Entry *i* begins at
 * `HEADER_BYTES + ENTRY_BYTES * i`, so finding a container is arithmetic rather than a walk. The
 * Roaring portable format makes its offset array conditional, which would put a branch on the hottest
 * read in the query layer.
 *
 * **[kind] is one byte and it is the extension point**, exactly as `blockType` is in the segment
 * format. A denser encoding — a bit-packed delta array, a compressed bitset — arrives as kind 4, not
 * as a new bitmap version, so a reader that meets one says "written by a newer build" rather than
 * "damaged".
 *
 * **Neither ARRAY nor BITSET carries a length.** An array container's element count *is* its
 * cardinality and a bitset is always [BITSET_WORDS] words, both already known from the directory.
 * Only RUN needs a count of its own, because its cardinality is not its run count.
 *
 * **[key] is a `u16` although the ordinal domain is a non-negative `Int`.** Keys only reach 32767
 * today, so the field is already wide enough for an unsigned 32-bit domain and widening later would
 * not be a format change.
 */
internal object BitmapFormat {
    /** The only bitmap format version this build writes, and the only one it reads. */
    const val VERSION: Int = 1

    const val HEADER_BYTES: Int = 8
    const val ENTRY_BYTES: Int = 12

    /** Values one container covers: the whole 16-bit remainder space. */
    const val CONTAINER_VALUES: Int = 1 shl 16

    const val KIND_ARRAY: Int = 1
    const val KIND_BITSET: Int = 2
    const val KIND_RUN: Int = 3

    /**
     * Largest cardinality an array container may hold.
     *
     * At 4096 values an array costs 8192 bytes, which is exactly a bitset, and the tie is broken
     * towards the array by [smallestKind] — the same threshold Roaring uses, for the same arithmetic
     * reason rather than by imitation.
     */
    const val ARRAY_MAX_CARDINALITY: Int = 4096

    const val BITSET_WORDS: Int = 1024
    const val BITSET_BYTES: Int = BITSET_WORDS * 8

    /**
     * Largest ordinal a bitmap may hold: one short of `Int.MAX_VALUE`.
     *
     * The one ordinal given up is what keeps [ReadableBitmap.cardinality] an `Int` — a bitmap holding
     * every value in `0..Int.MAX_VALUE` would have a cardinality of 2^31, one past the type, and
     * `Bitmap.ofRange` makes that reachable in a single cheap run container rather than only in
     * theory. The alternative is a `Long` cardinality and a cast at every call site, bought for one
     * ordinal that a segment two billion documents deep would be the first to need.
     */
    const val MAX_ORDINAL: Int = Int.MAX_VALUE - 1

    /** Containers a bitmap over `0..MAX_ORDINAL` can have, and so the ceiling on `containerCount`. */
    const val MAX_CONTAINERS: Int = (MAX_ORDINAL ushr 16) + 1

    /** The 16-bit key half of an ordinal: which container holds it. */
    fun high(value: Int): Int = value ushr 16

    /** The 16-bit remainder half of an ordinal: where in its container it sits. */
    fun low(value: Int): Int = value and 0xFFFF

    /** The ordinal a container [key] and a remainder [low] name together. */
    fun valueOf(key: Int, low: Int): Int = (key shl 16) or low

    fun arrayBytes(cardinality: Int): Int = cardinality * 2

    fun runBytes(runCount: Int): Int = 4 + runCount * 4

    /**
     * The encoding a container of this shape must use, and therefore the only one a reader accepts.
     *
     * One function, called by the writer that chooses an encoding *and* by the reader that checks the
     * choice — the same arrangement `BloomFilter.bitIndex` has, for the same reason: these are two
     * halves of one decision, and a difference between them would show up as a file the engine wrote
     * and cannot read. Ties go to the lower kind id, so the answer is total and the encoding of a
     * value set is unique. That uniqueness is what makes "equal value sets encode to identical bytes"
     * an assertion rather than a hope.
     */
    fun smallestKind(cardinality: Int, runCount: Int): Int {
        require(cardinality in 1..CONTAINER_VALUES) {
            "a container holds 1..$CONTAINER_VALUES values, not $cardinality"
        }
        // Candidates are considered in ascending kind order and each has to be *strictly* smaller
        // than the incumbent, which is what makes the tie-break "lower id wins" without stating it
        // twice. At 4096 values an array and a bitset are both 8192 bytes and the array keeps it.
        var kind = KIND_ARRAY
        var bytes = if (cardinality <= ARRAY_MAX_CARDINALITY) arrayBytes(cardinality) else Int.MAX_VALUE
        if (BITSET_BYTES < bytes) {
            kind = KIND_BITSET
            bytes = BITSET_BYTES
        }
        if (runBytes(runCount) < bytes) return KIND_RUN
        return kind
    }

    /** What a block of this shape occupies once encoded canonically, excluding its directory entry. */
    fun containerBytes(cardinality: Int, runCount: Int): Int = when (smallestKind(cardinality, runCount)) {
        KIND_ARRAY -> arrayBytes(cardinality)
        KIND_BITSET -> BITSET_BYTES
        else -> runBytes(runCount)
    }

    /** The name of a kind, for a failure message. `null` is what an unknown id reads as. */
    fun kindName(kind: Int): String? = when (kind) {
        KIND_ARRAY -> "array"
        KIND_BITSET -> "bitset"
        KIND_RUN -> "run"
        else -> null
    }
}
