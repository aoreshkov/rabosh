package app.oreshkov.rabosh.index

/**
 * A set of document ordinals that can be read, whether it is being built on the heap or read straight
 * out of a mapped file.
 *
 * An **ordinal** is the position of a document within one segment, counting from zero — which is what
 * an index sidecar stores against a value, and the reason a bitmap here is per-segment rather than
 * global. Ordinals run `0..`[BitmapFormat.MAX_ORDINAL].
 *
 * Every operation on this interface is implemented once, here, over the two implementations' shared
 * view of their blocks. That is not tidiness: [Bitmap] and [BitmapView] answer the same questions about
 * the same values, and a `rank` that differed between them would mean a query returning different
 * documents depending on whether the sidecar it read had been flushed yet. The interface is `sealed`
 * so that stays true — an implementation from outside the module could not be given to the algebra.
 *
 * Sizes and ranks are `Int`. See [BitmapFormat.MAX_ORDINAL] for the one ordinal that costs.
 */
public sealed interface ReadableBitmap {

    /** How many ordinals are present. */
    public val cardinality: Int

    public val isEmpty: Boolean get() = cardinality == 0

    /** Whether [ordinal] is present. Any `Int` is a fair question; a negative one is simply absent. */
    public fun contains(ordinal: Int): Boolean {
        if (ordinal < 0) return false
        val source = containerSource()
        val at = source.indexOfKey(BitmapFormat.high(ordinal))
        return at >= 0 && source.containerAt(at).contains(BitmapFormat.low(ordinal))
    }

    /** The smallest ordinal present. */
    public fun first(): Int {
        val source = containerSource()
        if (source.containerCount == 0) throw NoSuchElementException("the bitmap is empty")
        return BitmapFormat.valueOf(source.keyAt(0), source.containerAt(0).first)
    }

    /** The largest ordinal present. */
    public fun last(): Int {
        val source = containerSource()
        val at = source.containerCount - 1
        if (at < 0) throw NoSuchElementException("the bitmap is empty")
        return BitmapFormat.valueOf(source.keyAt(at), source.containerAt(at).last)
    }

    /**
     * How many present ordinals are less than or equal to [ordinal].
     *
     * One binary search of the container directory and one search inside a single block — the exclusive
     * prefix cardinalities in [BitmapFormat] are what make this `O(log n)` rather than a walk over every
     * block below the answer.
     */
    public fun rank(ordinal: Int): Int {
        require(ordinal >= 0) { "rank is asked of an ordinal, not $ordinal" }
        val source = containerSource()
        val at = source.indexOfKey(BitmapFormat.high(ordinal))
        if (at < 0) return source.cardinalityBefore(-at - 1)
        return source.cardinalityBefore(at) + source.containerAt(at).rank(BitmapFormat.low(ordinal))
    }

    /**
     * The [index]-th smallest ordinal present, counting from zero.
     *
     * @throws IndexOutOfBoundsException if [index] is not below [cardinality]. Deliberately not
     *   clamped: "the last ordinal" and "there is no such ordinal" are different answers, and a caller
     *   that conflated them would silently process a document that is not in the result.
     */
    public fun select(index: Int): Int {
        if (index < 0 || index >= cardinality) {
            throw IndexOutOfBoundsException("select($index) in a bitmap of $cardinality ordinal(s)")
        }
        val source = containerSource()
        val at = source.containerIndexForRank(index)
        val within = source.containerAt(at).select(index - source.cardinalityBefore(at))
        return BitmapFormat.valueOf(source.keyAt(at), within)
    }

    /** A walk over every present ordinal, ascending. */
    public fun cursor(): BitmapCursor = BitmapCursor(containerSource())

    /** The ordinals present in both. */
    public fun and(other: ReadableBitmap): Bitmap =
        BitmapAlgebra.and(containerSource(), other.containerSource())

    /** The ordinals present in either. */
    public fun or(other: ReadableBitmap): Bitmap =
        BitmapAlgebra.or(containerSource(), other.containerSource())

    /** The ordinals present here and not in [other]. */
    public fun andNot(other: ReadableBitmap): Bitmap =
        BitmapAlgebra.andNot(containerSource(), other.containerSource())

    /** The ordinals present in exactly one of the two. */
    public fun xor(other: ReadableBitmap): Bitmap =
        BitmapAlgebra.xor(containerSource(), other.containerSource())

    /**
     * Whether the two share an ordinal, without building the intersection.
     *
     * One of the two questions a planner asks of a sidecar it may then decide not to read, so it
     * allocates nothing: two dense blocks are compared word by word and anything else walks the sparser
     * side probing the denser.
     */
    public fun intersects(other: ReadableBitmap): Boolean =
        BitmapAlgebra.intersects(containerSource(), other.containerSource())

    /**
     * How many ordinals the two share, without building the intersection.
     *
     * The planner's other question. Whether an index is selective enough to be worth using is a
     * cardinality, and materialising the intersection would allocate exactly what the answer might
     * reject.
     */
    public fun andCardinality(other: ReadableBitmap): Int =
        BitmapAlgebra.andCardinality(containerSource(), other.containerSource())

    /** An independent, mutable bitmap holding the same ordinals. */
    public fun toBitmap(): Bitmap = BitmapAlgebra.copyOf(containerSource())

    /** Every present ordinal, ascending. For tests and for small results; a [cursor] does not allocate. */
    public fun toIntArray(): IntArray {
        val values = IntArray(cardinality)
        var written = 0
        val cursor = cursor()
        while (cursor.next()) values[written++] = cursor.value
        return values
    }

    /**
     * The size [encode] will produce, without producing it.
     *
     * Exact rather than an estimate, because a sidecar writer has to lay bitmaps out before it writes
     * them.
     */
    public fun encodedByteSize(): Int {
        val source = containerSource()
        var bytes = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * source.containerCount
        for (index in 0 until source.containerCount) {
            val container = source.containerAt(index)
            bytes += BitmapFormat.containerBytes(container.cardinality, container.runCount)
        }
        return bytes
    }

    /**
     * This bitmap in the layout [BitmapFormat] describes, canonically encoded.
     *
     * Each block is put into its smallest encoding on the way out, which is what makes the encoding of
     * a set of ordinals *unique*: two bitmaps holding the same ordinals produce identical bytes however
     * differently they were built. Nothing here is mutated — the normalised blocks are written and
     * discarded, so a bitmap that is still being added to does not churn its encodings.
     */
    public fun encode(): ByteArray {
        val source = containerSource()
        val count = source.containerCount
        val out = IndexWriter(encodedByteSize())
        out.writeByte(BitmapFormat.VERSION)
        out.writeByte(0)
        out.writeU16(count)
        out.writeU32(cardinality)

        val blocks = arrayOfNulls<Container>(count)
        val offsetFields = IntArray(count)
        var before = 0
        for (index in 0 until count) {
            val block = source.containerAt(index).materialise().normalise()
            blocks[index] = block
            out.writeU16(source.keyAt(index))
            out.writeByte(block.kind)
            out.writeByte(0)
            out.writeU32(before)
            // The offset cannot be known until every directory entry is written, which is what
            // `patchU32` exists for.
            offsetFields[index] = out.size
            out.writeU32(0)
            before += block.cardinality
        }
        check(before == cardinality) {
            "the blocks hold $before ordinal(s) but the bitmap reports $cardinality"
        }
        for (index in 0 until count) {
            out.patchU32(offsetFields[index], out.size)
            checkNotNull(blocks[index]).writeTo(out)
        }
        return out.toByteArray()
    }
}

/**
 * The blocks of a bitmap, keyed and in ascending key order.
 *
 * The seam between "what a bitmap is" and "where its bytes are". [Bitmap] answers from heap arrays and
 * [BitmapView] answers by reading a mapped directory, and everything in [ReadableBitmap] and
 * [BitmapAlgebra] is written against this rather than against either of them.
 */
internal interface ContainerSource {
    val containerCount: Int

    /** The 16-bit key of block [index]. Strictly ascending in [index]. */
    fun keyAt(index: Int): Int

    fun containerAt(index: Int): ReadableContainer

    /**
     * How many ordinals the blocks before [index] hold; the bitmap's cardinality when
     * [index] is [containerCount].
     */
    fun cardinalityBefore(index: Int): Int

    /** The index of the block keyed [key], or `-(insertionPoint) - 1`. */
    fun indexOfKey(key: Int): Int {
        var lower = 0
        var upper = containerCount - 1
        while (lower <= upper) {
            val middle = (lower + upper) ushr 1
            val candidate = keyAt(middle)
            if (candidate < key) {
                lower = middle + 1
            } else if (candidate > key) {
                upper = middle - 1
            } else {
                return middle
            }
        }
        return -(lower + 1)
    }

    /** The block holding the [rank]-th ordinal, by binary search over the prefix cardinalities. */
    fun containerIndexForRank(rank: Int): Int {
        var lower = 0
        var upper = containerCount - 1
        while (lower <= upper) {
            val middle = (lower + upper) ushr 1
            if (cardinalityBefore(middle) > rank) upper = middle - 1 else lower = middle + 1
        }
        return upper
    }
}

/** The blocks behind a bitmap. Exhaustive, which is what [ReadableBitmap] being `sealed` buys. */
internal fun ReadableBitmap.containerSource(): ContainerSource = when (this) {
    is Bitmap -> source
    is BitmapView -> source
}

/**
 * Whether two bitmaps hold the same ordinals.
 *
 * Equality is by value set and not by representation, which is why it is here rather than compared
 * field by field: a [Bitmap] and the [BitmapView] of its own encoding hold the same ordinals and must
 * be equal, and their internals have nothing in common.
 */
internal fun sameOrdinals(left: ReadableBitmap, right: ReadableBitmap): Boolean {
    if (left === right) return true
    if (left.cardinality != right.cardinality) return false
    val ours = left.cursor()
    val theirs = right.cursor()
    while (ours.next()) {
        if (!theirs.next() || ours.value != theirs.value) return false
    }
    return !theirs.next()
}

/** A hash over the ordinals, so that [sameOrdinals] and `hashCode` agree across implementations. */
internal fun ordinalHash(bitmap: ReadableBitmap): Int {
    var hash = bitmap.cardinality
    val cursor = bitmap.cursor()
    while (cursor.next()) hash = hash * 31 + cursor.value
    return hash
}

internal fun describe(bitmap: ReadableBitmap, name: String): String {
    if (bitmap.isEmpty) return "$name(empty)"
    return "$name(${bitmap.cardinality} ordinal(s), ${bitmap.first()}..${bitmap.last()})"
}
