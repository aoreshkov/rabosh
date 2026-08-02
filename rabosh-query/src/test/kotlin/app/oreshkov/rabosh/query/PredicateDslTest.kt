package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The DSL builds what it reads like, and refuses what the engine has no meaning for. */
class PredicateDslTest {

    private val a = CatalogPath.parse("$.a")

    @Test
    fun `comparisons build the leaf they read as`() {
        assertEquals(Predicate.Compare(a, Comparison.EQ, QueryValue.of("x")), path("$.a") eq "x")
        assertEquals(Predicate.Compare(a, Comparison.LT, QueryValue.of(1L)), path("$.a") lt 1L)
        assertEquals(Predicate.Compare(a, Comparison.LE, QueryValue.of(1L)), path("$.a") le 1L)
        assertEquals(Predicate.Compare(a, Comparison.GT, QueryValue.of(1L)), path("$.a") gt 1L)
        assertEquals(Predicate.Compare(a, Comparison.GE, QueryValue.of(1L)), path("$.a") ge 1L)
        assertEquals(Predicate.Exists(a), path("$.a").exists())
        assertEquals(Predicate.IsNull(a), path("$.a").isNull())
        assertEquals(
            Predicate.AnyOf(a, listOf(QueryValue.of("x"), QueryValue.of(1L))),
            path("$.a").oneOf("x", 1L),
        )
    }

    @Test
    fun `between is two bounds and both are inclusive`() {
        assertEquals(
            Predicate.And(listOf(path("$.a") ge 1L, path("$.a") le 9L)),
            path("$.a").between(1L, 9L),
        )
        assertEquals(
            Predicate.And(listOf(path("$.a") ge BigDecimal("1.5"), path("$.a") le BigDecimal("9.5"))),
            path("$.a").between(BigDecimal("1.5"), BigDecimal("9.5")),
        )
    }

    @Test
    fun `and, or and not compose both ways round`() {
        val left = path("$.a") eq 1L
        val right = path("$.b") eq 2L
        assertEquals(Predicate.And(listOf(left, right)), left and right)
        assertEquals(Predicate.And(listOf(left, right)), and(left, right))
        assertEquals(Predicate.Or(listOf(left, right)), left or right)
        assertEquals(Predicate.Or(listOf(left, right)), or(left, right))
        assertEquals(Predicate.Not(left), not(left))
        assertEquals(Predicate.And(listOf(left, right)), allOf(listOf(left, right)))
        assertEquals(Predicate.Or(listOf(left, right)), anyOf(listOf(left, right)))
    }

    /**
     * **There is no `NE`**, and that is a decision rather than an omission: `not(x eq 1)` is the only
     * spelling, so negation has one meaning. A separate operator would have to decide for itself what
     * an absent path does, and the day it disagreed nothing would say which was right.
     */
    @Test
    fun `there is no not-equal operator`() {
        assertEquals(listOf("EQ", "LT", "LE", "GT", "GE"), Comparison.entries.map { it.name })
    }

    @Test
    fun `a malformed path is refused with its position`() {
        val failure = assertFailsWith<IllegalArgumentException> { path("$.items[0]") }
        assertTrue(failure.message!!.contains("indices"), failure.message!!)
        assertFailsWith<IllegalArgumentException> { path("not a path") }
        assertFailsWith<IllegalArgumentException> { path("$.a[") }
    }

    @Test
    fun `a literal the engine cannot bracket is refused rather than guessed at`() {
        assertFailsWith<IllegalArgumentException> { QueryValue.ofAny(listOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { QueryValue.of(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { QueryValue.of(Double.POSITIVE_INFINITY) }
        assertEquals(QueryValue.Null, QueryValue.ofAny(null))
        assertEquals(QueryValue.of(1L), QueryValue.ofAny(1))
        assertEquals(QueryValue.of(1L), QueryValue.ofAny(1.0))
    }

    @Test
    fun `paths reports every path a predicate mentions, once`() {
        val predicate = and(
            path("$.a") eq 1L,
            or(path("$.b") eq 2L, path("$.a") eq 3L),
            not(path("$.c").exists()),
        )
        assertEquals(
            listOf("$.a", "$.b", "$.c"),
            predicate.paths().map { it.toString() },
        )
    }
}
