package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path

/**
 * The fourth golden store: the first one holding a **version-2** base sidecar.
 *
 * Phase 18 narrowed the `KEYS` entry header from a `u32` pair to two varints —
 * `IndexFormat.BASE_VERSION` went to 2 — which is six bytes per key and, over the benchmark corpus,
 * six of every ten bytes of a base sidecar. Nothing committed before it holds that layout, so this
 * directory is the only bytes in the repository a future build can be checked against.
 *
 * **It is added under the retirement rule, not in spite of it**, and this is the rule's third outing.
 * Phase 12 correctly added *nothing*: an optional section absent from both existing stores was already
 * covered by them. Phase 17 added one because a front-coded dictionary was a layout no committed file
 * had. This is the second of those cases — a varint key block is a layout no committed file has, and
 * the only way to have one written by *this* build, before the next format change needs it, is to
 * write it now.
 *
 * **What it is worth today, stated plainly.** Today it is a round trip wearing a golden file's
 * clothes: written by the build that reads it, pinning nothing `KeyBlockTest` and
 * `IndexByteIdentityTest` do not. It becomes evidence at the *next* format change and not before, and
 * saying so is the difference between a fixture that is an investment and one somebody mistakes for
 * proof. [GoldenStoreV3] said exactly this a phase ago and was right.
 *
 * **Nothing is retired, and each of the three has its own reason.** [GoldenStore] carries seven
 * singleton posting lists stored as `BITMAP`, a shape no later build can produce. [GoldenStoreV2]
 * carries the version-1 term dictionary on bytes nobody regenerated. [GoldenStoreV3] is the only store
 * pairing a version-2 posting file with a version-1 key block. All three are now also the only
 * committed cover for `FixedWidthKeyBlockReader` — which is the role phase 18 handed them, on the same
 * day it took `store-v3`'s.
 *
 * The corpus is [GoldenStoreV2]'s, deliberately unchanged, exactly as [GoldenStoreV3]'s is. Holding
 * the shape fixed across four directories is what makes them comparable as *format* differences rather
 * than as different data.
 */
internal object GoldenStoreV4 : GoldenCorpus {

    override val resource: String = "golden/store-v4"

    override val documentCount: Int = GoldenStoreV2.documentCount

    override val indexCount: Int = 5

    override val postingVersion: Int = 2

    /** The reason this directory exists: phase 18's varint key entries. */
    override val baseVersion: Int = 2

    override val columnsClaimFidelity: Boolean = true

    /**
     * Four phases before index kind 3, and the **newest** store that predates it.
     *
     * Which is what makes the four absent cases worth stating rather than assuming: this directory is
     * the one a reader would expect to carry everything current, so a corpus list in which nothing
     * says *no* is a list in which the assertion could be satisfied by the feature not existing.
     */
    override val compositeIndex: IndexDefinition? = null

    override val modelledPaths: List<String> = GoldenStoreV2.modelledPaths

    override val queries: List<Query> = GoldenStoreV2.queries

    override val deleted: List<Int> = GoldenStoreV2.deleted

    override fun expected(index: Int): String? = GoldenStoreV2.expected(index)

    override val options: StoreOptions get() = GoldenStoreV2.options

    /** The same script [GoldenStoreV3] writes, because only the format is allowed to differ. */
    override fun write(directory: Path): Unit = GoldenStoreV3.write(directory)

    override fun toString(): String = resource
}
