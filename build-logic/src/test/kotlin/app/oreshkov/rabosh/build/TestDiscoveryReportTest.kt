package app.oreshkov.rabosh.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The decision that fails a build for having executed no tests, tested.
 *
 * Every state is a string here rather than a directory on disk, which is what [TestDiscoveryReport]
 * being pure buys: "the results directory does not exist" and "it exists and is empty" are two
 * different failures with two different fixes, and neither needs a filesystem to arrange.
 */
class TestDiscoveryReportTest {

    private fun suiteXml(name: String, tests: Int, skipped: Int = 0): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<testsuite name="$name" tests="$tests" skipped="$skipped" failures="0" errors="0" time="0.1">""" +
            """<testcase name="a()" classname="$name" time="0.01"/></testsuite>"""

    @Test
    @DisplayName("a suite reports the tests that actually ran")
    fun executedIsTestsMinusSkipped() {
        val suite = TestDiscoveryReport.parseSuite(suiteXml("ATest", tests = 7, skipped = 2))
        assertEquals(TestDiscoveryReport.Suite(tests = 7, skipped = 2), suite)
        assertEquals(5, suite!!.executed)
    }

    @Test
    @DisplayName("an absent `skipped` reads as none, and an absent `tests` does not read as zero")
    fun absentAttributes() {
        assertEquals(
            TestDiscoveryReport.Suite(tests = 3, skipped = 0),
            TestDiscoveryReport.parseSuite("""<testsuite name="A" tests="3" failures="0"/>"""),
        )
        // The direction that matters: a document this cannot read must not be counted as a module
        // that ran nothing, because that is the reading under which the check passes wrongly.
        assertNull(TestDiscoveryReport.parseSuite("""<testsuite name="A" failures="0"/>"""))
        assertNull(TestDiscoveryReport.parseSuite("<results><run/></results>"))
    }

    @Test
    @DisplayName("a <testsuites> wrapper is not mistaken for a suite")
    fun suitesWrapper() {
        assertNull(TestDiscoveryReport.parseSuite("""<testsuites tests="9"></testsuites>"""))
    }

    @Test
    @DisplayName("an unescaped angle bracket in a display name does not make a document unreadable")
    fun angleBracketInDisplayName() {
        // XML permits a raw `>` inside an attribute value, and Kotlin's backtick test names invite
        // one. Bounding the attribute scan at the element's closing `>` would read this as damaged.
        val xml = """<testsuite name="A" tests="4" skipped="1">""" +
            """<testcase name="a &lt; b is not a > b()" classname="A"/></testsuite>"""
        assertEquals(TestDiscoveryReport.Suite(tests = 4, skipped = 1), TestDiscoveryReport.parseSuite(xml))
    }

    @Test
    @DisplayName("a task that ran tests has no problems")
    fun ranTests() {
        val problems = TestDiscoveryReport.problems(
            taskPath = ":rabosh-core:test",
            resultsDirPath = "build/test-results/test",
            documents = listOf(
                "TEST-ATest.xml" to suiteXml("ATest", tests = 7),
                // One module may hold a wholly skipped class — a tagged suite that is off by
                // default — and that must not fail while another class in it ran.
                "TEST-BTest.xml" to suiteXml("BTest", tests = 2, skipped = 2),
            ),
        )
        assertTrue(problems.isEmpty(), problems.toString())
    }

    @Test
    @DisplayName("a missing results directory is a failure, and so is an empty one")
    fun nothingWritten() {
        val missing = TestDiscoveryReport.problems(":rabosh-core:test", "build/test-results/test", null)
        assertEquals(1, missing.size)
        assertTrue(missing.single().contains("no test results"), missing.toString())

        val empty = TestDiscoveryReport.problems(":rabosh-core:test", "build/test-results/test", emptyList())
        assertEquals(1, empty.size)
        assertTrue(empty.single().contains("no test result documents"), empty.toString())
    }

    @Test
    @DisplayName("a module whose every test was skipped is a module nothing is checking")
    fun everythingSkipped() {
        val problems = TestDiscoveryReport.problems(
            taskPath = ":rabosh-index:test",
            resultsDirPath = "build/test-results/test",
            documents = listOf("TEST-ATest.xml" to suiteXml("ATest", tests = 4, skipped = 4)),
        )
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("every one of them skipped"), problems.toString())
    }

    @Test
    @DisplayName("an unreadable document is reported as itself rather than as an empty module")
    fun unreadableDocument() {
        val problems = TestDiscoveryReport.problems(
            taskPath = ":rabosh-query:test",
            resultsDirPath = "build/test-results/test",
            documents = listOf("TEST-ATest.xml" to "<nothing/>"),
        )
        // One problem, and it names the format rather than claiming the module ran nothing: the two
        // have different fixes, and reporting the wrong one sends somebody to the wrong file.
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("TEST-ATest.xml"), problems.toString())
        assertTrue(problems.single().contains("<testsuite>"), problems.toString())
    }
}
