package app.oreshkov.rabosh.query

import java.math.BigDecimal

/**
 * A literal a predicate compares against.
 *
 * Deliberately not a `Variant`: a predicate has to be writable without an encoder, has to hash, and
 * has to survive being compared with itself — none of which a document view offers. What it *is* is
 * the four families the engine can bracket a value into, which is what keeps the AST and
 * `ColumnPredicate` speaking about the same thing.
 *
 * **Numbers are canonical.** Every [Numeric] strips its trailing zeros, so `10`, `10.0` and `1e1` are
 * one literal. That is the same canonicalisation `ValueSignature` applies on the way into a term
 * dictionary, and the two have to agree or a query written with an integer would fail to find a
 * document that stored a decimal.
 *
 * There is no binary literal. JSON produces none, `ColumnPredicate` has no binary family to bracket
 * one into, and a literal that only one of the two index kinds could answer for would be a value
 * whose plan depends on which index happens to exist.
 */
public sealed interface QueryValue {

    /** A JSON string. */
    public class Text(public val value: String) : QueryValue {
        override fun equals(other: Any?): Boolean = this === other || (other is Text && value == other.value)

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "\"$value\""
    }

    /** A JSON number, canonical across the widths a document may have stored it at. */
    public class Numeric private constructor(public val value: BigDecimal) : QueryValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Numeric && value.compareTo(other.value) == 0)

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = value.toPlainString()

        internal companion object {
            fun of(value: BigDecimal): Numeric = Numeric(value.stripTrailingZeros())
        }
    }

    /** A JSON boolean. */
    public class Bool(public val value: Boolean) : QueryValue {
        override fun equals(other: Any?): Boolean = this === other || (other is Bool && value == other.value)

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = value.toString()
    }

    /** The JSON null, which is a value a path can hold rather than the absence of one. */
    public data object Null : QueryValue

    public companion object {
        /** The literal for [value]. */
        public fun of(value: String): QueryValue = Text(value)

        /** The literal for [value]. */
        public fun of(value: Long): QueryValue = Numeric.of(BigDecimal.valueOf(value))

        /** The literal for [value]. Rejects NaN and the infinities, which no ordering admits. */
        public fun of(value: Double): QueryValue {
            require(value.isFinite()) { "$value has no place in an ordering; NaN and the infinities are not literals" }
            return Numeric.of(BigDecimal.valueOf(value))
        }

        /** The literal for [value]. */
        public fun of(value: BigDecimal): QueryValue = Numeric.of(value)

        /** The literal for [value]. */
        public fun of(value: Boolean): QueryValue = Bool(value)

        /**
         * The literal for [value], for a caller holding an untyped one.
         *
         * @throws IllegalArgumentException for anything that is not a string, a finite number, a
         *   boolean or `null` — a type this engine cannot bracket is a mistake to report rather than
         *   a value to guess at.
         */
        public fun ofAny(value: Any?): QueryValue = when (value) {
            null -> Null
            is String -> of(value)
            is BigDecimal -> of(value)
            is Double -> of(value)
            is Float -> of(value.toDouble())
            is Boolean -> of(value)
            is Int, is Long, is Short, is Byte -> of((value as Number).toLong())
            else -> throw IllegalArgumentException(
                "${value::class.simpleName} is not a query literal; use a string, a number, a boolean or null",
            )
        }
    }
}
