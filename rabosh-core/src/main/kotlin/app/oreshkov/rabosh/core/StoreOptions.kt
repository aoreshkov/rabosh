package app.oreshkov.rabosh.core

/** When a commit is forced to stable storage. */
public enum class Durability {
    /**
     * Every commit is `fsync`ed before the call returns. A returned [DocumentStore.put] therefore
     * survives power loss, not merely process death.
     *
     * This is the default because the engine's central promise is that reopening yields exactly the
     * acknowledged prefix, and under [BUFFERED] "acknowledged" would silently come to mean
     * "acknowledged, unless the machine loses power in the next few seconds".
     */
    SYNC,

    /**
     * Commits are written to the operating system but not forced. They survive a process crash —
     * `kill -9` does not discard the page cache — and are lost only if the machine itself stops.
     *
     * Orders of magnitude faster for bulk ingest, where the sensible pattern is to load, call
     * [DocumentStore.sync], and only then report success to whoever asked for the load.
     */
    BUFFERED,
}

/** How much of a damaged log the engine is willing to read. */
public enum class LogRecoveryMode {
    /**
     * Stop at the first record that does not validate, provided nothing readable follows it.
     *
     * This is the honest reading of an interrupted write. The engine appends a commit and then
     * `fsync`s it; if it dies between those two steps, the tail of the log holds bytes that were
     * never acknowledged to anybody. Dropping them loses nothing anyone was told about, and the
     * file is truncated back to its last good offset on reopen so the next append does not sit
     * behind rubbish.
     *
     * What this mode still refuses to do is silently lose *acknowledged* data. A record whose
     * checksum fails while a valid record follows it is corruption, not a torn tail, and is
     * reported. So is a gap in the sequence numbers, and so is any fault in a log that is not the
     * newest one — a sealed log was `fsync`ed before the next one was created, so it cannot have a
     * torn tail.
     */
    TOLERATE_TORN_TAIL,

    /**
     * Refuse to open when any byte of any log is unreadable, torn tail included.
     *
     * For the case where an incomplete recovery must be an operator's decision rather than the
     * engine's: forensics, format-compatibility testing, or a deployment that would rather stop
     * than come up having quietly dropped the last commit it never promised.
     */
    STRICT,
}

/** Default memtable ceiling: 64 MiB, the same working-set size RocksDB defaults to. */
public const val DEFAULT_MEMTABLE_MAX_BYTES: Long = 64L * 1024 * 1024

/** Default ceiling on one sorted segment: 8 MiB. */
public const val DEFAULT_SEGMENT_MAX_BYTES: Long = 8L * 1024 * 1024

/**
 * Default block size: 4 KiB, one page.
 *
 * A block is the smallest unit a read touches, so this is the read amplification of a point
 * lookup. Blocks are filled to *at least* this size and then closed, so a document larger than a
 * block gets one to itself rather than being split — nothing in the format spans blocks.
 */
public const val DEFAULT_BLOCK_SIZE: Int = 4 * 1024

/** Default bloom budget: 10 bits per key, a roughly 1% false-positive rate. */
public const val DEFAULT_BLOOM_BITS_PER_KEY: Int = 10

/** Default number of level-0 segments that triggers a compaction. */
public const val DEFAULT_L0_COMPACTION_TRIGGER: Int = 4

/** Default byte budget for level 1; each level below it is [DEFAULT_LEVEL_SIZE_MULTIPLIER] times larger. */
public const val DEFAULT_BASE_LEVEL_BYTES: Long = 32L * 1024 * 1024

/** Default growth factor between levels. */
public const val DEFAULT_LEVEL_SIZE_MULTIPLIER: Int = 10

/** Number of levels below level 0. Seven levels at a factor of ten reach far past any embedded store. */
public const val LEVEL_COUNT: Int = 7

/**
 * Tuning for [DocumentStore.open].
 *
 * A plain class with default arguments rather than a `data class`: `copy` and `componentN` would
 * become part of the published ABI, and adding an option later would then be a binary-incompatible
 * change to a type whose whole purpose is to grow.
 */
public class StoreOptions(
    /** When commits reach stable storage. See [Durability]. */
    public val durability: Durability = Durability.SYNC,
    /**
     * Approximate ceiling on the active memtable, in bytes. Reaching it seals the memtable and
     * starts a new log; see [DocumentStore.rotate].
     *
     * Approximate because the figure counts key, metadata and value bytes plus a fixed estimate of
     * per-entry overhead, not the real retained heap, which no portable API reports.
     */
    public val memtableMaxBytes: Long = DEFAULT_MEMTABLE_MAX_BYTES,
    /** How much of a damaged log may be read. See [LogRecoveryMode]. */
    public val recoveryMode: LogRecoveryMode = LogRecoveryMode.TOLERATE_TORN_TAIL,
    /** Whether [DocumentStore.open] may create the directory. */
    public val createIfMissing: Boolean = true,
    /**
     * Approximate ceiling on one sorted segment, in bytes.
     *
     * Compaction cuts its output here. Smaller segments make each compaction cheaper and its output
     * overlap fewer files in the level below; larger ones mean fewer files to consult on a read.
     */
    public val segmentMaxBytes: Long = DEFAULT_SEGMENT_MAX_BYTES,
    /** Approximate size of a segment's data blocks. See [DEFAULT_BLOCK_SIZE]. */
    public val blockSize: Int = DEFAULT_BLOCK_SIZE,
    /**
     * Bits of bloom filter per distinct key in a segment.
     *
     * Zero disables the filter, which is only sensible for a workload that never asks about a key
     * that is not there — and that workload is rarer than the people who have it believe.
     */
    public val bloomBitsPerKey: Int = DEFAULT_BLOOM_BITS_PER_KEY,
    /** Level-0 segments that trigger a compaction. See [DEFAULT_L0_COMPACTION_TRIGGER]. */
    public val l0CompactionTrigger: Int = DEFAULT_L0_COMPACTION_TRIGGER,
    /** Byte budget for level 1. */
    public val baseLevelBytes: Long = DEFAULT_BASE_LEVEL_BYTES,
    /** Growth factor between levels. */
    public val levelSizeMultiplier: Int = DEFAULT_LEVEL_SIZE_MULTIPLIER,
    /**
     * Whether flush and compaction run on a background thread.
     *
     * `true` is what a real store wants: a writer schedules the work and carries on rather than
     * waiting behind a merge of somebody else's data. `false` is for tests and benchmarks, which
     * need to know exactly when maintenance happened — [DocumentStore.flush] and
     * [DocumentStore.compact] then do the work on the calling thread and return when it is done.
     */
    public val backgroundMaintenance: Boolean = true,
    /**
     * Where documents are reported as they are written into segments. See [SegmentObserver].
     *
     * This is the seam the modelling and indexing layers attach through, and it belongs in the
     * options rather than in a setter because flush and compaction may begin the moment the store is
     * open: an observer installed afterwards would silently miss whatever was written in between,
     * and a model with a hole in it is worse than no model. Use [DocumentStore.backfill] to cover
     * data that was written before the observer existed.
     */
    public val segmentObserver: SegmentObserver? = null,
) {
    init {
        require(memtableMaxBytes > 0) { "memtableMaxBytes must be positive, was $memtableMaxBytes" }
        require(segmentMaxBytes > 0) { "segmentMaxBytes must be positive, was $segmentMaxBytes" }
        require(blockSize > 0) { "blockSize must be positive, was $blockSize" }
        require(bloomBitsPerKey in 0..64) { "bloomBitsPerKey must be in 0..64, was $bloomBitsPerKey" }
        require(l0CompactionTrigger > 0) { "l0CompactionTrigger must be positive, was $l0CompactionTrigger" }
        require(baseLevelBytes > 0) { "baseLevelBytes must be positive, was $baseLevelBytes" }
        require(levelSizeMultiplier > 1) { "levelSizeMultiplier must exceed 1, was $levelSizeMultiplier" }
    }

    /**
     * These options with a different [segmentObserver], everything else unchanged.
     *
     * The one field a layer above legitimately needs to replace: composing several observers into
     * this single slot is what `rabosh-api` exists to do, and without this it would have to restate
     * every option here to do it — so an option added below would be silently dropped by a facade
     * that had not been updated. There is deliberately no general `copy`: this is not a `data class`,
     * for the reason above the constructor.
     */
    public fun withSegmentObserver(segmentObserver: SegmentObserver?): StoreOptions = StoreOptions(
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
        segmentObserver = segmentObserver,
    )

    /** Byte budget for [level], which is `1..LEVEL_COUNT`. Level 0 is bounded by file count instead. */
    internal fun levelBudget(level: Int): Long {
        var budget = baseLevelBytes
        repeat(level - 1) { budget *= levelSizeMultiplier }
        return budget
    }

    override fun toString(): String =
        "StoreOptions(durability=$durability, memtableMaxBytes=$memtableMaxBytes, " +
            "recoveryMode=$recoveryMode, createIfMissing=$createIfMissing, " +
            "segmentMaxBytes=$segmentMaxBytes, blockSize=$blockSize, " +
            "bloomBitsPerKey=$bloomBitsPerKey, l0CompactionTrigger=$l0CompactionTrigger, " +
            "baseLevelBytes=$baseLevelBytes, levelSizeMultiplier=$levelSizeMultiplier, " +
            "backgroundMaintenance=$backgroundMaintenance, " +
            "segmentObserver=${segmentObserver?.let { it::class.simpleName } ?: "none"})"

    public companion object {
        /** Durable commits, a 64 MiB memtable, and a tolerated torn tail. */
        public val DEFAULT: StoreOptions = StoreOptions()
    }
}
