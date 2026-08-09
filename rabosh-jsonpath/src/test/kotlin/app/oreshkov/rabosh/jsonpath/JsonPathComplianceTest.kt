package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RFC 9535, checked against cases this repository did not write.
 *
 * **The corpus is asserted before any case runs.** A conformance suite that silently loses its
 * fixtures passes over an empty corpus, which is the defect `testing.md` names twice — an `include`
 * pattern selecting nothing, a benchmark that never started. So the first test here pins the shape of
 * the suite: 703 cases, 247 of them invalid selectors, 667 Normalized Paths, and exactly 56 cases
 * tagged for a regular expression. Every count below is derived from those, never remembered
 * separately.
 *
 * **Nothing is excluded, and the number that used to be is still asserted.** The 56 cases tagged for
 * a regular expression ran with an exclusion and a counted refusal until the I-Regexp matcher landed;
 * the tag is still a fact about the corpus and is still pinned, but it no longer selects anything out.
 * 703 cases run. That number is the claim, and it is what lets the README say "RFC 9535" with nothing
 * after it.
 */
class JsonPathComplianceTest {

    @Test
    fun `the vendored suite is the corpus these tests believe it is`() {
        val cases = ComplianceSuite.cases
        assertEquals(TOTAL_CASES, cases.size, "the vendored suite is not the commit these counts came from")
        assertEquals(
            ComplianceSuite.FILES.size,
            cases.map { it.file }.distinct().size,
            "a fixture failed to load, so its cases are silently absent",
        )
        assertEquals(INVALID_CASES, cases.count { it.isInvalid }, "invalid-selector cases")
        assertEquals(REGEX_CASES, cases.count { it.needsRegex }, "cases needing a regular-expression matcher")
        assertEquals(
            RESULT_PATH_STRINGS,
            cases.sumOf { it.declaredResultPaths.size },
            "Normalized Paths carried by the suite",
        )
        assertEquals(
            DISTINCT_RESULT_PATHS,
            cases.flatMap { it.declaredResultPaths }.distinct().size,
            "distinct Normalized Paths carried by the suite",
        )
        for (case in cases) {
            assertTrue(case.isInvalid || case.hasResults, "$case: a valid case must say what it selects")
        }
    }

    @Test
    fun `every invalid selector is rejected, and says where`() {
        var checked = 0
        for (case in ComplianceSuite.cases) {
            if (!case.isInvalid) continue
            val failure = runCatching { JsonPathQuery.compile(case.selector) }.exceptionOrNull()
                ?: fail("$case: '${case.selector}' compiled, and the suite says it is invalid")
            assertTrue(
                failure is IllegalArgumentException,
                "$case: expected an IllegalArgumentException, got ${failure::class.simpleName}",
            )
            assertTrue(
                "at position " in failure.message.orEmpty(),
                "$case: a compile failure must carry a position, and this one says '${failure.message}'",
            )
            checked++
        }
        assertEquals(INVALID_CASES, checked, "invalid cases checked")
    }

    @Test
    fun `every valid selector selects the nodes the suite pairs with it`() {
        var checked = 0
        for (case in ComplianceSuite.cases) {
            if (case.isInvalid) continue
            val document = Variant.fromJson(
                case.document ?: fail("$case: a valid case must carry a document"),
            )
            val nodes = JsonPathQuery.compile(case.selector).nodesIn(document)
            val values = nodes.map { JsonCanonical.of(it.value.toJsonString()) }
            val paths = nodes.map { it.location.toNormalizedPath() }

            if (case.deterministic) {
                assertEquals(
                    case.results.single().map(JsonCanonical::of),
                    values,
                    "$case: '${case.selector}' selected the wrong values from ${case.document}",
                )
                assertEquals(
                    case.resultPaths.single(),
                    paths,
                    "$case: '${case.selector}' selected the wrong locations from ${case.document}",
                )
            } else {
                assertAnyAlternative(case, values, paths)
            }
            checked++
        }
        assertEquals(VALID_CASES, checked, "valid cases checked")
    }

    /**
     * Every `result_paths` entry round-trips character for character, and selects what it is paired
     * with.
     *
     * This is the assertion `rabosh-variant`'s `JsonPathCtsTest` makes over two of the fifteen
     * fixtures; here it runs over all of them, and over cases the query engine does not — the regex
     * exclusion is about *selectors*, and §2.7 is not a selector. 667 paths rather than 40.
     */
    @Test
    fun `every normalized path in the suite round-trips and selects its value`() {
        var roundTripped = 0
        var selected = 0
        for (case in ComplianceSuite.cases) {
            val paths = case.declaredResultPaths
            for (path in paths) {
                assertEquals(
                    path,
                    VariantPath.parseNormalized(path).toNormalizedPath(),
                    "$case: a normalized path did not survive being parsed and rendered",
                )
                roundTripped++
            }
            val documentText = case.document ?: continue
            if (!case.deterministic || paths.isEmpty()) continue
            val document = Variant.fromJson(documentText)
            val values = case.results.single()
            assertEquals(paths.size, values.size, "$case: the suite pairs each result with a path")
            for ((index, path) in paths.withIndex()) {
                val node = document.select(VariantPath.parseNormalized(path))
                    ?: fail("$case: $path selected nothing in $documentText")
                assertEquals(
                    JsonCanonical.of(values[index]),
                    JsonCanonical.of(node.toJsonString()),
                    "$case: at $path",
                )
                selected++
            }
        }
        assertEquals(RESULT_PATH_STRINGS, roundTripped, "normalized paths round-tripped")
        assertTrue(selected > 0, "no node was checked; the fixtures did not load")
    }

    /**
     * The cases that used to be excluded are answered, and are still counted.
     *
     * The mirror of the assertion this replaced, and it is kept as its own test rather than folded
     * into the two above for the reason the exclusion was counted in the first place: 56 is the
     * number that says which feature is being claimed, and a suite that stopped naming it would let a
     * regression re-open the hole with every remaining count still passing. The 50 valid ones must
     * compile *and* be checked by the evaluation test; the 6 invalid ones must still be rejected.
     */
    @Test
    fun `the cases that needed a regular expression are answered rather than refused`() {
        var valid = 0
        for (case in ComplianceSuite.cases) {
            if (!case.needsRegex) continue
            val failure = runCatching { JsonPathQuery.compile(case.selector) }.exceptionOrNull()
            if (case.isInvalid) {
                assertTrue(failure is IllegalArgumentException, "$case: '${case.selector}' must still be rejected")
                continue
            }
            assertTrue(failure == null, "$case: '${case.selector}' was refused — ${failure?.message}")
            valid++
        }
        assertEquals(REGEX_CASES - REGEX_INVALID_CASES, valid, "regular-expression cases compiled")
        assertEquals(
            TOTAL_CASES,
            ComplianceSuite.cases.size,
            "cases that ran; this is the number the conformance claim is made at",
        )
    }

    /**
     * A case whose answer depends on object member ordering, compared as a set.
     *
     * RFC 9535 leaves the order of an object's members to the implementation — this engine presents
     * them in name order, because that is what the encoding stores — so the suite lists every
     * permitted nodelist. Matching *any* of them is the whole obligation, and the pairing of each
     * value with its own location is preserved rather than compared separately.
     */
    private fun assertAnyAlternative(case: ComplianceSuite.Case, values: List<String>, paths: List<String>) {
        val actual = pairUp(paths, values)
        val alternatives = case.resultPaths.zip(case.results) { alternativePaths, alternativeValues ->
            pairUp(alternativePaths, alternativeValues.map(JsonCanonical::of))
        }
        assertTrue(
            alternatives.any { it == actual },
            "$case: '${case.selector}' over ${case.document} selected $actual, " +
                "which is none of the permitted nodelists $alternatives",
        )
    }

    /** Each node as one comparable string, sorted — a multiset that keeps location and value together. */
    private fun pairUp(paths: List<String>, values: List<String>): List<String> =
        paths.zip(values) { path, value -> "$path = $value" }.sorted()

    private companion object {
        /**
         * The suite's own shape, at commit `7be7c1fc28057c91e8eefaf197060fba7ed43acd`.
         *
         * Every other count in this file is derived from these four, so a fixture that changed fails
         * the shape test above rather than quietly redefining what is expected — the same rule the
         * Roaring fixtures follow, where the value set is built from the specification's recipe.
         */
        const val TOTAL_CASES = 703
        const val INVALID_CASES = 247
        const val REGEX_CASES = 56
        const val RESULT_PATH_STRINGS = 667

        /** How many of the 56 are invalid *selectors* — an arity error rather than a pattern. */
        const val REGEX_INVALID_CASES = 6

        /**
         * Distinct by **ordinal** string comparison, which is the only comparison a Normalized Path
         * admits: one location has exactly one spelling, so two paths differing in any code unit are
         * two locations. A culture-sensitive count answers 56 here, and that is the wrong question
         * rather than a smaller answer to this one.
         */
        const val DISTINCT_RESULT_PATHS = 57

        /** 703 total, less the 247 invalid ones. */
        const val VALID_CASES = TOTAL_CASES - INVALID_CASES
    }
}
