package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import app.oreshkov.rabosh.variant.toJsonString
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims `JsonPathQuery` makes about itself, as opposed to the ones RFC 9535 makes.
 *
 * The compliance suite says the answers are right. These say the *object* behaves as its
 * documentation promises: the two ways to walk agree, one compiled query serves many documents from
 * many threads, and neither the depth of a document nor the breadth of an array is a limit — which
 * are exactly the three things a caller would otherwise have to take on trust.
 */
class JsonPathQueryTest {

    @Test
    fun `the sink and the list report the same nodes`() {
        val document = Variant.fromJson("""{"items":[{"sku":"a"},{"sku":"b"},{"sku":"c"}]}""")
        val query = JsonPathQuery.compile("$..sku")

        val streamed = buildList { query.forEachNodeIn(document) { add(it.location.toNormalizedPath()) } }
        assertContentEquals(query.nodesIn(document).map { it.location.toNormalizedPath() }, streamed)
        assertEquals(3, streamed.size, "the fixture must produce more than one node")
    }

    /**
     * The README's snippet, executed — and the correlation it exists to demonstrate.
     *
     * A documented example is a test. The `db.query(…)` around this one is phase 20's snippet and is
     * already run by `ProtobufJsonTest`; what is new is the query and the line it prints, so that is
     * what is pinned here. The fixture is arranged so the assertion means something: element 1
     * carries the sku with the wrong quantity and element 2 the quantity with the wrong sku, which is
     * exactly the document an **uncorrelated** conjunction matches. Selecting element 3 alone is the
     * difference the README claims.
     */
    @Test
    fun `the README's snippet selects the element where both conditions hold`() {
        val document = Variant.fromJson(
            """
            {"items":[{"sku":"x","qty":1},{"sku":"ABC-123","qty":1},
                      {"sku":"y","qty":5},{"sku":"ABC-123","qty":5}]}
            """.trimIndent(),
        )
        val correlated = JsonPathQuery.compile("$.items[?@.sku == 'ABC-123' && @.qty == 5]")

        val printed = buildList { correlated.forEachNodeIn(document) { add(it.toJsonSummaryString()) } }
        assertContentEquals(listOf("""$['items'][3] {"qty":5,"sku":"ABC-123"}"""), printed)
    }

    @Test
    fun `a query renders as it was written`() {
        val text = "$.store.book[?@.price < 10].title"
        assertEquals(text, JsonPathQuery.compile(text).toString())
    }

    /**
     * **The descendant walk is iterative, and this is the fixture that says so.**
     *
     * `DEFAULT_MAX_JSON_DEPTH` bounds what `JsonParser` will ingest, but a `Variant` assembled
     * through `VariantBuilder` is never re-checked against it — so a document this deep is reachable
     * through the public API and a recursive walk would meet it as a `StackOverflowError`. The
     * document is therefore *built* rather than parsed, deliberately, and it is far deeper than any
     * JSON text this engine would accept.
     */
    @Test
    fun `a document deeper than ingest allows is walked without recursion`() {
        val document = nest(DEEP)
        val nodes = JsonPathQuery.compile("$..leaf").nodesIn(document)

        assertEquals(1, nodes.size, "the leaf is at exactly one location")
        assertEquals(DEEP + 1, nodes.single().location.steps.size, "the location must name every level")
        assertEquals("7", nodes.single().value.toJsonString())
    }

    /**
     * The walk carries no breadth budget, which is the other half of the same rule.
     *
     * `TermExtractor` stops at `IndexOptions.maxChildren`, and an expander that inherited *any* such
     * bound would return fewer nodes than the index matched — a caller who narrowed by the index and
     * then expanded would find nothing, with nothing anywhere to say so. Five thousand elements is
     * above every budget in the engine.
     */
    @Test
    fun `a wide array is reported entirely`() {
        val elements = (0 until WIDE).joinToString(",") { """{"sku":"s$it"}""" }
        val document = Variant.fromJson("""{"items":[$elements]}""")

        assertEquals(WIDE, JsonPathQuery.compile("$.items[*].sku").nodesIn(document).size)
        assertEquals(WIDE, JsonPathQuery.compile("$..sku").nodesIn(document).size)
        assertEquals(1, JsonPathQuery.compile("$.items[?@.sku == 's4999']").nodesIn(document).size)
    }

    /**
     * One compiled query, many threads.
     *
     * Worth asserting rather than reasoning about: `DocumentMatcher` one module over is explicitly
     * *not* thread-safe, so a reader who knows this codebase will ask. The answer is that a compiled
     * query holds no evaluation state at all — the walk's stack, its locations and its comparisons
     * are per call.
     *
     * **The I-Regexp matcher is in the fixture on purpose.** It is the one part of the module with a
     * loop that could have broken this: a compiled pattern is shared by every thread, and its thread
     * lists and visit marks are allocated *per run* rather than held on the program. A one-slot memo
     * for a document-supplied pattern was considered and declined, and this is the assertion that
     * would fail if one were added carelessly.
     */
    @Test
    fun `one compiled query serves many threads`() {
        val documents = List(THREADS) { index ->
            Variant.fromJson("""{"items":[{"n":$index,"s":"a$index"},{"n":${index + 1},"s":"b$index"}]}""")
        }
        val query = JsonPathQuery.compile("$.items[?@.n > 0 && match(@.s, '[ab][0-9]')].n")
        val expected = documents.map { document -> query.nodesIn(document).map { it.value.toJsonString() } }
        assertTrue(expected.any { it.isNotEmpty() }, "the fixture must select something")

        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = documents.mapIndexed { index, document ->
                pool.submit<List<String>> {
                    repeat(REPEATS) { query.nodesIn(documents[index]) }
                    query.nodesIn(document).map { it.value.toJsonString() }
                }
            }.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertEquals(expected, results)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the pool did not shut down")
        }
    }

    /**
     * Values outside the JSON data model are selectable, and compare by exact identity.
     *
     * RFC 9535 has no case for a date, a uuid or a binary — the engine can store all three and a
     * query can spell a literal for none — so the only reachable comparison is one document value
     * against another. Answering "never equal" would be a silent wrong answer to a caller comparing
     * two timestamps, which is why the rule is exact identity rather than refusal. Documented on
     * `variantsEqual`; asserted here because a comparison table is the kind of thing that gets
     * "simplified" later.
     */
    @Test
    fun `a value the JSON model has no case for compares by identity`() {
        val document = VariantBuilder().apply {
            startObject()
            field("a")
            appendDate(EPOCH_DAY)
            field("b")
            appendDate(EPOCH_DAY)
            field("c")
            appendDate(EPOCH_DAY + 1)
            field("d")
            appendLong(EPOCH_DAY.toLong())
            endObject()
        }.buildVariant()

        val equal = JsonPathQuery.compile("$[?@ == $.a]").nodesIn(document)
        assertContentEquals(listOf("$['a']", "$['b']"), equal.map { it.location.toNormalizedPath() })
        assertEquals(0, JsonPathQuery.compile("$[?@ < $.c]").nodesIn(document).size, "dates are unordered")
        assertEquals(4, JsonPathQuery.compile("$[*]").nodesIn(document).size, "all four are still selectable")
    }

    /** A chain of [depth] objects with `{"leaf":7}` at the bottom. */
    private fun nest(depth: Int): Variant = VariantBuilder().apply {
        repeat(depth) {
            startObject()
            field("down")
        }
        startObject()
        field("leaf")
        appendLong(7)
        endObject()
        repeat(depth) { endObject() }
    }.buildVariant()

    private companion object {
        /** Well above `DEFAULT_MAX_JSON_DEPTH`, and above any stack a recursive walk would have. */
        const val DEEP = 20_000
        const val WIDE = 5_000
        const val THREADS = 8
        const val REPEATS = 50
        const val TIMEOUT_SECONDS = 30L
        const val EPOCH_DAY = 19_000
    }
}
