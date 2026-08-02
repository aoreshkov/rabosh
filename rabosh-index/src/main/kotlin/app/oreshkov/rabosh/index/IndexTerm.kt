package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.ValueSignature
import app.oreshkov.rabosh.variant.Variant
import java.math.BigDecimal
import java.util.Arrays

/**
 * A value, as the index keys it.
 *
 * This is `ValueSignature` wearing a type, and the type exists so that a query cannot accidentally
 * ask for a term spelled some other way. Two consequences a caller should know about, both inherited
 * and both deliberate:
 *
 * **Numbers are canonical across widths.** `1`, `1.0` and `1.00` are one term, so a query written
 * with an integer finds documents that stored a double. Anything else would make the answer depend on
 * how a JSON writer happened to render the value.
 *
 * **The order is a lookup order, not a value order.** [compareTo] sorts by tag and then by bytes, so
 * `10` sorts before `9`. That is what the term dictionary needs to bisect and it is *not* something a
 * range predicate may be built on. An inverted index answers equality, `IN` and existence; ordered
 * skipping is what a shredded column is for, and the day one exists it will carry its own ordering.
 */
public class IndexTerm private constructor(internal val bytes: ByteArray) : Comparable<IndexTerm> {

    /** Bytes this term occupies. Compared against [IndexOptions.maxTermBytes] before it is looked up. */
    public val size: Int get() = bytes.size

    override fun compareTo(other: IndexTerm): Int = Arrays.compareUnsigned(bytes, other.bytes)

    override fun equals(other: Any?): Boolean =
        this === other || (other is IndexTerm && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String {
        val tag = ValueSignature.tagName(bytes[0].toInt() and 0xFF) ?: "tag ${bytes[0].toInt() and 0xFF}"
        val payload = bytes.copyOfRange(1, bytes.size)
        val rendered = if (tag == "binary") payload.joinToString("") { "%02x".format(it) } else payload.decodeToString()
        return "$tag:$rendered"
    }

    public companion object {
        /**
         * The term [value] would be indexed under, or `null` if it would not be indexed at all.
         *
         * `null` for a JSON null and for containers, matching what the writer does — a document
         * carrying one of those *has* the path, which existence reports, but there is no value to
         * look it up by.
         */
        public fun of(value: Variant): IndexTerm? = ValueSignature.of(value)?.let(::IndexTerm)

        /**
         * The term [value] is keyed under given [options], or `null` if it is not keyed at all.
         *
         * `null` for a JSON null, for a container, and for a value above [IndexOptions.maxTermBytes]
         * — all three are *present* and none of them is keyed. The bound is applied here so that the
         * writer, the reader and a query's recheck cannot drift apart about which values a dictionary
         * can spell; a query that skipped it would take a false negative from an index that dropped
         * the value on the way in.
         */
        public fun of(value: Variant, options: IndexOptions): IndexTerm? =
            ValueSignature.of(value)?.takeIf { it.size <= options.maxTermBytes }?.let(::IndexTerm)

        /** The term a string is indexed under. */
        public fun ofString(text: String): IndexTerm = IndexTerm(ValueSignature.ofText(text))

        /** The term a number is indexed under, whatever width the document stored it at. */
        public fun ofNumber(value: BigDecimal): IndexTerm = IndexTerm(ValueSignature.ofNumber(value))

        /** The term a number is indexed under, whatever width the document stored it at. */
        public fun ofNumber(value: Long): IndexTerm = IndexTerm(ValueSignature.ofNumber(value))

        /** The term a number is indexed under, whatever width the document stored it at. */
        public fun ofNumber(value: Double): IndexTerm = IndexTerm(ValueSignature.ofNumber(value))

        /** The term a boolean is indexed under. */
        public fun ofBoolean(value: Boolean): IndexTerm = IndexTerm(ValueSignature.ofBoolean(value))

        /** The term a byte string is indexed under. */
        public fun ofBinary(value: ByteArray): IndexTerm = IndexTerm(ValueSignature.ofBinary(value))

        internal fun ofSignature(signature: ByteArray): IndexTerm = IndexTerm(signature)
    }
}
