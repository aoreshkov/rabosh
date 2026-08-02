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
 * The third golden store: the first one holding a **version-2** posting file.
 *
 * Phase 17 replaced the term dictionary — `POSTING_VERSION` went to 2, the term entry lost its offset
 * and length, and the terms are front-coded behind restart points. Nothing committed before it holds
 * that layout, so this directory is the only bytes in the repository a future build can be checked
 * against.
 *
 * **It is added under the retirement rule, not in spite of it.** The rule adopted in phase 11 says a
 * golden store earns its place by *discriminating*, and phase 12's first application of it correctly
 * added nothing: an optional section absent from both existing stores was already covered by them.
 * This is the other case. A front-coded dictionary is a layout no committed file has, and the only way
 * to have one written by *this* build — before the next format change needs it — is to write it now.
 *
 * **What it was worth on the day it landed, and what it is worth now.** Phase 17 recorded it here as
 * "a round trip wearing a golden file's clothes: written by the build that reads it, pinning nothing
 * `PostingEncodingTest` and `IndexByteIdentityTest` do not", and predicted it would become evidence at
 * the *next* format change and not before.
 *
 * **Phase 18 was that change, the following day, and the prediction held twice over.** This directory
 * now carries a version-1 key block written by a build that can no longer write one — and it is the
 * **only** committed store pairing that with a version-2 posting file, a combination nothing will
 * produce again. The rotation `store-v2` went through in phase 17 is not a one-off property of that
 * phase; it is what a golden store is *for*, and two consecutive format changes are enough to say so
 * as an observation rather than a hope. [GoldenStoreV4] now holds the role this one vacated.
 *
 * **What its two predecessors are worth today has changed, and that is the interesting half.** Until
 * this phase `store-v1` and `store-v2` both held version-1 posting files and `store-v2` was itself a
 * round trip. Phase 17 made version 1 a layout this build can no longer *write* — so between them they
 * are now the only cover for [app.oreshkov.rabosh.index.FlatTermDictionary] on bytes nobody
 * regenerated, which is exactly the role `.claude/rules/format-permanence.md` predicted they would
 * acquire at the first format change. Neither may be retired: `store-v1` additionally carries seven
 * singleton posting lists stored
 * as `BITMAP`, a shape no later build can produce.
 *
 * The corpus is [GoldenStoreV2]'s, deliberately unchanged — including `$.uid`, whose singleton
 * postings this now pins in the new dictionary as well. Holding the shape fixed is what makes the
 * three directories comparable as *format* differences rather than as different data.
 */
internal object GoldenStoreV3 : GoldenCorpus {

    override val resource: String = "golden/store-v3"

    override val documentCount: Int = GoldenStoreV2.documentCount

    override val indexCount: Int = 5

    /** The reason this directory exists: phase 17's front-coded dictionary. */
    override val postingVersion: Int = 2

    /**
     * And a version-1 key block, which makes this the **only** committed store holding that pair.
     *
     * Phase 18 narrowed the key entry the day after phase 17 narrowed the term entry, so no build can
     * produce this combination again. That is what stops the retirement rule reaching for it: a store
     * is kept while it exercises a decode path no later store does, and (posting v2, base v1) is one.
     */
    override val baseVersion: Int = 1

    /**
     * The first committed store whose columns carry `SECTION_FIDELITY`.
     *
     * Phase 12 added the section and added no golden directory, correctly — both stores that existed
     * predated it and therefore covered the case that mattered, an absent section read as no claim.
     * The *present* case had no committed bytes at all until now. This is not why the directory exists,
     * but it is a second thing it pins, and the pair of assertions is stronger than either alone: one
     * says an old column is not believed, the other says a new one is.
     */
    override val columnsClaimFidelity: Boolean = true

    override val modelledPaths: List<String> = GoldenStoreV2.modelledPaths

    override val queries: List<Query> = GoldenStoreV2.queries

    override val deleted: List<Int> = GoldenStoreV2.deleted

    override fun expected(index: Int): String? = GoldenStoreV2.expected(index)

    override val options: StoreOptions get() = GoldenStoreV2.options

    override fun write(directory: Path) {
        Files.createDirectories(directory)
        val schema = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            val observer = CompositeSegmentObserver(listOf(schema, indexes))
            DocumentStore.open(directory, options.copyWith(observer)).use { store ->
                schema.attach(store)
                indexes.attach(store)

                for (index in 0 until documentCount) {
                    store.put(keyFor(index), jsonDocument(GoldenStoreV2.document(index)))
                    if (index % 60 == 59) store.flush()
                }
                for (index in GoldenStoreV2.overwritten) {
                    store.put(keyFor(index), jsonDocument(GoldenStoreV2.document(index + 10_000)))
                }
                for (index in deleted) store.delete(keyFor(index))
                store.flush()
                store.compact()

                indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                indexes.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                indexes.createIndex(store, IndexDefinition.column("$.score"))
                indexes.createIndex(store, IndexDefinition.column("$.price"))
                indexes.createIndex(store, IndexDefinition.inverted("$.uid"))
                store.sync()
            }
        }
    }

    override fun toString(): String = resource
}
