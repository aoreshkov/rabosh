package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexOptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Negation-normal form, folding, and the rewrite that must never happen.
 *
 * Most of this is bookkeeping: De Morgan, flattening, dropping identities. The last test is not. It
 * pins the one simplification that looks obviously right and deletes documents from a result, and it
 * does so over data rather than over syntax — because the two predicates it compares are equal by
 * every syntactic argument and different over any corpus where a path holds more than one type.
 */
class PredicateNormalisationTest {

    private val a = path("$.a")
    private val b = path("$.b")

    @Test
    fun `de morgan pushes negations down to leaves`() {
        assertEquals(
            Predicate.Or(listOf(Predicate.Not(a eq 1L), Predicate.Not(b eq 2L))),
            not(and(a eq 1L, b eq 2L)).normalise(),
        )
        assertEquals(
            Predicate.And(listOf(Predicate.Not(a eq 1L), Predicate.Not(b eq 2L))),
            not(or(a eq 1L, b eq 2L)).normalise(),
        )
    }

    @Test
    fun `a double negation cancels`() {
        assertEquals(a eq 1L, not(not(a eq 1L)).normalise())
        assertEquals(Predicate.Not(a eq 1L), not(not(not(a eq 1L))).normalise())
    }

    @Test
    fun `nested junctions of the same kind are flattened`() {
        assertEquals(
            Predicate.And(listOf(a eq 1L, b eq 2L, a eq 3L)),
            and(and(a eq 1L, b eq 2L), a eq 3L).normalise(),
        )
    }

    @Test
    fun `constants are absorbed and identities dropped`() {
        assertEquals(Predicate.False, and(a eq 1L, Predicate.False).normalise())
        assertEquals(a eq 1L, and(a eq 1L, Predicate.True).normalise())
        assertEquals(Predicate.True, or(a eq 1L, Predicate.True).normalise())
        assertEquals(a eq 1L, or(a eq 1L, Predicate.False).normalise())
        assertEquals(Predicate.True, and().normalise())
        assertEquals(Predicate.False, or().normalise())
        assertEquals(Predicate.False, not(Predicate.True).normalise())
    }

    @Test
    fun `a repeated operand is kept once`() {
        assertEquals(a eq 1L, and(a eq 1L, a eq 1L).normalise())
        assertEquals(Predicate.And(listOf(a eq 1L, b eq 2L)), and(a eq 1L, b eq 2L, a eq 1L).normalise())
    }

    @Test
    fun `an IN of one value is an equality and an IN of none matches nothing`() {
        assertEquals(a eq 1L, (a oneOf listOf(1L)).normalise())
        assertEquals(Predicate.False, (a oneOf emptyList()).normalise())
        assertEquals(Predicate.True, not(a oneOf emptyList()).normalise())
        assertEquals(Predicate.AnyOf(a.path, listOf(QueryValue.of(1L), QueryValue.of(2L))), a.oneOf(1L, 2L, 1L).normalise())
    }

    @Test
    fun `numbers are canonical across the widths a document may hold them at`() {
        assertEquals(a eq 10L, a eq java.math.BigDecimal("10.00"))
        assertEquals(a eq 10L, a eq 10.0)
        assertEquals(QueryValue.of(1L), QueryValue.of(java.math.BigDecimal("1.000")))
    }

    /**
     * **The rewrite that must never happen**, asserted over data rather than over syntax.
     *
     * `not($.a >= 10)` holds for a document whose `a` is a string and for one with no `a` at all;
     * `$.a < 10` holds for neither, because a numeric predicate matches numbers only. The two are
     * therefore different questions, and a normaliser that "simplified" one into the other would
     * silently drop every document of another type — invisible to any test whose corpus is uniform.
     */
    @Test
    fun `a negated range is not a flipped range`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.load(
                    listOf(
                        jsonDocument("""{"a":5}"""),
                        jsonDocument("""{"a":50}"""),
                        jsonDocument("""{"a":"high"}"""),
                        jsonDocument("""{"b":1}"""),
                        jsonDocument("""{"a":null}"""),
                    ),
                )
                catalog.createIndex(store, IndexDefinition.column("$.a"))

                store.snapshot().use { snapshot ->
                    val negated = engine.keys(Query.where(not(a ge 10L)), snapshot)
                    val flipped = engine.keys(Query.where(a lt 10L), snapshot)

                    assertEquals(listOf(keyFor(0), keyFor(2), keyFor(3), keyFor(4)), negated)
                    assertEquals(listOf(keyFor(0)), flipped)
                    assertNotEquals(negated, flipped, "type bracketing is what makes these different")

                    // And normalisation must have preserved the difference rather than smoothing it.
                    val normalised = not(a ge 10L).normalise()
                    assertEquals(Predicate.Not(a ge 10L), normalised)
                }
            }
        }
    }

    /** A leaf keeps its own negation flag through lowering, which is what the executor reads. */
    @Test
    fun `lowering keeps a negated leaf negated`() {
        val leaf = not(a eq 1L).normalise().lower(IndexOptions.DEFAULT)
        assertTrue(leaf is Normal.Leaf && leaf.negated)
        val positive = (a eq 1L).normalise().lower(IndexOptions.DEFAULT)
        assertTrue(positive is Normal.Leaf && !positive.negated)
    }

    /** An ordering against a boolean or a null has no answer, so it matches nothing rather than failing. */
    @Test
    fun `an ordered comparison against an unordered literal matches nothing`() {
        val lowered = Predicate.Compare(a.path, Comparison.LT, QueryValue.of(true)).lower(IndexOptions.DEFAULT)
        assertEquals(Normal.AlwaysFalse, lowered)
    }
}
