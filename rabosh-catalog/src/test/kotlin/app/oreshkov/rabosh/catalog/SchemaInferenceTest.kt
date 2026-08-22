package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import app.oreshkov.rabosh.variant.VariantPath
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The phase's other acceptance criterion: **the inferred schema matches ground truth.**
 *
 * "Matches" is asserted **exactly**, and it can be because the corpus is built so that the two
 * approximations the design admits to do not apply: every key is written once, and the store is
 * compacted. Under those conditions there is one live version of every document and the fold over
 * segments is a count rather than an estimate. Asserting exactness on a corpus with overwrites would
 * be testing a claim the design does not make — see [InferredSchema].
 */
class SchemaInferenceTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `presence, types and nullability match the corpus exactly`() {
        val documents = corpus()
        withCatalog { store, catalog ->
            store.load(documents.map { Variant.fromJson(it) })
            store.compact()
            catalog.attach(store)
            val schema = catalog.inferSchema()

            assertTrue(schema.coverage.isComplete)
            assertEquals(documents.size.toLong(), schema.documentCount)

            // `id` is in every document and is a distinct integer in each: a unique key.
            val id = schema["$.id"]!!
            assertEquals(documents.size.toLong(), id.observations)
            assertEquals(1.0, id.presence)
            assertEquals(mapOf(VariantKind.INTEGER to documents.size.toLong()), id.types)
            // Counts are exact; the *cardinality* is the one number that is an estimate above the
            // exact range, and the schema says so through `distinctIsExact` rather than pretending.
            assertTrue(!id.distinctIsExact, "400 values is past the exact range")
            assertTrue(withinEstimateBound(id.distinctEstimate, documents.size), "${id.distinctEstimate}")
            val idRange = id.bounds.numeric!!
            assertEquals("0", idRange.min.toPlainString())
            assertEquals("${documents.size - 1}", idRange.max.toPlainString())

            // `team` is in every document and takes one of seven values: the shape an index wants.
            val team = schema["$.team"]!!
            assertEquals(1.0, team.presence)
            assertEquals(7, team.distinctEstimate)
            assertTrue(team.distinctIsExact, "seven values is well inside the exact range")
            assertEquals(1.0, team.typeStability)
            assertEquals(VariantKind.STRING, team.dominantType)

            // `note` is present in half the documents and null in half of those.
            val note = schema["$.note"]!!
            assertEquals(documents.size / 2L, note.observations)
            assertEquals(0.5, note.presence)
            assertEquals(documents.size / 4L, note.sketch.nullObservations, "null in half of those")
            assertEquals(0.5, note.nullFraction, 1e-9)

            // `legacy` is a string in most documents and an integer in a few: not type-stable.
            val legacy = schema["$.legacy"]!!
            assertTrue(legacy.typeStability < 1.0 && legacy.typeStability > 0.5)
            assertEquals(VariantKind.STRING, legacy.dominantType)

            // An array's elements are counted per element, which is the honest number for an array.
            assertEquals(documents.size * 2L, schema["$.tags[*]"]!!.observations)
            assertEquals(2.0, schema["$.tags[*]"]!!.presence)

            assertNull(schema["$.absent"], "a path nothing has is not invented")
            assertTrue(!schema.isTruncated, "the corpus fits the path budget")
        }
    }

    @Test
    fun `every path the corpus contains is in the schema and nothing else is`() {
        val documents = corpus()
        withCatalog { store, catalog ->
            store.load(documents.map { Variant.fromJson(it) })
            store.compact()
            catalog.attach(store)

            assertEquals(
                setOf(
                    "$", "$.id", "$.team", "$.active", "$.score", "$.note", "$.legacy",
                    "$.tags", "$.tags[*]", "$.profile", "$.profile.city", "$.profile.zip",
                ),
                catalog.inferSchema().fields.mapTo(HashSet()) { it.path.toString() },
            )
        }
    }

    @Test
    fun `the estimate stays close once the corpus outgrows exact counting`() {
        withCatalog { store, catalog ->
            val size = 10_000
            store.load(List(size) { Variant.fromJson("""{"id":$it,"bucket":${it % 500}}""") })
            store.compact()
            catalog.attach(store)
            val schema = catalog.inferSchema()

            val bucket = schema["$.bucket"]!!
            assertTrue(withinEstimateBound(bucket.distinctEstimate, 500), "${bucket.distinctEstimate}")
            val id = schema["$.id"]!!
            assertTrue(!id.distinctIsExact)
            assertTrue(withinEstimateBound(id.distinctEstimate, size), "${id.distinctEstimate} for $size")
        }
    }

    @Test
    fun `documents still in the memtable are not counted`() {
        // Stated in the KDoc and asserted here: the catalog covers segments, and a caller who wants
        // everything flushes first. Reporting uncommitted documents as if they were segmented is the
        // failure this rules out.
        withCatalog { store, catalog ->
            store.load(List(50) { Variant.fromJson("""{"id":$it}""") })
            catalog.attach(store)
            assertEquals(50, catalog.inferSchema().documentCount)

            for (index in 50 until 90) store.put(keyFor(index), Variant.fromJson("""{"id":$index}"""))
            assertEquals(50, catalog.inferSchema().documentCount, "the memtable is not in the model")

            store.flush()
            assertEquals(90, catalog.inferSchema().documentCount, "the flush put it there")
        }
    }

    /**
     * A corpus whose shape is known by construction.
     *
     * Every key is written once and nothing is overwritten, so after a compaction the counts are
     * exact rather than approximate. Deliberate irregularities: a field present in half the
     * documents, a field that is null in half of *those*, and a field whose type is not stable.
     */
    private fun corpus(size: Int = 400): List<String> = List(size) { index ->
        buildString {
            append("""{"id":$index,"team":"team-${index % 7}","active":${index % 2 == 0}""")
            append(""","score":${index % 20}.25""")
            if (index % 2 == 0) append(""","note":${if (index % 4 == 0) "null" else "\"note-$index\""}""")
            append(""","legacy":${if (index % 10 == 0) "$index" else "\"v$index\""}""")
            append(""","tags":["tag-${index % 5}","common"]""")
            append(""","profile":{"city":"city-${index % 23}","zip":"${10000 + index % 900}"}""")
            append('}')
        }
    }

    /**
     * Whether a cardinality estimate is within three standard errors of the truth.
     *
     * Above [HyperLogLog.SPARSE_LIMIT] the count stops being exact and starts being an estimate, and
     * a test that asserted equality there would be asserting something the design does not claim.
     * Three standard errors is the bound a deterministic test can hold; a regression that mattered
     * misses it by an order of magnitude.
     */
    private fun withinEstimateBound(estimate: Long, truth: Int): Boolean =
        abs(estimate - truth).toDouble() / truth <= 3 * 1.04 / kotlin.math.sqrt(1024.0)

    /**
     * **No model, over any corpus, ever contains a `..`** — the invariant `CatalogStep.AnyDescendant`
     * was gated on, and the reason it could join this type instead of forcing a third one.
     *
     * `CatalogPath` exists because `VariantPath` may not have a wildcard, and by that precedent a
     * step the *data* cannot produce should have been a new type again. What decides otherwise is
     * that the two steps are different kinds of thing and the difference is checkable here:
     * `AnyElement` **collapses locations a document has**, so a sketch emits it; `AnyDescendant` is a
     * **pattern a caller wrote**, so nothing that reads documents can produce one. A sketch key
     * carrying one would mean the model had started describing a query, and the type discipline
     * would be a comment rather than a fact.
     *
     * Asserted over generated documents rather than a fixture, because the claim is *over any
     * corpus* and a hand-written corpus is the one shape it is guaranteed to hold for.
     */
    @Test
    fun `no sketch over any corpus emits a descendant step`() {
        forAll(Gen.list(JsonGens.document(), sizes = 0..6)) { corpus ->
            val builder = SegmentSketchBuilder(CatalogOptions.DEFAULT)
            for (document in corpus) builder.add(jsonDocument(document))
            for (path in builder.build().paths) {
                assertTrue(
                    path.steps.none { it === CatalogStep.AnyDescendant },
                    "a sketch emitted '$path', which no walk of a document can produce",
                )
            }
        }
    }

    /**
     * The other half of the discipline, and the one a caller meets: a shape may say `..`, a
     * projection may not.
     *
     * A projection reads *one* location out of a row, which is `VariantPath`'s contract and the
     * reason the two types are separate at all. Nothing changed there — `VariantPath.parse` has no
     * `..` and never will — and this asserts it rather than leaving the absence to be noticed.
     */
    @Test
    fun `a descendant is a shape and has no location spelling`() {
        assertEquals(
            listOf(CatalogStep.AnyDescendant, CatalogStep.Field("@type")),
            CatalogPath.parse("""$..["@type"]""").steps,
        )
        assertFailsWith<IllegalArgumentException> { VariantPath.parse("""$..["@type"]""") }
        assertNull(VariantPath.parseJsonPathOrNull("""$..["@type"]"""))
    }

    private fun withCatalog(body: (DocumentStore, SchemaCatalog) -> Unit) {
        val directory = scratch(root, "inference")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            body(store, catalog)
        }
    }
}
