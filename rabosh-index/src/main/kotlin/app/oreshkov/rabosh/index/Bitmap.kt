package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental

/**
 * A mutable set of document ordinals, held as one block per 65 536 ordinals.
 *
 * This is what an index build accumulates: as a segment's documents are walked, the ordinals matching a
 * value go into one of these, and [encode] turns it into the bytes a sidecar carries. Reading one back
 * is [BitmapView], which does not build this at all.
 *
 * ```kotlin
 * val matching = Bitmap()
 * matching.add(7)
 * matching.addAll(100..199)
 * matching.cardinality           // 101
 * val bytes = matching.encode()
 * ```
 *
 * **Not thread-safe**, and deliberately: one of these belongs to one sidecar build, the same rule the
 * catalog's sketch collector follows. Several may be built at once — a compaction writes more than one
 * segment — but each on its own thread.
 *
 * **Equality is by ordinals, not by representation.** A bitmap built by adding values one at a time and
 * one built by a union of two others are equal when they hold the same ordinals, and either is equal to
 * a [BitmapView] over its own [encode]. That is the point: the encoding of a set of ordinals is unique,
 * so a comparison of *bytes* is a comparison of *contents*, and the tests can assert the strong form.
 *
 * `hashCode` walks the ordinals, so it is `O(cardinality)`. Sound rather than fast, which is the right
 * way round for a structure nobody puts in a hash map by design.
 */
@RaboshExperimental
public class Bitmap private constructor(
    private var keys: IntArray,
    private var blocks: Array<Container?>,
    private var size: Int,
    private var total: Int,
) : ReadableBitmap {

    /** An empty bitmap. */
    public constructor() : this(IntArray(INITIAL_CAPACITY), arrayOfNulls(INITIAL_CAPACITY), 0, 0)

    /**
     * Exclusive prefix cardinalities, built when first asked for and dropped by any change.
     *
     * [BitmapView] reads these out of the directory it was written with; a bitmap being built has to
     * derive them, and deriving them per `rank` would make a binary search over blocks quadratic.
     */
    private var prefixSums: IntArray? = null

    override val cardinality: Int get() = total

    internal val source: ContainerSource = object : ContainerSource {
        override val containerCount: Int get() = size
        override fun keyAt(index: Int): Int = keys[index]
        override fun containerAt(index: Int): ReadableContainer = checkNotNull(blocks[index])
        override fun cardinalityBefore(index: Int): Int = prefixSum(index)
    }

    /** Adds [ordinal]. `true` if it was not already present. */
    public fun add(ordinal: Int): Boolean {
        requireOrdinal(ordinal)
        val key = BitmapFormat.high(ordinal)
        val at = source.indexOfKey(key)
        if (at < 0) {
            insert(-at - 1, key, ArrayContainer.of(BitmapFormat.low(ordinal)))
            total++
            return true
        }
        return mutate(at) { it.add(BitmapFormat.low(ordinal)) } != 0
    }

    /** Removes [ordinal]. `true` if it was present. */
    public fun remove(ordinal: Int): Boolean {
        if (ordinal < 0) return false
        val at = source.indexOfKey(BitmapFormat.high(ordinal))
        if (at < 0) return false
        return mutate(at) { it.remove(BitmapFormat.low(ordinal)) } != 0
    }

    /**
     * Adds every ordinal in [ordinals].
     *
     * A block the range covers but the bitmap does not yet have is created as a **single run**, which is
     * why `Bitmap.ofRange(0..10_000_000)` costs 153 blocks of four bytes each rather than 153 bitsets.
     * A block that already exists takes the range into whatever encoding it is in.
     */
    public fun addAll(ordinals: IntRange) {
        if (ordinals.isEmpty()) return
        requireOrdinal(ordinals.first)
        requireOrdinal(ordinals.last)
        val firstKey = BitmapFormat.high(ordinals.first)
        val lastKey = BitmapFormat.high(ordinals.last)
        for (key in firstKey..lastKey) {
            val from = if (key == firstKey) BitmapFormat.low(ordinals.first) else 0
            val to = if (key == lastKey) BitmapFormat.low(ordinals.last) else 0xFFFF
            val at = source.indexOfKey(key)
            if (at < 0) {
                insert(-at - 1, key, RunContainer.ofRange(from, to))
                total += to - from + 1
            } else {
                mutate(at) { it.addRange(from, to) }
            }
        }
    }

    /** Removes every ordinal in [ordinals]. */
    public fun removeAll(ordinals: IntRange) {
        if (ordinals.isEmpty()) return
        require(ordinals.first >= 0) { "an ordinal is not negative, and $ordinals begins below zero" }
        // Clamped rather than rejected: removing ordinals a bitmap cannot hold is a well-defined
        // request that takes out everything up to the ceiling, whereas *adding* one is a caller error.
        val highest = minOf(ordinals.last, BitmapFormat.MAX_ORDINAL)
        val firstKey = BitmapFormat.high(ordinals.first)
        val lastKey = BitmapFormat.high(highest)
        for (key in firstKey..lastKey) {
            val at = source.indexOfKey(key)
            if (at < 0) continue
            val from = if (key == firstKey) BitmapFormat.low(ordinals.first) else 0
            val to = if (key == lastKey) BitmapFormat.low(highest) else 0xFFFF
            mutate(at) { it.removeRange(from, to) }
        }
    }

    /** Drops every ordinal. */
    public fun clear() {
        for (index in 0 until size) blocks[index] = null
        size = 0
        total = 0
        prefixSums = null
    }

    /** Replaces this bitmap's ordinals with the intersection of it and [other]. */
    public fun andWith(other: ReadableBitmap): Unit = becomes(BitmapAlgebra.and(source, other.containerSource()))

    /** Replaces this bitmap's ordinals with the union of it and [other]. */
    public fun orWith(other: ReadableBitmap): Unit = becomes(BitmapAlgebra.or(source, other.containerSource()))

    /** Removes from this bitmap every ordinal present in [other]. */
    public fun andNotWith(other: ReadableBitmap): Unit =
        becomes(BitmapAlgebra.andNot(source, other.containerSource()))

    /** Replaces this bitmap's ordinals with those present in exactly one of it and [other]. */
    public fun xorWith(other: ReadableBitmap): Unit = becomes(BitmapAlgebra.xor(source, other.containerSource()))

    /** An independent copy. */
    public fun copy(): Bitmap = BitmapAlgebra.copyOf(source)

    override fun toBitmap(): Bitmap = copy()

    override fun equals(other: Any?): Boolean = other is ReadableBitmap && sameOrdinals(this, other)

    override fun hashCode(): Int = ordinalHash(this)

    override fun toString(): String = describe(this, "Bitmap")

    // --- internals ------------------------------------------------------------------------------

    /**
     * Applies [change] to block [at], keeping the cardinality and the block list in step.
     *
     * Every mutation funnels through here, which is what keeps three things that must agree in one
     * place: the running total, the lazily built prefix sums, and the rule that a block emptied by a
     * removal leaves the bitmap. A block that stays but holds nothing would put a directory entry in
     * the encoding that a reader rejects.
     *
     * @return the change in cardinality.
     */
    private inline fun mutate(at: Int, change: (Container) -> Container): Int {
        val before = checkNotNull(blocks[at]).cardinality
        val replacement = change(checkNotNull(blocks[at]))
        val delta = replacement.cardinality - before
        if (replacement.cardinality == 0) {
            removeBlock(at)
        } else {
            blocks[at] = replacement
        }
        if (delta != 0) {
            total += delta
            prefixSums = null
        }
        return delta
    }

    private fun insert(at: Int, key: Int, block: Container) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            blocks = blocks.copyOf(size * 2)
        }
        keys.copyInto(keys, at + 1, at, size)
        blocks.copyInto(blocks, at + 1, at, size)
        keys[at] = key
        blocks[at] = block
        size++
        prefixSums = null
    }

    private fun removeBlock(at: Int) {
        keys.copyInto(keys, at, at + 1, size)
        blocks.copyInto(blocks, at, at + 1, size)
        blocks[size - 1] = null
        size--
        prefixSums = null
    }

    /** Takes [result]'s blocks over. [result] is a fresh bitmap from the algebra and is discarded. */
    private fun becomes(result: Bitmap) {
        keys = result.keys
        blocks = result.blocks
        size = result.size
        total = result.total
        prefixSums = null
    }

    private fun prefixSum(index: Int): Int {
        val sums = prefixSums ?: IntArray(size + 1).also { built ->
            var running = 0
            for (position in 0 until size) {
                built[position] = running
                running += checkNotNull(blocks[position]).cardinality
            }
            built[size] = running
            prefixSums = built
        }
        return sums[index]
    }

    public companion object {
        private const val INITIAL_CAPACITY = 4

        /** A bitmap holding exactly [ordinals]. */
        public fun of(vararg ordinals: Int): Bitmap = Bitmap().also { bitmap ->
            for (ordinal in ordinals) bitmap.add(ordinal)
        }

        /**
         * A bitmap holding every ordinal in [ordinals].
         *
         * Also how a complement is spelled. There is no `not`, because the universe a complement is
         * taken against is the segment's document count — something the caller has and a bitmap does
         * not:
         *
         * ```kotlin
         * val absent = Bitmap.ofRange(0 until documentCount).also { it.andNotWith(present) }
         * ```
         */
        public fun ofRange(ordinals: IntRange): Bitmap = Bitmap().also { it.addAll(ordinals) }

        /**
         * The union of [bitmaps], accumulated into one result.
         *
         * What an `IN (a, b, c)` predicate does with three sidecar bitmaps. Folding `or` pairwise would
         * allocate a whole bitmap per step and throw all but the last away.
         */
        public fun union(bitmaps: Iterable<ReadableBitmap>): Bitmap {
            val result = Bitmap()
            for (bitmap in bitmaps) result.orWith(bitmap)
            return result
        }

        /**
         * The intersection of [bitmaps], smallest first.
         *
         * Smallest first because an intersection can only shrink, so starting from the sparsest operand
         * makes every later step cheaper. That is the one ordering decision made here — a planner with
         * statistics in hand can do better, and phase 8 is where those live.
         */
        public fun intersection(bitmaps: Iterable<ReadableBitmap>): Bitmap {
            val ordered = bitmaps.sortedBy { it.cardinality }
            if (ordered.isEmpty()) return Bitmap()
            val result = ordered.first().toBitmap()
            for (index in 1 until ordered.size) {
                if (result.isEmpty) break
                result.andWith(ordered[index])
            }
            return result
        }

        /** Takes ownership of blocks already in ascending key order, none of them empty. */
        internal fun fromBlocks(keys: IntArray, blocks: Array<Container?>, size: Int): Bitmap {
            var total = 0
            for (index in 0 until size) total += checkNotNull(blocks[index]).cardinality
            return Bitmap(keys, blocks, size, total)
        }

        private fun requireOrdinal(ordinal: Int) {
            require(ordinal in 0..BitmapFormat.MAX_ORDINAL) {
                "an ordinal is 0..${BitmapFormat.MAX_ORDINAL}, not $ordinal"
            }
        }
    }
}
