package app.oreshkov.rabosh.index

/**
 * One 65 536-value block of a bitmap: the values of a bitmap that share a 16-bit key.
 *
 * A block is stored in whichever of three encodings is smallest for its contents — a sorted array of
 * remainders, a flat bitset, or a list of runs — and [BitmapFormat.smallestKind] is the single place
 * that decides which. Splitting the value space this way is what makes a bitmap adaptive rather than
 * uniformly good or uniformly bad: a dense block pays one bit per value, a sparse block pays two
 * bytes per value, and a block of consecutive values pays four bytes per run.
 *
 * This is the read-only half, and both a heap [Container] and a container read straight out of a
 * mapped file implement it. Every method here can be answered **in place**, without materialising
 * anything, which is what lets the query layer probe a sidecar without allocating.
 *
 * A remainder is an `Int` in `0..65535` at every boundary. Inside an array or a run it is stored as a
 * `Char`, which is the JVM's unsigned 16-bit type: `Char` ordering *is* unsigned ordering, so a binary
 * search over remainders needs no masking and cannot be got wrong by forgetting one. `Short` would
 * have compared signed and put 0x8000 before 0x0001.
 */
internal interface ReadableContainer {
    /** One of [BitmapFormat.KIND_ARRAY], [BitmapFormat.KIND_BITSET], [BitmapFormat.KIND_RUN]. */
    val kind: Int

    /** How many values this block holds. Always at least one for a container a bitmap keeps. */
    val cardinality: Int

    /**
     * Runs of consecutive values, which is what decides whether a run encoding is the smallest.
     *
     * Read-only and therefore here rather than on [Container]: `BitmapView.verify` asks it of a mapped
     * block to confirm the encoding on disk really is the canonical one.
     */
    val runCount: Int

    /** The smallest remainder present. Undefined when [cardinality] is zero. */
    val first: Int

    /** The largest remainder present. Undefined when [cardinality] is zero. */
    val last: Int

    fun contains(low: Int): Boolean

    /** How many values are less than or equal to [low]. */
    fun rank(low: Int): Int

    /** The [index]-th smallest value, counting from zero. [index] must be below [cardinality]. */
    fun select(index: Int): Int

    fun cursor(): ContainerCursor

    /**
     * Checks that the contents are well-formed and canonically encoded, calling [report] if not.
     *
     * The deep half of reading a bitmap, and it is deliberately not on the read path — see [BitmapView]
     * for why. [report] never returns, so an implementation reads as a series of assertions.
     */
    fun verify(report: (String) -> Nothing): Unit = verifyContainerContents(this, report)

    /**
     * A heap container holding these values, which is `this` when there already is one.
     *
     * The boundary between reading in place and building something: the four constructive operations
     * produce a container regardless, so they work on materialised operands, and this is bounded at
     * 8 KB per block however large the bitmap is.
     */
    fun materialise(): Container
}

/**
 * A container whose remainders live on the heap, and which can therefore be built and changed.
 *
 * [add], [remove], [addRange] and [removeRange] **may mutate the receiver and return the container to
 * keep**, which is `this` when the encoding did not have to change. The receiver must not be used
 * afterwards. That is the shape every adaptive-container bitmap uses, and the alternative — a fresh
 * container per value added — would allocate once per document on an index build.
 */
internal sealed interface Container : ReadableContainer {
    fun add(low: Int): Container

    fun remove(low: Int): Container

    /** Adds every remainder in `first..last`, both inclusive. */
    fun addRange(first: Int, last: Int): Container

    /** Removes every remainder in `first..last`, both inclusive. */
    fun removeRange(first: Int, last: Int): Container

    /**
     * A container holding the same values that can be mutated without touching this one.
     *
     * A bitmap that adopts a block from another bitmap has to copy it, because [materialise] hands back
     * the receiver itself for a heap container and the two bitmaps would then share storage — a later
     * `add` to one would appear in the other. A run list may return itself, because every mutation of
     * one expands into a different container rather than changing its runs.
     */
    fun copy(): Container

    /**
     * This block in its canonical encoding, which is the only one a reader accepts.
     *
     * Called on the way out, not after every mutation: re-encoding a block on each added value would
     * turn an index build into a churn of conversions, and the shape a block *ends up* in is the only
     * one that reaches a file.
     */
    fun normalise(): Container

    /**
     * Appends this block in its current encoding. See [BitmapFormat].
     *
     * There is deliberately no matching `encodedByteSize` here: the size of a bitmap is computed from
     * [BitmapFormat.containerBytes] over a block's cardinality and run count, which is the *canonical*
     * size and therefore the size this will produce once [normalise] has run. Two functions answering
     * that question could disagree, and the one that lost would produce a writer whose buffer and whose
     * bytes are different lengths.
     */
    fun writeTo(out: IndexWriter)

    override fun materialise(): Container = this
}

/**
 * A bitset-encoded block, whichever side of the mapping boundary it is on.
 *
 * The one place a cross-kind operation is worth specialising: two bitsets intersect in 1024 word
 * operations, and going through [ReadableContainer.contains] instead would be sixty-four times as
 * many probes for the same answer. Only bitsets implement it, so the fast path is a type test.
 */
internal interface BitsetSource {
    fun word(index: Int): Long
}

/** A walk over the values of one block, in ascending order. */
internal interface ContainerCursor {
    /** The value the cursor sits on. Valid only after [next] or [advanceTo] returned `true`. */
    val low: Int

    /** Advances to the next value. `false` once the block is exhausted. */
    fun next(): Boolean

    /**
     * Advances to the first value at or after [low] which is at or after the current position.
     *
     * The leapfrog step. Intersecting two bitmaps by walking both from the start costs the sum of
     * their sizes; walking one and jumping the other costs the smaller of them, which is the whole
     * reason an index intersection is cheaper than a merge.
     */
    fun advanceTo(low: Int): Boolean
}

/**
 * The part of [ReadableContainer.verify] that every encoding shares.
 *
 * Three claims, checked through the read surface rather than against each encoding's bytes, so one
 * function covers all three kinds: the values a cursor yields ascend and do not repeat, there are as
 * many of them as the directory said, and the encoding in use is the one
 * [BitmapFormat.smallestKind] would have chosen. The last is what makes "equal value sets encode to
 * identical bytes" enforceable on *reading* as well as on writing — a block that decodes correctly but
 * was encoded non-canonically would otherwise break the property silently.
 */
internal fun verifyContainerContents(container: ReadableContainer, report: (String) -> Nothing) {
    var previous = -1
    var counted = 0
    val cursor = container.cursor()
    while (cursor.next()) {
        if (cursor.low <= previous) {
            report("${BitmapFormat.kindName(container.kind)} block holds ${cursor.low} after $previous")
        }
        previous = cursor.low
        counted++
    }
    if (counted != container.cardinality) {
        report("block holds $counted value(s) but the directory claims ${container.cardinality}")
    }
    if (counted == 0) report("block holds no values")
    val expected = BitmapFormat.smallestKind(counted, container.runCount)
    if (container.kind != expected) {
        report(
            "block of $counted value(s) in ${container.runCount} run(s) is encoded as " +
                "${BitmapFormat.kindName(container.kind)} rather than ${BitmapFormat.kindName(expected)}",
        )
    }
}

/**
 * Whether two blocks share a value.
 *
 * Neither operand is materialised and nothing is built: the answer is a `Boolean`, and the planner
 * asks it of sidecars it may then decide not to read at all. Two bitsets take the word path; anything
 * else walks the sparser side and probes the denser, which is `O(min)` probes rather than `O(a + b)`.
 */
internal fun containersIntersect(left: ReadableContainer, right: ReadableContainer): Boolean {
    if (left is BitsetSource && right is BitsetSource) {
        for (index in 0 until BitmapFormat.BITSET_WORDS) {
            if (left.word(index) and right.word(index) != 0L) return true
        }
        return false
    }
    val probe = if (left.cardinality <= right.cardinality) left else right
    val target = if (probe === left) right else left
    val cursor = probe.cursor()
    while (cursor.next()) {
        if (target.contains(cursor.low)) return true
    }
    return false
}

/**
 * How many values two blocks share, without building the intersection.
 *
 * The planner's other question: whether an index is selective enough to be worth using is a
 * cardinality, and computing it by materialising the intersection would allocate exactly what the
 * decision might reject.
 */
internal fun containersAndCardinality(left: ReadableContainer, right: ReadableContainer): Int {
    if (left is BitsetSource && right is BitsetSource) {
        var count = 0
        for (index in 0 until BitmapFormat.BITSET_WORDS) {
            count += (left.word(index) and right.word(index)).countOneBits()
        }
        return count
    }
    val probe = if (left.cardinality <= right.cardinality) left else right
    val target = if (probe === left) right else left
    var count = 0
    val cursor = probe.cursor()
    while (cursor.next()) {
        if (target.contains(cursor.low)) count++
    }
    return count
}
