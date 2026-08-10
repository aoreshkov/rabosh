package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The evaluation budget: that it fires, that it fires on the right bound, and — the half that is
 * easy to forget — that it does not fire on anything honest.
 *
 * **A limit that changed a compliant answer would have broken the module's only claim**, so the
 * assertions here come in pairs throughout: every "this is refused" sits beside a "this is answered",
 * usually the same query against the same document under a looser bound. A budget nothing can pass is
 * as broken as a budget nothing can fail, and only one of those two shows up as a red test.
 *
 * The compliance suite is the other half and needs no help from this file: `JsonPathQuery.compile`
 * applies [JsonPathLimits.DEFAULT], so all 703 of its cases already run with the budget on.
 * `JsonPathQueryTest`'s 20 000-deep document and 5 000-wide array likewise — they are the fixtures
 * that say the defaults do not reach an honest query, and they were not touched to accommodate this.
 */
class JsonPathLimitsTest {

    // --- the bound refuses; it never truncates ---------------------------------------------------

    /**
     * The distinction the whole design rests on.
     *
     * A budget that stopped the walk and returned what it had would be indistinguishable, to a
     * caller, from a document that genuinely holds three nodes. So the failure is an exception and
     * `nodesIn` produces no list at all — asserted here rather than left to the KDoc, because it is
     * the one behaviour a future "just return what we have" refactor would quietly reverse.
     */
    @Test
    fun `exceeding a limit yields an exception rather than a short nodelist`() {
        val document = Variant.fromJson("""{"a":[1,2,3,4,5,6,7,8,9,10]}""")
        val query = JsonPathQuery.compile("$.a[*]")

        assertEquals(10, query.nodesIn(document, JsonPathLimits.NONE).size, "the honest answer")

        val refused = assertFailsWith<JsonPathLimitExceededException> {
            query.nodesIn(document, JsonPathLimits(maxNodesProduced = 4))
        }
        assertEquals(JsonPathLimit.NODES_PRODUCED, refused.limit)
        assertEquals(4, refused.allowed)
    }

    /**
     * Each bound reports itself, because a caller serving untrusted expressions has to answer
     * differently for "too many results" than for "too much work".
     */
    @Test
    fun `each limit names itself when it is the one that fires`() {
        val document = nest(64)

        val visited = assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile("$..*", JsonPathLimits(maxNodesVisited = 8)).nodesIn(document)
        }
        assertEquals(JsonPathLimit.NODES_VISITED, visited.limit)

        val produced = assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile("$..*", JsonPathLimits(maxNodesProduced = 8)).nodesIn(document)
        }
        assertEquals(JsonPathLimit.NODES_PRODUCED, produced.limit)

        val depth = assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile("$..*", JsonPathLimits(maxDescendantDepth = 8)).nodesIn(document)
        }
        assertEquals(JsonPathLimit.DESCENDANT_DEPTH, depth.limit)

        // The pairing that stops all three being satisfied by the fixture simply being large: with
        // the bounds off, the same query over the same document answers, and answers correctly.
        // 64 nested `down` values plus the one `leaf` value.
        assertEquals(65, JsonPathQuery.compile("$..*", JsonPathLimits.NONE).nodesIn(document).size)
    }

    /**
     * Depth is counted from where the expansion started, not from the root of the document.
     *
     * `$.a.b..*` descends two levels before the `..` begins, and a bound that charged those two to
     * the descendant budget would make the same expansion cost different amounts depending on how
     * deeply the segment before it had already reached — which is a bound on the document rather
     * than on the walk.
     */
    @Test
    fun `descendant depth is measured below the segment that starts it`() {
        val document = Variant.fromJson("""{"a":{"b":{"c":{"d":{"e":1}}}}}""")

        // From `$`, `..` reaches `e` five levels down; from `$.a.b`, three.
        assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile("$..*", JsonPathLimits(maxDescendantDepth = 3)).nodesIn(document)
        }
        assertEquals(
            3,
            JsonPathQuery.compile("$.a.b..*", JsonPathLimits(maxDescendantDepth = 3)).nodesIn(document).size,
            "the same bound, the same document, a shallower expansion",
        )
    }

    // --- the attack the item exists for ----------------------------------------------------------

    /**
     * **A quadratic expression over a deep document is refused, under the shipped defaults.**
     *
     * `$..*..nope` is fourteen characters. The first expansion yields every node; the second walks
     * the whole subtree under each of them, so over a chain of *d* nodes the work is `d²/2` — for
     * the fixture below, upwards of twelve million node-touches against a default of ten million.
     * This is the shape case F exists to survive: the expression comes from someone you do not
     * trust and the document is yours.
     *
     * **The second selector names a field the document does not have, and that is the point rather
     * than a convenience.** This query's answer is the *empty nodelist* — it returns nothing, reads
     * nothing back to the caller, and is by its result indistinguishable from `$.absent`. So neither
     * [JsonPathLimits.maxNodesProduced] nor anything else measured on the answer can see it coming,
     * and only a bound on the *work* can. `$..*..*` is quadratic too and is caught by the produced
     * bound first, which is correct behaviour and would have been the wrong fixture: it would leave
     * `maxNodesVisited` unexercised by the one case it exists for.
     *
     * Asserted with the **defaults**, deliberately, rather than with a bound chosen to make it fail.
     * A limit nobody's defaults reach is a feature nobody has.
     */
    @Test
    fun `a quadratic descendant expansion is refused by the defaults`() {
        val document = nest(QUADRATIC_DEPTH)

        var produced = 0
        val refused = assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile("$..*..nope").forEachNodeIn(document) { produced++ }
        }
        assertEquals(JsonPathLimit.NODES_VISITED, refused.limit)
        assertEquals(JsonPathLimits.DEFAULT_MAX_NODES_VISITED, refused.allowed)
        assertEquals(0, produced, "the expression returns nothing at all, which is what hides it")

        // And the other direction, on the same document: a linear walk of it is not refused, so what
        // the defaults caught is the *expression* rather than the fixture being big.
        assertEquals(1, JsonPathQuery.compile("$..leaf").nodesIn(document).size)
    }

    /**
     * A filter applied to every node of a descendant expansion is the same attack wearing a
     * different selector, and the budget has to see the candidates it rejects.
     *
     * A filter that tests five thousand elements and selects none has done five thousand elements'
     * worth of work; counting only what a filter *selects* would leave `$..[?@.nope]` free.
     */
    @Test
    fun `a filter is charged for the candidates it rejects`() {
        val elements = (0 until 500).joinToString(",") { """{"sku":"s$it"}""" }
        val document = Variant.fromJson("""{"items":[$elements]}""")
        val query = "$.items[?@.sku == 'nothing-matches-this']"

        assertEquals(0, JsonPathQuery.compile(query, JsonPathLimits.NONE).nodesIn(document).size)

        val refused = assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile(query, JsonPathLimits(maxNodesVisited = 100)).nodesIn(document)
        }
        assertEquals(JsonPathLimit.NODES_VISITED, refused.limit)
    }

    /** The sub-walk a filter runs is inside the same budget, or `$[?@..x]` buys an unbounded walk per candidate. */
    @Test
    fun `work done inside a filter counts against the same budget`() {
        val document = Variant.fromJson("""{"items":[{"deep":{"a":{"b":{"c":1}}}},{"deep":{"a":{"b":{"c":2}}}}]}""")
        val query = "$.items[?count(@..*) > 0]"

        assertEquals(2, JsonPathQuery.compile(query, JsonPathLimits.NONE).nodesIn(document).size)
        assertFailsWith<JsonPathLimitExceededException> {
            JsonPathQuery.compile(query, JsonPathLimits(maxNodesVisited = 6)).nodesIn(document)
        }
    }

    // --- the defaults do not reach an honest query -----------------------------------------------

    /**
     * The three defaults, stated as the numbers they are.
     *
     * Pinned so that lowering one is a visible decision rather than a tuning tweak: these are the
     * only thing standing between an honest query and an exception, and a caller reading
     * `INTEGRATION.md` is told what they are.
     */
    @Test
    fun `the shipped defaults are the documented ones`() {
        val limits = JsonPathLimits.DEFAULT
        assertEquals(10_000_000L, limits.maxNodesVisited)
        assertEquals(1_000_000L, limits.maxNodesProduced)
        assertEquals(100_000, limits.maxDescendantDepth)

        assertEquals(0L, JsonPathLimits.NONE.maxNodesVisited, "NONE must actually be unbounded")
        assertTrue(JsonPathLimits.NONE.toString().contains("unbounded"))
    }

    /**
     * A document deeper than the engine will ingest is still walked under the defaults.
     *
     * The companion to `JsonPathQueryTest`'s iterative-walk fixture, from the other side: that one
     * says a deep document does not overflow the stack, and this one says the budget does not then
     * refuse it anyway. Together they are the claim that the defaults are a backstop rather than a
     * policy.
     */
    @Test
    fun `the defaults admit a document deeper than ingest allows`() {
        val document = nest(20_000)
        val nodes = JsonPathQuery.compile("$..leaf").nodesIn(document)

        assertEquals(1, nodes.size)
        assertEquals(20_001, nodes.single().location.steps.size)
    }

    // --- the counters are per call ----------------------------------------------------------------

    /**
     * One compiled query, two threads, two budgets.
     *
     * `JsonPathQuery` promises it may be applied from any number of threads at once, and a counter
     * on the query would break that *silently* — two documents sharing a budget means one of them
     * fails for the other's size, intermittently and never in a test that runs one thread. The
     * limits are shared and immutable; the counters are created per call.
     */
    @Test
    fun `two threads evaluating one query do not share a budget`() {
        val document = nest(50)
        // One evaluation of `$..*` here is 103 touches — 52 nodes popped by the descent, 51 children
        // emitted by the wildcard — and 51 nodes. The bound sits comfortably above one run and
        // comfortably below two, so a counter shared between threads is *caught* rather than merely
        // being catchable.
        val query = JsonPathQuery.compile("$..*", JsonPathLimits(maxNodesVisited = 150))

        assertEquals(51, query.nodesIn(document).size, "one evaluation must fit inside the bound")

        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val results = (0 until THREADS).map { pool.submit<Int> { query.nodesIn(document).size } }
            for (result in results) assertEquals(51, result.get(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
    }

    private companion object {
        /**
         * Deep enough that `$..*..*` costs more than [JsonPathLimits.DEFAULT_MAX_NODES_VISITED].
         *
         * `d²/2` at 5 000 is 12.5 million against a default of 10 million — over it, and not by so
         * much that the fixture would still fail if the counting were made twice as coarse.
         */
        const val QUADRATIC_DEPTH = 5_000
        const val THREADS = 4

        /** `{"down":{"down":{…{"leaf":7}}}}`, [depth] levels deep. Built, not parsed: the parser would refuse it. */
        fun nest(depth: Int): Variant = VariantBuilder().apply {
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
    }
}
