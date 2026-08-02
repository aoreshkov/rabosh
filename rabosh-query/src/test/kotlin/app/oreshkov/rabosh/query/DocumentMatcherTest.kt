package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The evaluator on its own, against the reference model.
 *
 * This is where the *meaning* of a predicate is checked, separately from any plan: what a leaf does
 * with an array, with a nested null, with an absent path and with a value of the wrong type. Getting
 * these wrong would make every plan agree with a scan and every answer wrong together.
 */
class DocumentMatcherTest {

    private fun matches(predicate: Predicate, json: String): Boolean =
        DocumentMatcher(predicate.normalise().lower(IndexOptions.DEFAULT), IndexOptions.DEFAULT)
            .matches(jsonDocument(json))

    @Test
    fun `a leaf is existential over the values at its path`() {
        assertTrue(matches(path("$.tags[*]") eq "b", """{"tags":["a","b","c"]}"""))
        assertFalse(matches(path("$.tags[*]") eq "z", """{"tags":["a","b","c"]}"""))
        // And its negation is about the document: no tag is "b".
        assertFalse(matches(not(path("$.tags[*]") eq "b"), """{"tags":["a","b"]}"""))
        assertTrue(matches(not(path("$.tags[*]") eq "z"), """{"tags":["a","b"]}"""))
        assertTrue(matches(not(path("$.tags[*]") eq "z"), """{"other":1}"""))
    }

    @Test
    fun `type bracketing decides what a comparison even sees`() {
        assertFalse(matches(path("$.a") ge 10L, """{"a":"high"}"""))
        assertFalse(matches(path("$.a") ge 10L, """{"a":true}"""))
        assertFalse(matches(path("$.a") ge 10L, """{"a":null}"""))
        assertFalse(matches(path("$.a") ge 10L, """{"b":99}"""))
        assertTrue(matches(path("$.a") ge 10L, """{"a":10}"""))
        // Text ranges see strings only, and compare in UTF-8 byte order.
        assertTrue(matches(path("$.a") ge "m", """{"a":"z"}"""))
        assertFalse(matches(path("$.a") ge "m", """{"a":26}"""))
    }

    @Test
    fun `a number is the same value however the document spelled it`() {
        for (literal in listOf("10", "10.0", "1.0e1", "10.00")) {
            assertTrue(matches(path("$.a") eq 10L, """{"a":$literal}"""), literal)
            assertTrue(matches(path("$.a") eq BigDecimal("10.000"), """{"a":$literal}"""), literal)
        }
    }

    @Test
    fun `present, null and absent are three different states`() {
        assertTrue(matches(path("$.note").exists(), """{"note":null}"""))
        assertTrue(matches(path("$.note").isNull(), """{"note":null}"""))
        assertFalse(matches(path("$.note").exists(), """{"other":1}"""))
        assertFalse(matches(path("$.note").isNull(), """{"other":1}"""))
        assertFalse(matches(path("$.note").isNull(), """{"note":"x"}"""))
        assertTrue(matches(not(path("$.note").exists()), """{"other":1}"""))
        assertFalse(matches(not(path("$.note").exists()), """{"note":null}"""))
    }

    @Test
    fun `a container at the end of a path is not a value`() {
        assertFalse(matches(path("$.a").exists(), """{"a":{"b":1}}"""))
        assertFalse(matches(path("$.a").exists(), """{"a":[1,2]}"""))
        assertTrue(matches(path("$.a.b").exists(), """{"a":{"b":1}}"""))
        assertTrue(matches(path("$.a[*]").exists(), """{"a":[1,2]}"""))
    }

    @Test
    fun `conjunctions and disjunctions fold over the leaves`() {
        val document = """{"a":1,"b":"x"}"""
        assertTrue(matches(and(path("$.a") eq 1L, path("$.b") eq "x"), document))
        assertFalse(matches(and(path("$.a") eq 1L, path("$.b") eq "y"), document))
        assertTrue(matches(or(path("$.a") eq 9L, path("$.b") eq "x"), document))
        assertFalse(matches(or(path("$.a") eq 9L, path("$.b") eq "y"), document))
        assertTrue(matches(Predicate.True, document))
        assertFalse(matches(Predicate.False, document))
    }

    /** One matcher, many documents: the reused state must not leak between them. */
    @Test
    fun `a matcher reused across documents does not remember the last one`() {
        val matcher = DocumentMatcher(
            (path("$.a") eq 1L).normalise().lower(IndexOptions.DEFAULT),
            IndexOptions.DEFAULT,
        )
        assertTrue(matcher.matches(jsonDocument("""{"a":1}""")))
        assertFalse(matcher.matches(jsonDocument("""{"a":2}""")))
        assertTrue(matcher.matches(jsonDocument("""{"a":1}""")))
        assertFalse(matcher.matches(jsonDocument("""{"b":1}""")))
    }

    /** Over generated documents, the evaluator and the reference model agree about every leaf shape. */
    @Test
    fun `the evaluator agrees with the reference model over generated documents`() {
        val paths = listOf("$.a", "$.b.c", "$.tags[*]", "$.missing")
        forAll(JsonGens.document()) { document ->
            for (expression in paths) {
                val catalogPath = CatalogPath.parse(expression)
                val values = valuesAt(document, catalogPath)
                val encoded = jsonDocument(document)

                fun check(predicate: Predicate, note: String) {
                    val matcher = DocumentMatcher(
                        predicate.normalise().lower(IndexOptions.DEFAULT),
                        IndexOptions.DEFAULT,
                    )
                    assertEquals(
                        referenceKeys(mapOf(keyFor(0) to document), predicate).isNotEmpty(),
                        matcher.matches(encoded),
                        "$note over $expression of ${document.toJsonString()}",
                    )
                }

                check(path(expression).exists(), "exists")
                check(path(expression).isNull(), "is null")
                check(not(path(expression).exists()), "not exists")
                check(path(expression) eq "x", "eq text")
                check(path(expression) eq 1L, "eq number")
                check(path(expression) ge 0L, "ge number")
                check(path(expression) lt "m", "lt text")
                check(not(path(expression) ge 0L), "negated range")

                // And whatever the corpus produced, a literal drawn from it must be found.
                val scalar = values.firstOrNull { it is JsonValue.Str || it is JsonValue.Num }
                if (scalar is JsonValue.Str) check(path(expression) eq scalar.value, "eq an actual value")
                if (scalar is JsonValue.Num) {
                    check(path(expression) eq BigDecimal(scalar.literal), "eq an actual number")
                }
            }
        }
    }
}
