package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.name

/**
 * The corpus behind the golden store, and the code that writes one.
 *
 * **Every value here is fixed on purpose.** The corpus decides what the committed bytes are, so a
 * change to it is a change to the thing being pinned — which is the opposite of what a compatibility
 * test is for. Add a *new* golden directory rather than editing this one, exactly as a format change
 * is a new id rather than a changed one.
 *
 * The shape exercises each format that has to survive: several segments so the manifest names more
 * than one, an overwrite and a deletion so the merge has something to do, a path of stable numeric
 * type for a shredded column, a repeated path for a `[*]` index, a nullable path so presence and
 * nullity are distinguishable, and a value wide enough to be residual.
 */
internal object GoldenStore : GoldenCorpus {

    /** Where the committed store lives on the test classpath. */
    const val RESOURCE: String = "golden/store-v1"

    const val DOCUMENT_COUNT: Int = 240

    override val resource: String get() = RESOURCE

    override val documentCount: Int get() = DOCUMENT_COUNT

    override val indexCount: Int get() = 4

    /** Written by the phase 9 build, long before phase 17 gave the dictionary a second layout. */
    override val postingVersion: Int get() = 1

    /** And long before phase 18 gave the key block one. `u32/u32` key entry headers. */
    override val baseVersion: Int get() = 1

    /** Phase 9 predates `SECTION_FIDELITY`, so these columns claim nothing and are believed. */
    override val columnsClaimFidelity: Boolean get() = false

    override val modelledPaths: List<String> =
        listOf("$.team", "$.score", "$.price", "$.tags[*]", "$.live", "$.note")

    override val queries: List<Query> = goldenQueries()

    /** The keys written after the initial load, then overwritten. */
    val overwritten: List<Int> = listOf(3, 17, 100, 211)

    /** The keys written and then deleted. */
    override val deleted: List<Int> = listOf(5, 42, 199)

    fun document(index: Int): String = buildString {
        append("""{"team":"team-${index % 7}","score":${index % 50},""")
        append(""""price":${index % 90}.${"%02d".format(index % 100)},""")
        append(""""tags":["t${index % 5}","t${index % 3}"],""")
        append(""""live":${index % 3 == 0}""")
        if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"note-$index\""}""")
        // Wider than 64 unscaled bits at any common scale: this ordinal must go to residual, which
        // is a state the column format has to keep expressible.
        if (index % 40 == 0) append(""","huge":123456789012345678901234567890.5""")
        append("}")
    }

    /** What the document at [index] should read back as, after the overwrites and deletions. */
    override fun expected(index: Int): String? = when {
        index in deleted -> null
        index in overwritten -> document(index + 10_000)
        else -> document(index)
    }

    override val options: StoreOptions
        get() = StoreOptions(
            durability = Durability.SYNC,
            segmentMaxBytes = 16 * 1024,
            blockSize = 512,
            backgroundMaintenance = false,
        )

    /** Writes a complete store — documents, sketches and both index kinds — into [directory]. */
    override fun write(directory: Path) {
        Files.createDirectories(directory)
        val schema = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            val observer = CompositeSegmentObserver(listOf(schema, indexes))
            DocumentStore.open(directory, options.copyWith(observer)).use { store ->
                schema.attach(store)
                indexes.attach(store)

                for (index in 0 until DOCUMENT_COUNT) {
                    store.put(keyFor(index), jsonDocument(document(index)))
                    if (index % 60 == 59) store.flush()
                }
                for (index in overwritten) store.put(keyFor(index), jsonDocument(document(index + 10_000)))
                for (index in deleted) store.delete(keyFor(index))
                store.flush()
                store.compact()

                indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                indexes.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                indexes.createIndex(store, IndexDefinition.column("$.score"))
                indexes.createIndex(store, IndexDefinition.column("$.price"))
                store.sync()
            }
        }
    }

    /** Names the directory in a parameterised test report, where `GoldenStore@1a2b` would not. */
    override fun toString(): String = resource
}

/**
 * The queries every golden store is interrogated with.
 *
 * Shared because both committed corpora carry the same fields; the shapes are the ones each format
 * has to survive — equality on a string, equality inside a repeated path, a range and a strict range
 * over shredded columns, a conjunction across two index kinds, and nullity against absence, which are
 * different questions and have caught each other's bugs before.
 */
internal fun goldenQueries(): List<Query> = listOf(
    Query.where(path("$.team") eq "team-3"),
    Query.where(path("$.tags[*]") eq "t2"),
    Query.where(path("$.score").between(java.math.BigDecimal("10"), java.math.BigDecimal("20"))),
    Query.where(path("$.price") lt java.math.BigDecimal("30")),
    Query.where(and(path("$.team") eq "team-1", path("$.score") ge 25L)),
    Query.where(path("$.note").isNull()),
    Query.where(not(path("$.note").exists())),
)
