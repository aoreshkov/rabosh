package app.oreshkov.rabosh.core

/**
 * A snapshot of what the store is holding, for tests, benchmarks and operational visibility.
 *
 * Taken without the write lock, so the figures are individually accurate and mutually approximate —
 * which is the right trade for numbers whose purpose is to be watched rather than to be reasoned
 * with.
 */
public class StoreStats internal constructor(
    /** Sequence number of the last committed operation; `0` before anything is written. */
    public val lastSequence: Long,
    /** Approximate size of the active memtable. See [StoreOptions.memtableMaxBytes]. */
    public val memtableBytes: Long,
    /** Versions held in the active memtable, counting superseded ones. */
    public val memtableEntries: Int,
    /** Memtables that have been sealed and are awaiting a segment. */
    public val sealedMemtables: Int,
    /** Number of the log currently being appended to. */
    public val logNumber: Long,
    /** Bytes written to the current log, including its header. */
    public val logBytes: Long,
    /** Sorted segments the live version names, across every level. */
    public val segmentCount: Int,
    /** Total size of those segments on disk. */
    public val segmentBytes: Long,
    /**
     * Segments per level, index 0 being level 0.
     *
     * The shape of the tree in one line. A level 0 that stays at or above
     * [StoreOptions.l0CompactionTrigger] means compaction is not keeping up with ingest, which is
     * the first thing to look at when reads slow down.
     */
    public val segmentsPerLevel: List<Int>,
    /**
     * Snapshots currently open.
     *
     * Worth watching: every one of them holds back the versions a compaction would otherwise drop,
     * so a snapshot nobody closed shows up here long before it shows up as disk usage.
     */
    public val liveSnapshots: Int,
) {
    override fun toString(): String =
        "StoreStats(lastSequence=$lastSequence, memtableBytes=$memtableBytes, " +
            "memtableEntries=$memtableEntries, sealedMemtables=$sealedMemtables, " +
            "logNumber=$logNumber, logBytes=$logBytes, segmentCount=$segmentCount, " +
            "segmentBytes=$segmentBytes, segmentsPerLevel=$segmentsPerLevel, " +
            "liveSnapshots=$liveSnapshots)"
}
