package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fifth golden store: the first one holding an index of **kind 3**, a composite term.
 *
 * Phase 22 added `COMPOSITE_TERM` and took nothing from the format but an id — no version bump, no
 * section kind, and a `.pst` that is a posting file in every byte but one header field. What it *did*
 * take is a **record continuation**: the registry's per-index entry carries `fieldCount` and the
 * declared field paths after `createdAtSequence`, for this kind and no other. That is a shape on disk
 * which no committed store held, and this directory is the bytes that hold it.
 *
 * **Why it was right not to add one on the day, and why that stopped being enough.** Phase 22 recorded
 * "no golden store was added, because no file any earlier build can write means anything different",
 * and as a statement about *backward* compatibility that is exactly true — the four older directories
 * go on meaning what they meant, and they cover the case an older reader takes, which is to stop at an
 * unknown kind byte. But a golden store is not evidence for the build that wrote it; it is evidence
 * for every build that comes after. From the moment 0.2.0 shipped, a registry holding a kind-3
 * continuation is a file *earlier than the next build*, with nothing committed behind it. The only
 * cover was `ElemMatchTest`'s registry round trip, and `.claude/rules/testing.md` is explicit about
 * what a round trip is worth here: reorder a header and every write-then-read test still passes.
 *
 * **The corpus grows, and that is [GoldenStoreV2]'s case rather than a break with it.** `store-v3` and
 * `store-v4` hold `store-v2`'s corpus unchanged, because a pure layout change is only comparable
 * against identical data. This is the other kind of directory — the kind `store-v2` itself was. A
 * composite term keys a tuple of fields *within one array element*, and the shared corpus has no array
 * of objects at all: `$.tags[*]` is an array of strings, so kind 3 has nothing to key and the
 * directory would pin the feature not existing. `store-v2` added `$.uid` for exactly this reason —
 * `store-v1` indexed only low-cardinality paths and could not pin the singleton encoding however
 * carefully it was read. So the rule is not "hold the corpus fixed"; it is **hold the corpus fixed for
 * a layout change and grow it for a shape the format could not previously express**.
 *
 * The growth is additive and mechanical: [document] is [GoldenStoreV2.document] with one path added,
 * so every assertion the older directories make about `$.team`, `$.uid`, `$.score`, `$.price`,
 * `$.tags[*]`, `$.live` and `$.note` is made here over the same values.
 *
 * **What the added path is arranged to do**, because a fixture for a correlated question has to
 * contain the shape that separates it from the uncorrelated one:
 *
 * - two elements per document whose `sku` and `qty` are taken from **different** residues, so there
 *   are documents in which one element carries the sku and *another* carries the qty. Those are the
 *   documents an uncorrelated conjunction returns and `elemMatch` does not, and they exist at
 *   `index % 12 in 3..4` — arranged, not hoped for, per `.claude/rules/testing.md`.
 * - every seventh document additionally carries an element with a `sku` and **no** `qty`. It
 *   contributes no term at all, which is the property that makes a full-tuple lookup exact and a
 *   partial one lossy, and the reason `CompositeTermPrefixTest` refuses a prefix scan. Committed
 *   bytes in which that element is absent from the dictionary are what a future build changing its
 *   mind about incomplete elements would have to disagree with.
 *
 * **What this directory is worth today, stated as its predecessors state it.** Today it is a round
 * trip wearing a golden file's clothes: written by the build that reads it, pinning nothing
 * `ElemMatchTest` and `CompositeTermPrefixTest` do not. It becomes evidence at the *next* change to
 * the registry, the posting header or the tuple encoding, and not before. [GoldenStoreV3] said this of
 * itself and was right within a day; [GoldenStoreV4] said it and is still waiting. Saying it is the
 * difference between a fixture that is an investment and one somebody mistakes for proof.
 *
 * **One thing it is that none of the others is: written by a *release*.** The four before it were
 * written by phase builds, which are commits. This one is written by the tree at `v0.2.0`, which is
 * the artefact on Maven Central — so it is the first committed answer to the sentence
 * `COMPATIBILITY.md` actually promises, that a store written by an earlier **release** opens on every
 * later one.
 *
 * **Nothing is retired, and the reasons are unchanged.** [GoldenStore] carries seven singleton posting
 * lists stored as `BITMAP`; [GoldenStoreV2] carries the version-1 term dictionary on bytes nobody
 * regenerated; [GoldenStoreV3] is the only store pairing a version-2 posting file with a version-1 key
 * block; and all three remain the only committed cover for `FixedWidthKeyBlockReader`. [GoldenStoreV4]
 * keeps its place for a reason this directory *creates*: it is now the newest store that predates
 * index kind 3, which is what makes the absent case a claim about a build that could have written one
 * rather than about builds that could not.
 */
internal object GoldenStoreV5 : GoldenCorpus {

    override val resource: String = "golden/store-v5"

    override val documentCount: Int = GoldenStoreV2.documentCount

    /** [GoldenStoreV2]'s five, plus the composite index this directory exists for. */
    override val indexCount: Int = 6

    /** Written by the 0.2.0 build, which writes version 2 of both sidecars. */
    override val postingVersion: Int = 2

    /** And version 2 of the key block. */
    override val baseVersion: Int = 2

    override val columnsClaimFidelity: Boolean = true

    /**
     * The reason this directory exists, and the definition the registry must give back intact.
     *
     * Order is significant to the bytes — a term carries each value's declared position, so
     * `(sku, qty)` and `(qty, sku)` are different files for the same elements. Comparing the decoded
     * definition against this one therefore compares the field *order* as well as the field set,
     * which is what a `List` in [IndexDefinition] is for.
     */
    override val compositeIndex: IndexDefinition =
        IndexDefinition.composite(ELEMENT_PATH, SKU, QTY)

    override val modelledPaths: List<String> =
        GoldenStoreV2.modelledPaths + listOf("$.items[*].sku", "$.items[*].qty")

    /**
     * [GoldenStoreV2]'s shapes, plus the two this corpus exists to tell apart.
     *
     * Both are asserted by the shared suite to agree with a full scan and to match *something*; that
     * they match different things is asserted separately, because "the correlated query answered" is
     * true of a build that had quietly stopped correlating.
     */
    override val queries: List<Query> =
        GoldenStoreV2.queries + listOf(Query.where(correlated), Query.where(uncorrelated))

    override val deleted: List<Int> = GoldenStoreV2.deleted

    /**
     * [GoldenStoreV2.document] with one array of objects appended, and nothing else touched.
     *
     * Spliced rather than rewritten so that the relationship is mechanical: there is no second copy of
     * the shared fields to drift from the original, and a change to [GoldenStoreV2.document] — which
     * would already be a change to three committed directories — cannot silently leave this one
     * describing different data.
     */
    fun document(index: Int): String {
        val base = GoldenStoreV2.document(index)
        check(base.endsWith("}")) { "the shared corpus no longer ends in an object: $base" }
        return base.dropLast(1) + ""","items":${items(index)}}"""
    }

    /**
     * The elements of `$.items[*]`, arranged so the two questions have different answers.
     *
     * `sku` moves on a period of 4 and `qty` on a period of 3, and the second element takes each from
     * a different offset — so an element holding the sku of interest generally does *not* hold the qty
     * of interest. See the class KDoc for which residues that puts the discriminating documents at.
     */
    private fun items(index: Int): String = buildString {
        append("""[{"sku":"sku-${index % 4}","qty":${index % 3}}""")
        append(""",{"sku":"sku-${(index + 1) % 4}","qty":${(index + 2) % 3}}""")
        // An element with a sku and no qty contributes no term: a composite term exists only for an
        // element carrying every declared field. Committed bytes for the shape that decides exactness.
        if (index % 7 == 0) append(""",{"sku":"sku-9"}""")
        append("]")
    }

    override fun expected(index: Int): String? = when {
        index in deleted -> null
        index in GoldenStoreV2.overwritten -> document(index + 10_000)
        else -> document(index)
    }

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
                    store.put(keyFor(index), jsonDocument(document(index)))
                    if (index % 60 == 59) store.flush()
                }
                for (index in GoldenStoreV2.overwritten) {
                    store.put(keyFor(index), jsonDocument(document(index + 10_000)))
                }
                for (index in deleted) store.delete(keyFor(index))
                store.flush()
                store.compact()

                // The same five, in the same order, so `$.uid` is still the fifth index and its
                // posting files are still the `.0005.pst` ones the singleton assertion reads.
                indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                indexes.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                indexes.createIndex(store, IndexDefinition.column("$.score"))
                indexes.createIndex(store, IndexDefinition.column("$.price"))
                indexes.createIndex(store, IndexDefinition.inverted("$.uid"))
                // The reason this directory exists, created last so the five above are undisturbed.
                indexes.createIndex(store, compositeIndex)
                store.sync()
            }
        }
    }

    override fun toString(): String = resource

    /** The element path the composite index is over. */
    const val ELEMENT_PATH: String = "$.items[*]"

    /** The declared fields, relative to one element, in the order the term writes them. */
    const val SKU: String = "$.sku"
    const val QTY: String = "$.qty"

    /**
     * "One element has **both**" — the question a composite term answers exactly.
     *
     * Held here rather than built at each use so that the two queries below cannot drift into asking
     * the same thing, which would make the assertion that separates them vacuous.
     */
    val correlated: Predicate
        get() = elemMatch(ELEMENT_PATH, and(path(SKU) eq "sku-0", path(QTY) eq 0L))

    /** "Some element has the sku and some element has the qty" — a superset, and defined semantics. */
    val uncorrelated: Predicate
        get() = and(path("$.items[*].sku") eq "sku-0", path("$.items[*].qty") eq 0L)
}
