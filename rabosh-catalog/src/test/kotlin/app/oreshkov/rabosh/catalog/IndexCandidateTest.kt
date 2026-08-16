package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The recommendation, checked against a corpus built with one deliberately good path and three
 * deliberately bad ones.
 *
 * The point of the test is not the exact scores — every threshold here is a heuristic and is the
 * caller's to change — but that the reasons hold: a path nothing has, a path with one value, and a
 * path whose type is not stable must not be recommended, and each must be excluded by the rule that
 * is supposed to exclude it.
 */
class IndexCandidateTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the good path is recommended and the bad ones are not`() {
        withSchema { schema ->
            val candidates = rankIndexCandidates(schema, IndexCandidateOptions.DEFAULT)
            val recommended = candidates.filter { it.kind == IndexKind.INVERTED }.map { it.path.toString() }

            assertTrue("$.team" in recommended, "present, one type, twelve values: $recommended")
            assertTrue("$.rare" !in recommended, "present in 5% of documents")
            assertTrue("$.constant" !in recommended, "one value, so an index returns everything")
            assertTrue("$.wobbly" !in recommended, "string half the time and an integer the rest")
            assertTrue("$" !in recommended, "the document itself is not an indexable value")
            assertTrue("$.profile" !in recommended, "an object is a place to look inside, not a value")
        }
    }

    @Test
    fun `each exclusion is made by the rule that is meant to make it`() {
        withSchema { schema ->
            fun paths(options: IndexCandidateOptions) =
                rankIndexCandidates(schema, options).map { it.path.toString() }.toSet()

            val relaxedPresence = IndexCandidateOptions(minPresence = 0.01)
            assertTrue("$.rare" in paths(relaxedPresence), "only presence was keeping it out")

            val relaxedStability = IndexCandidateOptions(minTypeStability = 0.4)
            assertTrue("$.wobbly" in paths(relaxedStability), "only type stability was keeping it out")

            val relaxedDistinct = IndexCandidateOptions(minDistinct = 1)
            assertTrue("$.constant" in paths(relaxedDistinct), "only cardinality was keeping it out")
        }
    }

    @Test
    fun `a unique path is a candidate, and a strong one`() {
        // Deliberately *not* excluded by default. A distinct value per document is the best equality
        // index there is; confusing a bitmap's storage shape with an index's usefulness is what an
        // upper bound on cardinality would do *unasked*. Asked for, it is the next test.
        withSchema { schema ->
            val unique = rankIndexCandidates(schema, IndexCandidateOptions.DEFAULT)
                .first { it.path.toString() == "$.id" && it.kind == IndexKind.INVERTED }
            assertTrue(unique.score > 0.9, "score ${unique.score}")
        }
    }

    @Test
    fun `a cardinality ceiling separates a category from an identifier`() {
        // The knob a scorer cannot infer: `$.id` and `$.team` are the same *shape* — present, one
        // type, well distributed — and differ only in how many rows a caller expects back. One
        // distinct value per ten documents keeps the twelve teams and drops the four hundred ids.
        withSchema { schema ->
            val banded = IndexCandidateOptions(maxDistinctFraction = 0.1)
            val terms = rankIndexCandidates(schema, banded)
                .filter { it.kind == IndexKind.INVERTED }
                .map { it.path.toString() }

            assertTrue("$.team" in terms, "twelve values over 400 documents: $terms")
            assertTrue("$.id" !in terms, "one value per document is an identifier, not a category")
            assertTrue(
                "$.id" in rankIndexCandidates(schema, IndexCandidateOptions.DEFAULT)
                    .filter { it.kind == IndexKind.INVERTED }
                    .map { it.path.toString() },
                "only the ceiling was keeping it out",
            )
        }
    }

    @Test
    fun `a cardinality ceiling does not reach the columns`() {
        // `$.description` carries a large share of the bytes and a distinct value per document. The
        // ceiling is about how many rows a *term* returns; a column is read for the bytes it avoids,
        // so the tightest band that admits no term at all must still admit it.
        withSchema { schema ->
            val banded = IndexCandidateOptions(maxDistinctFraction = 1.0 / 400)
            val byKind = rankIndexCandidates(schema, banded).groupBy({ it.kind }, { it.path.toString() })

            assertTrue("$.description" in byKind[IndexKind.SHREDDED_COLUMN].orEmpty(), "$byKind")
            assertTrue("$.description" !in byKind[IndexKind.INVERTED].orEmpty(), "$byKind")
        }
    }

    @Test
    fun `the ceiling is a positive number or it is not a ceiling`() {
        // NaN is the one that matters: every comparison against it is false, so an unvalidated NaN
        // would silently recommend nothing at all and look like a corpus with no indexable paths.
        assertFailsWith<IllegalArgumentException> { IndexCandidateOptions(maxDistinctFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { IndexCandidateOptions(maxDistinctFraction = -1.0) }
        assertFailsWith<IllegalArgumentException> { IndexCandidateOptions(maxDistinctFraction = Double.NaN) }
        assertEquals(Double.POSITIVE_INFINITY, IndexCandidateOptions.DEFAULT.maxDistinctFraction)
    }

    @Test
    fun `a bulky stable field is suggested as a column`() {
        withSchema { schema ->
            val columns = rankIndexCandidates(schema, IndexCandidateOptions.DEFAULT)
                .filter { it.kind == IndexKind.SHREDDED_COLUMN }
                .map { it.path.toString() }
            assertTrue("$.description" in columns, "it carries a large share of the bytes: $columns")
            assertTrue("$.active" !in columns, "a boolean is not worth a column of its own")
        }
    }

    @Test
    fun `every candidate says what it is based on`() {
        withSchema { schema ->
            for (candidate in rankIndexCandidates(schema, IndexCandidateOptions.DEFAULT)) {
                assertTrue(candidate.reason.isNotBlank(), "$candidate")
                assertTrue(candidate.score in 0.0..1.0, "$candidate")
            }
        }
    }

    @Test
    fun `the ranking is stable and bounded`() {
        withSchema { schema ->
            val options = IndexCandidateOptions(limit = 3)
            val first = rankIndexCandidates(schema, options)
            assertEquals(3, first.size)
            assertEquals(first.map { it.path to it.kind }, rankIndexCandidates(schema, options).map { it.path to it.kind })
            assertEquals(first.map { it.score }.sortedDescending(), first.map { it.score })
        }
    }

    private fun withSchema(body: (InferredSchema) -> Unit) {
        val directory = scratch(root, "candidates")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            store.load(corpus())
            store.compact()
            catalog.attach(store)
            body(catalog.inferSchema())
        }
    }

    /**
     * One good path, three bad ones, and a bulky one.
     *
     * - `team` — always present, one type, twelve values. The shape an inverted index wants.
     * - `id` — always present, unique. Also a good index, and deliberately so.
     * - `rare` — present in 5% of documents.
     * - `constant` — always present, always the same value.
     * - `wobbly` — a string half the time and an integer the rest.
     * - `description` — always present and large, so shredding it avoids reading the documents.
     */
    private fun corpus(size: Int = 400): List<Variant> = List(size) { index ->
        Variant.fromJson(
            buildString {
                append("""{"id":$index,"team":"team-${index % 12}","constant":"same"""")
                append(""","active":${index % 2 == 0}""")
                append(""","wobbly":${if (index % 2 == 0) "$index" else "\"w$index\""}""")
                if (index % 20 == 0) append(""","rare":"rare-$index"""")
                append(""","description":"${"lorem ipsum dolor sit amet ".repeat(8)}$index"""")
                append(""","profile":{"city":"city-${index % 23}"}""")
                append('}')
            },
        )
    }
}
