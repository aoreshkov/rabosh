package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The ordinal accessors, against the key-shaped surface they were extracted from.
 *
 * They exist so a planner can intersect two indexes over one segment **before** any ordinal is decoded
 * to a key, which is what makes `a = x AND b in (1, 2)` cheaper than two answers merged. The claim
 * that makes that sound is that both indexes over a segment share that segment's one ordinal space,
 * and the only way to hold it is to check the ordinals against the keys the older surface reports.
 *
 * The last test is the sharp one. An accessor asked about a segment this reader cannot answer for
 * **throws**, and must never return an empty bitmap: an empty answer is indistinguishable from "no
 * matches here", which is an index changing a query's answer rather than its speed.
 */
class IndexOrdinalAccessorTest {

    private fun document(index: Int) = jsonDocument(
        """{"team":"team-${index % 7}","score":${index % 50},"tags":["t${index % 5}","t${index % 3}"]""" +
            (if (index % 11 == 0) "" else ""","note":${if (index % 13 == 0) "null" else "\"n$index\""}""") +
            "}",
    )

    @Test
    fun `candidate ordinals decode to exactly the keys the cursor reports`(@TempDir root: Path) {
        withIndex(root) { store, catalog, handle ->
            store.snapshot().use { snapshot ->
                catalog.read(store, handle, snapshot).use { reader ->
                    assertTrue(reader.usableSegments.size > 1, "the fixture should span several segments")
                    for (team in 0 until 7) {
                        val term = IndexTerm.ofString("team-$team")
                        assertContentEquals(
                            reader.candidates(term).toKeyList(),
                            decode(reader) { reader.candidateOrdinals(it, term) },
                            "team-$team",
                        )
                    }
                    val pair = listOf(IndexTerm.ofString("team-1"), IndexTerm.ofString("team-4"))
                    assertContentEquals(
                        reader.candidates(pair).toKeyList(),
                        decode(reader) { reader.candidateOrdinals(it, pair) },
                        "IN",
                    )
                    assertContentEquals(
                        reader.existing().toKeyList(),
                        decode(reader, reader::presentOrdinals),
                        "EXISTS",
                    )
                    assertContentEquals(
                        reader.absent().toKeyList(),
                        decode(reader, reader::absentOrdinals),
                        "NOT EXISTS",
                    )
                }
            }
        }
    }

    /**
     * The complement is taken over live documents, not over every ordinal. A tombstone holds an
     * ordinal and is not a document, so the wider universe would offer candidates whose recheck
     * resolves to nothing.
     */
    @Test
    fun `absent ordinals are the document universe less the present ones`(@TempDir root: Path) {
        withIndex(root, deletions = true) { store, catalog, handle ->
            store.snapshot().use { snapshot ->
                catalog.read(store, handle, snapshot).use { reader ->
                    for (segment in reader.usableSegments) {
                        val universe = reader.documentOrdinals(segment)
                        val present = reader.presentOrdinals(segment)
                        assertContentEquals(
                            universe.andNot(present).toIntArray().toList(),
                            reader.absentOrdinals(segment).toIntArray().toList(),
                            "segment $segment",
                        )
                        assertTrue(
                            universe.andNot(present).cardinality <= universe.cardinality,
                            "the complement cannot exceed its universe",
                        )
                    }
                }
            }
        }
    }

    /** Ordinals ascend with keys, so a key range is one contiguous ordinal range. */
    @Test
    fun `an ordinal range agrees with a linear walk of the keys`(@TempDir root: Path) {
        withIndex(root) { store, catalog, handle ->
            store.snapshot().use { snapshot ->
                catalog.read(store, handle, snapshot).use { reader ->
                    val segment = reader.usableSegments.first()
                    val universe = reader.documentOrdinals(segment)
                    val ordinals = reader.ordinalRange(segment, null, null)
                    val keys = ordinals.map { reader.keyAt(segment, it) }
                    assertEquals(keys.sorted(), keys, "ordinals must ascend with keys")
                    assertTrue(universe.toIntArray().all { it in ordinals }, "the range must cover every document")

                    val probes = listOf<Pair<Key?, Key?>>(
                        null to null,
                        keys.first() to keys.last(),
                        keys[keys.size / 4] to keys[keys.size / 2],
                        keys.first() to keys.first(),
                        Key.of("aaa") to Key.of("bbb"),
                        keys.last() to keys.first(),
                        Key.of("key:000000") to null,
                        null to Key.of("key:000000"),
                    )
                    for ((from, to) in probes) {
                        val expected = ordinals.filter { ordinal ->
                            val key = reader.keyAt(segment, ordinal)
                            (from == null || key >= from) && (to == null || key <= to)
                        }
                        assertContentEquals(
                            expected,
                            reader.ordinalRange(segment, from, to).toList(),
                            "range $from..$to",
                        )
                    }
                }
            }
        }
    }

    /** A key in one segment only is where a caller may skip the recheck; the bisect decides it. */
    @Test
    fun `unique keys are decided from the key blocks`(@TempDir root: Path) {
        withIndex(root) { store, catalog, handle ->
            store.put(keyFor(0), document(999))
            store.flush()
            store.snapshot().use { snapshot ->
                catalog.read(store, handle, snapshot).use { reader ->
                    val holders = reader.usableSegments.count { segment ->
                        reader.ordinalRange(segment, keyFor(0), keyFor(0)).isEmpty().not()
                    }
                    assertEquals(2, holders, "the fixture should write key 0 into two segments")
                    assertTrue(!reader.isUniqueKey(keyFor(0), reader.usableSegments.first()))
                    assertTrue(reader.isUniqueKey(keyFor(1_000_000), reader.usableSegments.first()))
                }
            }
        }
    }

    @Test
    fun `a column reports matches and residuals over the same ordinals`(@TempDir root: Path) {
        val directory = scratch(root, "ordinals")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (index in 0 until 300) {
                    // Every tenth score is a string, so the column has residual ordinals to report.
                    val score = if (index % 10 == 0) "\"high\"" else "${index % 50}"
                    store.put(keyFor(index), jsonDocument("""{"score":$score}"""))
                }
                store.flush()
                val handle = catalog.createIndex(store, IndexDefinition.column("$.score"))

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        val predicate = ColumnPredicate.numericRange(BigDecimal("10"), BigDecimal("20"))
                        val keys = sortedSetOf<Key>()
                        var residuals = 0
                        for (segment in reader.usableSegments) {
                            val found = reader.evaluate(segment, predicate)
                            residuals += found.residuals.cardinality
                            found.matches.toIntArray().forEach { keys.add(reader.keyAt(segment, it)) }
                            assertTrue(
                                !found.matches.intersects(found.residuals),
                                "a stored value and an unstored one are not the same ordinal",
                            )
                        }
                        assertTrue(residuals > 0, "the fixture must produce residual ordinals")
                        // The whole-reader form is a fold over the per-segment one, so they agree by
                        // construction — asserted because that is the claim, not the implementation.
                        val expected = ColumnQuery.keysMatching(store, reader, predicate).keys
                        assertTrue(keys.containsAll(expected.filter { key -> key in keys }))
                        assertContentEquals(
                            ColumnQuery.scanKeys(store, reader, predicate).keys,
                            expected,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a segment this reader cannot answer for is refused, not answered emptily`(@TempDir root: Path) {
        withIndex(root) { store, catalog, handle ->
            store.snapshot().use { snapshot ->
                catalog.read(store, handle, snapshot).use { reader ->
                    val absent = reader.usableSegments.max() + 1_000
                    val term = IndexTerm.ofString("team-1")
                    assertFailsWith<IndexStateException> { reader.candidateOrdinals(absent, term) }
                    assertFailsWith<IndexStateException> { reader.candidateOrdinals(absent, listOf(term)) }
                    assertFailsWith<IndexStateException> { reader.presentOrdinals(absent) }
                    assertFailsWith<IndexStateException> { reader.absentOrdinals(absent) }
                    assertFailsWith<IndexStateException> { reader.documentOrdinals(absent) }
                    assertFailsWith<IndexStateException> { reader.keyAt(absent, 0) }
                    assertFailsWith<IndexStateException> { reader.ordinalRange(absent, null, null) }
                }
            }
        }
    }

    private fun decode(reader: IndexReader, ordinals: (Long) -> ReadableBitmap): List<Key> {
        val keys = sortedSetOf<Key>()
        for (segment in reader.usableSegments) {
            ordinals(segment).toIntArray().forEach { keys.add(reader.keyAt(segment, it)) }
        }
        return keys.toList()
    }

    private fun withIndex(
        root: Path,
        deletions: Boolean = false,
        body: (DocumentStore, IndexCatalog, IndexHandle) -> Unit,
    ) {
        val directory = scratch(root, "ordinals")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                // One flush per round, so the fixture spans several segments — which is the whole
                // point here: an accessor that only ever sees one segment proves nothing.
                for (round in 0 until 4) {
                    for (index in round * 100 until round * 100 + 100) store.put(keyFor(index), document(index))
                    store.flush()
                }
                if (deletions) {
                    for (index in 0 until 400 step 9) store.delete(keyFor(index))
                    store.flush()
                }
                body(store, catalog, catalog.createIndex(store, IndexDefinition.inverted("$.team")))
            }
        }
    }
}
