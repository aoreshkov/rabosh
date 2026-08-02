package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import java.math.BigDecimal

/**
 * A path a predicate is being written about.
 *
 * The receiver half of the DSL, and nothing more: it holds a [CatalogPath] and builds leaves. The
 * overloads exist so that a literal is typed at the call site rather than boxed and sorted out later
 * — `path("$.score") ge 10` cannot be written against a string bound by accident.
 */
public class PathRef internal constructor(public val path: CatalogPath) {

    /** Some value at this path equals [value]. */
    public infix fun eq(value: String): Predicate = compare(Comparison.EQ, QueryValue.of(value))

    /** Some value at this path equals [value], whatever width the document stored it at. */
    public infix fun eq(value: Long): Predicate = compare(Comparison.EQ, QueryValue.of(value))

    /** Some value at this path equals [value]. */
    public infix fun eq(value: Double): Predicate = compare(Comparison.EQ, QueryValue.of(value))

    /** Some value at this path equals [value]. */
    public infix fun eq(value: BigDecimal): Predicate = compare(Comparison.EQ, QueryValue.of(value))

    /** Some value at this path equals [value]. */
    public infix fun eq(value: Boolean): Predicate = compare(Comparison.EQ, QueryValue.of(value))

    /** Some value at this path is less than [value]. */
    public infix fun lt(value: Long): Predicate = compare(Comparison.LT, QueryValue.of(value))

    /** Some value at this path is less than [value]. */
    public infix fun lt(value: BigDecimal): Predicate = compare(Comparison.LT, QueryValue.of(value))

    /** Some value at this path sorts before [value] in UTF-8 byte order. */
    public infix fun lt(value: String): Predicate = compare(Comparison.LT, QueryValue.of(value))

    /** Some value at this path is at most [value]. */
    public infix fun le(value: Long): Predicate = compare(Comparison.LE, QueryValue.of(value))

    /** Some value at this path is at most [value]. */
    public infix fun le(value: BigDecimal): Predicate = compare(Comparison.LE, QueryValue.of(value))

    /** Some value at this path sorts at or before [value] in UTF-8 byte order. */
    public infix fun le(value: String): Predicate = compare(Comparison.LE, QueryValue.of(value))

    /** Some value at this path is greater than [value]. */
    public infix fun gt(value: Long): Predicate = compare(Comparison.GT, QueryValue.of(value))

    /** Some value at this path is greater than [value]. */
    public infix fun gt(value: BigDecimal): Predicate = compare(Comparison.GT, QueryValue.of(value))

    /** Some value at this path sorts after [value] in UTF-8 byte order. */
    public infix fun gt(value: String): Predicate = compare(Comparison.GT, QueryValue.of(value))

    /** Some value at this path is at least [value]. */
    public infix fun ge(value: Long): Predicate = compare(Comparison.GE, QueryValue.of(value))

    /** Some value at this path is at least [value]. */
    public infix fun ge(value: BigDecimal): Predicate = compare(Comparison.GE, QueryValue.of(value))

    /** Some value at this path sorts at or after [value] in UTF-8 byte order. */
    public infix fun ge(value: String): Predicate = compare(Comparison.GE, QueryValue.of(value))

    /** Some value at this path equals one of [values]. The `IN` case. */
    public infix fun oneOf(values: Collection<Any?>): Predicate =
        Predicate.AnyOf(path, values.map(QueryValue::ofAny))

    /** Some value at this path equals one of [values]. */
    public fun oneOf(vararg values: Any?): Predicate = oneOf(values.toList())

    /** Some value at this path lies in `[min, max]`, both bounds inclusive. */
    public fun between(min: BigDecimal, max: BigDecimal): Predicate =
        and(ge(min), le(max))

    /** Some value at this path lies in `[min, max]`, both bounds inclusive. */
    public fun between(min: Long, max: Long): Predicate = and(ge(min), le(max))

    /** Some value at this path lies in `[min, max]` in UTF-8 byte order, both bounds inclusive. */
    public fun between(min: String, max: String): Predicate = and(ge(min), le(max))

    /** This path carries at least one value, the JSON null included. */
    public fun exists(): Predicate = Predicate.Exists(path)

    /** Some value at this path is the JSON null. */
    public fun isNull(): Predicate = Predicate.IsNull(path)

    override fun toString(): String = path.toString()

    private fun compare(operator: Comparison, value: QueryValue): Predicate =
        Predicate.Compare(path, operator, value)
}

/**
 * The path written as an expression, in the catalog's grammar: `$.user.name`, `$.tags[*]`.
 *
 * @throws IllegalArgumentException with the offending position, including for a numeric index —
 *   `$.items[0]` is not a path a catalog or an index knows, because an array collapses to `[*]`.
 */
public fun path(expression: String): PathRef = PathRef(CatalogPath.parse(expression))

/** The path, already parsed. */
public fun path(path: CatalogPath): PathRef = PathRef(path)

/** Every operand holds. */
public fun and(vararg operands: Predicate): Predicate = Predicate.And(operands.toList())

/** Every operand holds. */
public fun allOf(operands: List<Predicate>): Predicate = Predicate.And(operands)

/** At least one operand holds. */
public fun or(vararg operands: Predicate): Predicate = Predicate.Or(operands.toList())

/** At least one operand holds. */
public fun anyOf(operands: List<Predicate>): Predicate = Predicate.Or(operands)

/** The operand does not hold, of the document. See [Predicate] for what that means at a path. */
public fun not(operand: Predicate): Predicate = Predicate.Not(operand)

/** Both hold. */
public infix fun Predicate.and(other: Predicate): Predicate = Predicate.And(listOf(this, other))

/** Either holds. */
public infix fun Predicate.or(other: Predicate): Predicate = Predicate.Or(listOf(this, other))
