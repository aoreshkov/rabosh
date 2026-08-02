package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A store written by an earlier build, opened by this one.
 *
 * Every magic number, format version, type id and layout in this engine is documented as
 * **permanent**. That is a promise about files that already exist, and the only way to keep a promise
 * about files is to keep some files: `src/test/resources/golden/store-v1` and `store-v2` are real
 * stores — documents, manifest, log, sketches, posting files and shredded columns — committed as
 * bytes.
 *
 * What this catches is the thing a same-build round trip cannot: a change that is self-consistent.
 * Renumber a type id, reorder a header field, alter what a checksum covers, and every test that
 * writes and reads with the same code still passes. This one stops passing, which is the point.
 *
 * **There is more than one directory, and that is the design.** `store-v1` predates the singleton
 * posting encoding and holds not a single posting list of cardinality one; `store-v2` is unique-valued
 * throughout. Reading both is what says the encoding arrived *additively* — v1 proves the old bytes
 * still mean what they meant, v2 proves the new ones are pinned too. A regenerated v1 would prove
 * neither.
 *
 * **Phase 17 is the first time that investment paid, and it is worth saying which way round.** Until
 * then `store-v2` was a round trip wearing a golden file's clothes — written by the build that read
 * it, pinning nothing another test did not. Phase 17 bumped `POSTING_VERSION` to 2, so *both* older
 * directories now hold a dictionary layout this build can no longer write, and between them they are
 * the only cover in the repository for the version-1 reader. `store-v3` takes v2's former place: it is
 * this build's own output today and becomes evidence at the next format change. That rotation is the
 * whole argument for keeping a fixture nobody can yet evaluate.
 *
 * **Regenerating is a deliberate act, not a fix.** If this test fails, either the change is a
 * compatibility break to be reverted, or it is a new format — in which case the new golden store is a
 * *new directory* beside these, so that all of them are read from then on. Nothing here should be
 * rewritten in place, for the same reason a format id is added rather than changed.
 *
 * ```
 * ./gradlew :rabosh-query:test --tests '*FormatCompatibilityTest*' -Drabosh.golden.write=true
 * ```
 *
 * writes fresh stores into `build/golden/` for a human to inspect and copy in.
 */
class FormatCompatibilityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `a store written by an earlier build still reads`(golden: GoldenCorpus, @TempDir target: Path) {
        val directory = golden.extractTo(target.resolve("store"))

        DocumentStore.open(directory, golden.options).use { store ->
            for (index in 0 until golden.documentCount) {
                val expected = golden.expected(index)
                val actual = store.get(keyFor(index))?.toJsonString()
                if (expected == null) {
                    assertNull(actual, "key $index was deleted before the store was committed")
                } else {
                    assertEquals(
                        Variant.fromJson(expected).toJsonString(),
                        actual,
                        "key $index does not read back as it was written",
                    )
                }
            }

            // The whole store, in order, through the merge rather than through point lookups.
            val scanned = store.scan().use { cursor ->
                buildList { while (cursor.next()) add(cursor.key) }
            }
            assertEquals(
                golden.documentCount - golden.deleted.size,
                scanned.size,
                "a deleted key must stay deleted",
            )
            assertEquals(scanned.sorted(), scanned, "and the merge must still be ordered")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `the sketches written by an earlier build still infer a schema`(golden: GoldenCorpus, @TempDir target: Path) {
        val directory = golden.extractTo(target.resolve("store"))
        val schema = SchemaCatalog(directory)
        DocumentStore.open(directory, golden.options).use { store ->
            schema.attach(store)
            val inferred = schema.inferSchema()

            assertTrue(inferred.coverage.isComplete, "every segment's sketch must still decode")
            for (expression in golden.modelledPaths) {
                assertTrue(inferred[expression] != null, "$expression should be in the inferred schema")
            }
            assertEquals(
                app.oreshkov.rabosh.variant.VariantKind.STRING,
                inferred["$.team"]?.dominantType,
            )
            assertTrue((inferred["$.note"]?.nullFraction ?: 0.0) > 0.0, "nullity must survive the round trip")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `the indexes written by an earlier build still answer, and agree with a scan`(
        golden: GoldenCorpus,
        @TempDir target: Path,
    ) {
        val directory = golden.extractTo(target.resolve("store"))
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(directory, golden.options).use { store ->
                // `backfill = false`: if the committed sidecars did not decode, this must fail rather
                // than quietly rebuild them and pass.
                indexes.attach(store, backfill = false)
                assertEquals(
                    golden.indexCount,
                    indexes.indexes().size,
                    "every index definition must still be registered",
                )

                val engine = QueryEngine(store, indexes)
                store.snapshot().use { snapshot ->
                    for (query in golden.queries) {
                        val stats = assertMatchesScan(engine, store, snapshot, query, "golden: $query")
                        assertTrue(stats.rowsReturned > 0, "the golden corpus should match: $query")
                    }

                    // And the sidecars are genuinely being read, not silently skipped.
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.team") eq "team-3"),
                        "golden coverage",
                    )
                    assertEquals(0, stats.segmentsScanned, "the committed sidecars must cover every segment")
                    assertEquals(0, stats.documentsRead, "and answer without opening a document")
                }
            }
        }
    }

    /**
     * The fidelity flag, pinned in **both** directions on committed bytes.
     *
     * `store-v1` and `store-v2` predate `SECTION_FIDELITY`, so their `.col` files carry no claim about
     * whether their values reconstruct exactly — and the rule is that an absent section reads as *no*
     * rather than as *yes*. `store-v3` was written after it and claims exactly. The consequence is
     * visible from the outside either way: the projection over `$.score` answers correctly in both,
     * and only one of them opens a document to do it.
     *
     * This is the assertion that says the flag was added the safe way round. Had it been spelled as
     * "this column may be lossy", every column ever written would silently have claimed a fidelity
     * nobody checked, and the old stores would pass here while returning values no document holds.
     * Asserting the *present* case beside it is what stops "push-down never fires" satisfying both —
     * the same reason `documentsRead == 0` never stands without a second counter beside it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `a column is projected from columns only when it claims fidelity`(
        golden: GoldenCorpus,
        @TempDir target: Path,
    ) {
        val directory = golden.extractTo(target.resolve("store"))
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(directory, golden.options).use { store ->
                indexes.attach(store, backfill = false)
                val engine = QueryEngine(store, indexes)
                val query = Query.where(path("$.team") eq "team-3").project("$.score")

                store.snapshot().use { snapshot ->
                    // Bound as a plan in every corpus — `$.score` does have a column. Whether it can
                    // serve a *row* is what the section decides.
                    assertTrue(engine.explain(query, snapshot).projectsFromColumns)
                    engine.execute(query, snapshot).use { cursor ->
                        var rows = 0
                        while (cursor.next()) {
                            assertTrue(cursor.row["$.score"] != null, "the value must still come back")
                            rows++
                        }
                        assertTrue(rows > 0, "the golden corpus should match")
                        if (golden.columnsClaimFidelity) {
                            assertEquals(rows, cursor.stats.rowsProjectedFromColumns, "a claiming column serves rows")
                            assertEquals(0, cursor.stats.documentsRead, "so no document is opened")
                        } else {
                            assertEquals(0, cursor.stats.rowsProjectedFromColumns, "an old column claims nothing")
                            assertTrue(cursor.stats.documentsRead > 0, "so the documents are read instead")
                        }
                    }
                }
            }
        }
    }

    /**
     * The singleton posting encoding, read out of bytes committed before this assertion was written.
     *
     * A unique-valued index is the whole reason `store-v2` exists, and "the query answered" would be
     * true of either encoding. So the file is inspected: every term entry in the `$.uid` posting file
     * must carry [app.oreshkov.rabosh.index.IndexFormat.POSTING_ENCODING_SINGLE], and the file must
     * end where its presence bitmap does, because a singleton has no posting region to live in.
     *
     * **Run against both dictionary layouts, which is what makes it an interoperability assertion
     * rather than a self-check.** `store-v2` holds these singletons in the version-1 directory and
     * `store-v3` in the version-2 one, and the encoding id is *the same number in both* — the whole
     * claim of phase 17's version bump: a layout may be replaced, an id may not be renumbered. The
     * entry width comes from the corpus rather than from a literal, because reading a version-2 file
     * with version-1 arithmetic would land inside the wrong field and could easily still find a 2.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("uniqueValuedCorpora")
    fun `the committed unique-valued index is stored as singletons`(golden: GoldenCorpus, @TempDir target: Path) {
        val directory = golden.extractTo(target.resolve("store"))

        // The `$.uid` index is the fifth defined, so its posting files are the `.0005.pst` ones.
        val postings = Files.newDirectoryStream(directory, "*.0005.pst").use { it.toList() }
        assertTrue(postings.isNotEmpty(), "${golden.resource} must carry posting files for the unique-valued index")

        val entryBytes = golden.postingTermEntryBytes
        for (file in postings) {
            val bytes = Files.readAllBytes(file)
            assertEquals(golden.postingVersion, readLittleEndianU32(bytes, 8), "$file is not the version claimed")
            val termCount = readLittleEndianU32(bytes, 40)
            val directoryOffset = readLittleEndianU32(bytes, 44)
            val presenceOffset = readLittleEndianU32(bytes, 48)
            val presenceLength = readLittleEndianU32(bytes, 52)
            assertTrue(termCount > 0, "$file names no terms")
            for (index in 0 until termCount) {
                val at = directoryOffset + index * entryBytes + golden.postingFieldOffset
                assertEquals(2, bytes[at + 8].toInt() and 0xFF, "term $index of $file is not SINGLE")
                assertEquals(0, readLittleEndianU32(bytes, at + 4), "posting length")
            }
            assertEquals(
                bytes.size,
                presenceOffset + presenceLength,
                "$file must end at its presence bitmap: singletons occupy no posting region",
            )
        }
    }

    /**
     * The committed dictionaries are the layouts they claim to be, and both are still read.
     *
     * Without this, "every golden store opens" would be satisfied by a build that had quietly stopped
     * writing version 2, or that had regenerated the older directories on the way past — which is the
     * failure the regeneration rule exists to prevent and which no behavioural assertion can see.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `each golden store carries the posting version it claims`(golden: GoldenCorpus, @TempDir target: Path) {
        val directory = golden.extractTo(target.resolve("store"))
        val postings = Files.newDirectoryStream(directory, "*.pst").use { it.toList() }
        assertTrue(postings.isNotEmpty(), "${golden.resource} must carry posting files")
        for (file in postings) {
            assertEquals(
                golden.postingVersion,
                readLittleEndianU32(Files.readAllBytes(file), 8),
                "$file is not version ${golden.postingVersion}",
            )
        }
    }

    /**
     * The committed key blocks are the layouts they claim to be, and both are still read.
     *
     * The same assertion as the posting version above and for the same reason, on the second version
     * field this engine has bumped. It runs **both ways** by construction — three corpora claim
     * version 1 and one claims version 2 — which is what stops it being satisfied by a build that had
     * quietly stopped writing version 2 or had regenerated the older directories on the way past.
     * Phase 17 learned that the hard way from a fidelity assertion which only ever saw the absent case.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    fun `each golden store carries the base version it claims`(golden: GoldenCorpus, @TempDir target: Path) {
        val directory = golden.extractTo(target.resolve("store"))
        val sidecars = Files.newDirectoryStream(directory, "*.idx").use { it.toList() }
        assertTrue(sidecars.isNotEmpty(), "${golden.resource} must carry base sidecars")
        for (file in sidecars) {
            assertEquals(
                golden.baseVersion,
                readLittleEndianU32(Files.readAllBytes(file), 8),
                "$file is not version ${golden.baseVersion}",
            )
        }
    }

    /**
     * Writes fresh golden stores for a human to look at. Never run in an ordinary build: it exists
     * so that adding a *new* golden directory is a documented, repeatable act.
     */
    @Test
    fun `regenerating is opt-in and writes beside the build`() {
        if (System.getProperty("rabosh.golden.write")?.toBoolean() != true) return
        for (golden in corpora()) {
            val target = Path.of("build", golden.resource)
            if (Files.exists(target)) target.toFile().deleteRecursively()
            golden.write(target)
            println("wrote a golden store to ${target.toAbsolutePath()}")
        }
    }

    private companion object {
        @JvmStatic
        fun corpora(): List<GoldenCorpus> = listOf(GoldenStore, GoldenStoreV2, GoldenStoreV3, GoldenStoreV4)

        /** The corpora carrying an index whose every term matches exactly one document. */
        @JvmStatic
        fun uniqueValuedCorpora(): List<GoldenCorpus> = listOf(GoldenStoreV2, GoldenStoreV3, GoldenStoreV4)

        /** The format is little-endian throughout; this reads a `u32` without opening the file twice. */
        fun readLittleEndianU32(bytes: ByteArray, offset: Int): Int {
            var value = 0
            for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
            return value
        }
    }
}
