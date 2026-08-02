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

/**
 * The second golden store: everything `store-v1` pins, plus the singleton posting encoding.
 *
 * `store-v1` indexes only low-cardinality paths, so not one of its posting lists has cardinality one
 * and it cannot pin [app.oreshkov.rabosh.index.IndexFormat.POSTING_ENCODING_SINGLE] however carefully
 * it is read. This corpus adds `$.uid`, distinct per document, and an inverted index over it — so
 * every term in that `.pst` is a singleton and the encoding is committed as bytes rather than as a
 * round trip.
 *
 * **This is a new directory, not an edit to the old one.** Both are read from here on, which is the
 * same rule as adding a format id rather than changing one: `store-v1` is what says the encoding
 * arrived additively, and only a file written before the change can say that.
 *
 * Every value here is fixed on purpose, for the reason [GoldenStore] gives.
 */
internal object GoldenStoreV2 : GoldenCorpus {

    override val resource: String = "golden/store-v2"

    override val documentCount: Int = 240

    override val indexCount: Int = 5

    /** Written by the phase 11 build: the singleton encoding, in the version-1 dictionary. */
    override val postingVersion: Int = 1

    /** Version-1 key entries too — phase 18 is four phases away from this build. */
    override val baseVersion: Int = 1

    /** Phase 11 predates `SECTION_FIDELITY` by one phase, so these columns claim nothing either. */
    override val columnsClaimFidelity: Boolean = false

    override val modelledPaths: List<String> =
        listOf("$.team", "$.uid", "$.score", "$.price", "$.tags[*]", "$.live", "$.note")

    /** The shared shapes, plus the one this corpus exists for: a term matching a single document. */
    override val queries: List<Query> = goldenQueries() + Query.where(path("$.uid") eq 43L)

    /** The keys written after the initial load, then overwritten. */
    val overwritten: List<Int> = listOf(3, 17, 100, 211)

    /** The keys written and then deleted. */
    override val deleted: List<Int> = listOf(5, 42, 199)

    fun document(index: Int): String = buildString {
        // Distinct per document, which is what makes every posting list here a singleton.
        append("""{"uid":$index,"team":"team-${index % 7}","score":${index % 50},""")
        append(""""price":${index % 90}.${"%02d".format(index % 100)},""")
        append(""""tags":["t${index % 5}","t${index % 3}"],""")
        append(""""live":${index % 3 == 0}""")
        if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"note-$index\""}""")
        // Wider than 64 unscaled bits at any common scale: this ordinal must go to residual.
        if (index % 40 == 0) append(""","huge":123456789012345678901234567890.5""")
        append("}")
    }

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

    override fun write(directory: Path) {
        Files.createDirectories(directory)
        val schema = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            val observer = CompositeSegmentObserver(listOf(schema, indexes))
            DocumentStore.open(directory, options.copyWith(observer)).use { store ->
                schema.attach(store)
                indexes.attach(store)

                for (index in 0 until documentCount) {
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
                // The reason this directory exists.
                indexes.createIndex(store, IndexDefinition.inverted("$.uid"))
                store.sync()
            }
        }
    }

    /** Names the directory in a parameterised test report, where `GoldenStoreV2@1a2b` would not. */
    override fun toString(): String = resource
}
