package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.RaboshExperimental
import java.util.Arrays

/**
 * A mergeable estimate of how many distinct values a path holds.
 *
 * Cardinality is what turns a list of paths into a recommendation. A path whose every document
 * carries a different value is a key, not an index; a path with two values is a flag and a bitmap
 * over it saves nothing. The useful band is in between, and finding it needs a distinct count over
 * millions of values held in a few hundred bytes and mergeable across segments — which is what
 * [HyperLogLog](https://en.wikipedia.org/wiki/HyperLogLog) is for.
 *
 * **Mergeability is the property the whole design rests on.** The catalog's model of a store is the
 * fold of its live segments' sketches, so a merge that depended on order — or that lost accuracy
 * each time — would make the model depend on the compaction history rather than on the data.
 * Register-wise maximum has neither problem: it is associative, commutative, idempotent, and exact
 * in the sense that merging two sketches gives byte-for-byte what sketching the union would have.
 *
 * **Two representations, and which one is in use is a function of the data alone.**
 *
 * - **Sparse**, up to [SPARSE_LIMIT] distinct 64-bit hashes kept verbatim. The estimate is then the
 *   count, exactly. Most paths in a real document live here — a status field, a country code, a
 *   boolean — and these are exactly the paths worth indexing, so the estimator is exact precisely
 *   where the recommendation is being made.
 * - **Dense**, [REGISTER_COUNT] one-byte registers, once the sparse set would overflow.
 *
 * A merge whose result would exceed the sparse limit produces a dense sketch, and a merge involving
 * a dense sketch is dense. Both conditions depend only on the union, never on the order the union
 * was built in, so two different fold orders produce **identical bytes**. The property test asserts
 * that equality rather than an approximate one.
 *
 * **The precision is a format constant, not a knob.** Registers written at one precision cannot be
 * merged with registers written at another, and a knob whose values do not interoperate is a trap.
 * It lives in [SketchFormat] with the rest of the permanent constants.
 *
 * Mutable, and deliberately: accumulation calls [add] once per value on the flush and compaction
 * path, and a copy per value would dwarf the sketch. Merging into a *new* sketch is [mergedWith];
 * [merge] mutates. Not thread-safe — one observation belongs to one segment writer.
 */
@RaboshExperimental
public class HyperLogLog private constructor(
    private var hashes: LongArray?,
    private var hashCount: Int,
    private var registers: ByteArray?,
) {
    /** An empty estimator. */
    public constructor() : this(LongArray(INITIAL_SPARSE_CAPACITY), 0, null)

    /** Whether the exact, small-cardinality representation is still in use. */
    public val isSparse: Boolean get() = registers == null

    /** Adds a value by its 64-bit hash. */
    public fun add(hash: Long) {
        val dense = registers
        if (dense != null) {
            applyTo(dense, hash)
            return
        }
        val sparse = checkNotNull(hashes)
        val at = Arrays.binarySearch(sparse, 0, hashCount, hash)
        if (at >= 0) return
        if (hashCount == SPARSE_LIMIT) {
            promote()
            applyTo(checkNotNull(registers), hash)
            return
        }
        val insertAt = -at - 1
        val target = if (hashCount == sparse.size) grow(sparse) else sparse
        hashes = target
        System.arraycopy(target, insertAt, target, insertAt + 1, hashCount - insertAt)
        target[insertAt] = hash
        hashCount++
    }

    /** Adds a value by its bytes. */
    public fun add(bytes: ByteArray): Unit = add(SketchHash.hash64(bytes))

    /**
     * Folds [other] into this estimator.
     *
     * Associative, commutative and idempotent, and the representation of the result depends only on
     * the union of the two inputs — see the class documentation for why that matters.
     */
    public fun merge(other: HyperLogLog) {
        val theirs = other.registers
        if (theirs != null) {
            if (registers == null) promote()
            val mine = checkNotNull(registers)
            for (index in mine.indices) {
                if (theirs[index] > mine[index]) mine[index] = theirs[index]
            }
            return
        }
        val theirHashes = checkNotNull(other.hashes)
        for (index in 0 until other.hashCount) add(theirHashes[index])
    }

    /** A copy of this estimator with [other] folded into it, leaving both operands untouched. */
    public fun mergedWith(other: HyperLogLog): HyperLogLog = copy().also { it.merge(other) }

    /** An independent copy. */
    public fun copy(): HyperLogLog =
        HyperLogLog(hashes?.copyOf(), hashCount, registers?.copyOf())

    /** `true` when nothing has been added. */
    public val isEmpty: Boolean get() = registers == null && hashCount == 0

    /**
     * The estimated number of distinct values.
     *
     * Exact while the sketch is sparse. Once dense, the harmonic-mean estimator with the standard
     * bias constant, falling back to linear counting while empty registers remain — which is the
     * range where the harmonic estimator is known to be badly biased. The large-range correction the
     * original paper describes is **not** applied and must not be: it exists to undo the wrap-around
     * of a 32-bit hash, and this one is 64 bits, so applying it would introduce the error it was
     * written to remove.
     */
    public val estimate: Long
        get() {
            val dense = registers ?: return hashCount.toLong()
            var zeros = 0
            var inverseSum = 0.0
            for (register in dense) {
                val value = register.toInt()
                if (value == 0) zeros++
                inverseSum += 1.0 / (1L shl value).toDouble()
            }
            val raw = ALPHA * REGISTER_COUNT * REGISTER_COUNT / inverseSum
            if (zeros > 0 && raw <= LINEAR_COUNTING_THRESHOLD) {
                return Math.round(REGISTER_COUNT * kotlin.math.ln(REGISTER_COUNT.toDouble() / zeros))
            }
            return Math.round(raw)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HyperLogLog) return false
        val mine = registers
        val theirs = other.registers
        if ((mine == null) != (theirs == null)) return false
        if (mine != null) return mine.contentEquals(theirs)
        if (hashCount != other.hashCount) return false
        val a = checkNotNull(hashes)
        val b = checkNotNull(other.hashes)
        return Arrays.equals(a, 0, hashCount, b, 0, other.hashCount)
    }

    override fun hashCode(): Int {
        val dense = registers
        if (dense != null) return dense.contentHashCode()
        var result = hashCount
        val sparse = checkNotNull(hashes)
        for (index in 0 until hashCount) result = 31 * result + sparse[index].hashCode()
        return result
    }

    override fun toString(): String =
        if (isSparse) "HyperLogLog(exact, $hashCount)" else "HyperLogLog(~$estimate)"

    // --- encoding ------------------------------------------------------------------------------

    /** Writes this estimator in the sidecar's encoding. See [SketchFormat]. */
    internal fun writeTo(out: SketchWriter) {
        val dense = registers
        if (dense == null) {
            val sparse = checkNotNull(hashes)
            out.writeByte(SketchFormat.HLL_SPARSE)
            out.writeByte(SketchFormat.HLL_PRECISION)
            out.writeInt(hashCount)
            for (index in 0 until hashCount) out.writeLong(sparse[index])
        } else {
            out.writeByte(SketchFormat.HLL_DENSE)
            out.writeByte(SketchFormat.HLL_PRECISION)
            out.write(dense)
        }
    }

    internal companion object {
        /**
         * Bits of hash used as a register index: 1024 registers, a standard error near 3.25%.
         *
         * Permanent. Enough to tell "a hundred distinct values" from "a hundred thousand", which is
         * the question an index recommendation asks — and small enough that a segment holding the
         * full path budget spends about a megabyte on estimators in the worst case rather than four.
         */
        const val PRECISION: Int = 10

        const val REGISTER_COUNT: Int = 1 shl PRECISION

        /**
         * Distinct hashes kept verbatim before switching to registers.
         *
         * Ninety-six, so a low-cardinality path costs 768 bytes and an exact answer rather than 1024
         * bytes and an estimate. Permanent, because it decides which representation a sidecar holds.
         */
        const val SPARSE_LIMIT: Int = 96

        private const val INITIAL_SPARSE_CAPACITY = 8

        /** The bias constant for 1024 registers: `0.7213 / (1 + 1.079 / m)`. */
        private const val ALPHA: Double = 0.7213 / (1.0 + 1.079 / REGISTER_COUNT)

        /** Below `2.5m` the harmonic estimator is biased, and linear counting is better. */
        private const val LINEAR_COUNTING_THRESHOLD: Double = 2.5 * REGISTER_COUNT

        /** Reads an estimator written by [writeTo]. */
        fun readFrom(reader: SketchReader): HyperLogLog {
            val mode = reader.byte("estimator mode")
            val precision = reader.byte("estimator precision")
            if (precision != SketchFormat.HLL_PRECISION) {
                // Registers at one precision cannot be merged with registers at another, and folding
                // one down to the other is not implemented — so this is a format the build cannot
                // use, not damage. Rebuilding the sidecar is the fix, and it is cheap.
                throw UnsupportedSketchFormatException(
                    "${reader.file} holds a cardinality estimator at precision $precision; " +
                        "this build uses ${SketchFormat.HLL_PRECISION}",
                )
            }
            return when (mode) {
                SketchFormat.HLL_SPARSE -> {
                    val count = reader.count("estimator entry count", SPARSE_LIMIT)
                    val hashes = LongArray(maxOf(count, INITIAL_SPARSE_CAPACITY))
                    var previous = Long.MIN_VALUE
                    for (index in 0 until count) {
                        val hash = reader.long("estimator entry")
                        // Sorted and distinct is not decoration: `add` binary-searches, `equals`
                        // compares element-wise, and a file that broke either would make two sketches
                        // of the same data compare unequal.
                        if (index > 0 && hash <= previous) {
                            reader.corrupt("estimator entries are not strictly ascending")
                        }
                        previous = hash
                        hashes[index] = hash
                    }
                    HyperLogLog(hashes, count, null)
                }

                SketchFormat.HLL_DENSE -> {
                    val registers = reader.bytes(REGISTER_COUNT, "estimator registers")
                    for (register in registers) {
                        val value = register.toInt()
                        if (value < 0 || value > MAX_RANK) {
                            reader.corrupt("estimator register value $value is outside 0..$MAX_RANK")
                        }
                    }
                    HyperLogLog(null, 0, registers)
                }

                else -> reader.corrupt("unknown cardinality estimator mode $mode")
            }
        }

        /** Largest rank a 64-bit hash can produce at this precision, and so the largest register. */
        private const val MAX_RANK: Int = 64 - PRECISION + 1

        private fun applyTo(registers: ByteArray, hash: Long) {
            val index = (hash ushr (64 - PRECISION)).toInt()
            // The remaining bits, left-aligned. All-zero would give 64 leading zeros, which is one
            // past what the field can mean, so the rank is capped where the bits run out.
            val remaining = hash shl PRECISION
            val rank = minOf(java.lang.Long.numberOfLeadingZeros(remaining) + 1, MAX_RANK)
            if (rank > registers[index].toInt()) registers[index] = rank.toByte()
        }

        private fun grow(sparse: LongArray): LongArray = sparse.copyOf(minOf(sparse.size * 2, SPARSE_LIMIT + 1))
    }

    private fun promote() {
        val dense = ByteArray(REGISTER_COUNT)
        val sparse = checkNotNull(hashes)
        for (index in 0 until hashCount) applyTo(dense, sparse[index])
        registers = dense
        hashes = null
        hashCount = 0
    }
}
