package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.name

/**
 * One committed store, and everything `FormatCompatibilityTest` needs to interrogate it.
 *
 * There is more than one golden directory and there will be more again, because the repo's rule is
 * that a format change is a **new** directory beside the old one rather than an edit to it. This is
 * what makes "beside" cheap: the assertions are written once against this, so adding a golden store
 * is writing its corpus and listing it, not copying a test.
 *
 * Every member is a fact about bytes that already exist. Changing one is changing what is pinned,
 * which is the opposite of what a compatibility test is for.
 */
interface GoldenCorpus {

    /** Where the committed store lives on the test classpath. */
    val resource: String

    /** How many keys were written before the overwrites and deletions. */
    val documentCount: Int

    /** The keys written and then deleted, which must still read as absent. */
    val deleted: List<Int>

    /** How many index definitions the registry must still name. */
    val indexCount: Int

    /**
     * The `POSTING_VERSION` the committed `.pst` files carry.
     *
     * A property of the corpus rather than a constant in a test, because phase 17 made the posting
     * file's term entry a different width in version 2 and an assertion that reads the directory has
     * to know which. Pinning it here also means a corpus whose files were regenerated under a newer
     * format fails loudly instead of being read with the wrong arithmetic.
     */
    val postingVersion: Int

    /** Bytes per term entry in this corpus's posting files, derived from [postingVersion]. */
    val postingTermEntryBytes: Int get() = if (postingVersion >= 2) 16 else 24

    /**
     * The `BASE_VERSION` the committed `.idx` files carry.
     *
     * The second version fact and the second reason to state one per corpus rather than per test.
     * Phase 18 narrowed the key entry's two lengths from a `u32` pair to two varints, and the three
     * directories written before it are now the only committed cover for the version-1 key block —
     * exactly the role `store-v1` and `store-v2` acquired for the version-1 term dictionary on the day
     * phase 17 landed. A corpus that was quietly regenerated under a newer build fails here rather
     * than passing as a round trip.
     */
    val baseVersion: Int

    /**
     * Whether this corpus's `.col` files carry `ColumnFormat.SECTION_FIDELITY`.
     *
     * Phase 12 added the section and the two older stores predate it, so between them the corpora now
     * pin the flag **in both directions on committed bytes**: a column that makes no claim is read as
     * making none and the documents are opened, and a column that claims exact reconstruction is
     * believed and the read is skipped. Getting the absent case right is the safety property — an
     * optional section must state a *capability*, never a defect — and getting the present case
     * asserted is what stops "no push-down anywhere" passing for both.
     */
    val columnsClaimFidelity: Boolean

    /** Offset within a term entry of `postingOffset`; version 1 spends its first eight on the term. */
    val postingFieldOffset: Int get() = if (postingVersion >= 2) 0 else 8

    /** The options the store was written with, and the ones it must reopen under. */
    val options: StoreOptions

    /** Paths the committed sketches must still model. */
    val modelledPaths: List<String>

    /** Queries that must still answer, agree with a full scan, and match something. */
    val queries: List<Query>

    /** What the document at [index] should read back as. `null` where it was deleted. */
    fun expected(index: Int): String?

    /** Writes a fresh store of this shape. Used only by the opt-in regeneration. */
    fun write(directory: Path)

    /** Copies the committed store out of the classpath into a writable directory. */
    fun extractTo(target: Path): Path {
        val source = requireNotNull(GoldenCorpus::class.java.classLoader.getResource(resource)) {
            "the golden store $resource is missing from the test resources; see FormatCompatibilityTest"
        }
        val root = Path.of(source.toURI())
        Files.createDirectories(target)
        Files.newDirectoryStream(root).use { entries ->
            // The lock file belongs to whoever held the directory, not to the format.
            for (entry in entries) if (entry.name != "LOCK") entry.copyTo(target.resolve(entry.name))
        }
        return target
    }
}

/** [StoreOptions] has no `copy`, on purpose; this is the one place a test needs one. */
internal fun StoreOptions.copyWith(observer: CompositeSegmentObserver): StoreOptions = StoreOptions(
    durability = durability,
    memtableMaxBytes = memtableMaxBytes,
    recoveryMode = recoveryMode,
    createIfMissing = createIfMissing,
    segmentMaxBytes = segmentMaxBytes,
    blockSize = blockSize,
    bloomBitsPerKey = bloomBitsPerKey,
    l0CompactionTrigger = l0CompactionTrigger,
    baseLevelBytes = baseLevelBytes,
    levelSizeMultiplier = levelSizeMultiplier,
    backgroundMaintenance = backgroundMaintenance,
    segmentObserver = observer,
)
