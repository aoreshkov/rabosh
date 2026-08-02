package app.oreshkov.rabosh.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One immutable picture of which segments are live and at which level.
 *
 * **Level 0 holds whatever flushes produced, so its files overlap** and are consulted newest first.
 * **Levels 1 and below hold non-overlapping runs sorted by key**, so a lookup there is a binary
 * search that touches one file. That difference is the whole shape of the read path.
 *
 * A version is reference counted and holds a reference to every segment in it. Installing a new
 * version releases the old one, and a segment whose last reference goes is unmapped and its file
 * deleted — but not before, which is why a reader that is inside a file a compaction has just
 * replaced is safe rather than lucky.
 */
internal class Version(
    /** Index 0 is level 0, newest segment first; index `n` is level `n`, ordered by key. */
    val levels: List<List<SegmentTable>>,
) {
    private val references = AtomicInteger(1)

    init {
        for (level in levels) {
            for (table in level) {
                check(table.acquire()) { "segment ${table.number} was released before the version holding it" }
            }
        }
    }

    fun acquire() {
        references.incrementAndGet()
    }

    fun release() {
        if (references.decrementAndGet() == 0) {
            for (level in levels) {
                for (table in level) table.release()
            }
        }
    }

    /** Every segment in the version, whatever level it sits at. */
    fun segments(): List<SegmentTable> = levels.flatten()

    val segmentCount: Int get() = levels.sumOf { it.size }

    val totalBytes: Long get() = levels.sumOf { level -> level.sumOf { it.metadata.fileBytes } }

    fun bytesAt(level: Int): Long =
        levels.getOrNull(level)?.sumOf { it.metadata.fileBytes } ?: 0

    fun countAt(level: Int): Int = levels.getOrNull(level)?.size ?: 0

    /**
     * The newest version of [key] at or below [maxSequence], or `null` if no segment holds one.
     *
     * The order is not an optimisation; it is the answer. Level 0's files overlap, so the newest
     * has to be consulted first, and a hit at any level — tombstone included — stops the search.
     * Carrying on past a tombstone would find the document a deeper level still holds and undo the
     * deletion.
     */
    fun get(key: Key, maxSequence: Long): Found? {
        for (table in levels[0]) {
            table.get(key, maxSequence)?.let { return it }
        }
        for (index in 1 until levels.size) {
            val table = findOverlapping(levels[index], key) ?: continue
            table.get(key, maxSequence)?.let { return it }
        }
        return null
    }

    /** Segments at [level] whose key range overlaps `[from, to]`, either bound absent meaning open. */
    fun overlapping(level: Int, from: Key?, to: Key?): List<SegmentTable> =
        levels.getOrNull(level).orEmpty().filter { it.metadata.overlaps(from, to) }

    /**
     * The one segment in a sorted, non-overlapping level that could hold [key].
     *
     * A binary search rather than a scan: at level 5 a store may hold thousands of files, and
     * looking at each one's key range would make the deepest level the most expensive.
     */
    private fun findOverlapping(level: List<SegmentTable>, key: Key): SegmentTable? {
        var low = 0
        var high = level.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val metadata = level[middle].metadata
            when {
                key < metadata.smallestKey -> high = middle - 1
                key > metadata.largestKey -> low = middle + 1
                else -> return level[middle]
            }
        }
        return null
    }

    override fun toString(): String =
        "Version(" + levels.mapIndexed { index, level -> "L$index=${level.size}" }.joinToString() + ")"
}

/**
 * The live version, the manifest that records it, and the file numbers both draw on.
 *
 * Everything that changes the shape of the tree goes through [apply]: it writes one manifest record,
 * forces it, opens whatever segments the new version needs, installs it, and releases the old one.
 * That order matters — the manifest is on the platter before the version is visible, so a crash
 * between the two leaves a store that opens on the *new* version, whose files are all present,
 * rather than on a version whose inputs a compaction has already deleted.
 *
 * Not thread-safe by itself; [DocumentStore] serialises writers and mutates only under its own
 * lock. Readers take a [Version] through [acquireCurrent], which is guarded here.
 */
internal class VersionSet(
    private val directory: Path,
    private val options: StoreOptions,
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val openTables = HashMap<Long, SegmentTable>()

    /**
     * Segments that have left the tree and are to be deleted once nothing is reading them.
     *
     * A set rather than a flag on the table, and consulted rather than assumed, because the other
     * way a mapping goes away is [close] — and deleting the live segments of a store that is simply
     * shutting down would be the worst bug in the engine. Deletion follows *departure*, never
     * unmapping on its own.
     */
    private val departed = HashSet<Long>()

    @Volatile
    private var currentVersion: Version = Version(List(LEVEL_COUNT + 1) { emptyList() })

    private var manifest: ManifestWriter? = null
    private var manifestNumber = 0L
    private var nextFile = 1L

    /** Oldest log still needed. Logs numbered below it have been flushed into segments. */
    var logNumber: Long = 0
        private set

    /** Last sequence number the store has committed, as of the last manifest record. */
    var lastSequence: Long = 0
        private set

    /** Segment file numbers that failed to delete; retried on the next open. */
    private var undeleted = 0

    val current: Version get() = currentVersion

    val manifestBytes: Long get() = manifest?.bytesWritten ?: 0

    /** Takes a reference on the live version. The caller must release it. */
    fun acquireCurrent(): Version = lock.withLock {
        currentVersion.also { it.acquire() }
    }

    fun newFileNumber(): Long = lock.withLock { nextFile++ }

    /**
     * Ensures no number at or below [highest] is ever handed out again.
     *
     * A log or a segment can exist on disk without the manifest knowing its number: the number is
     * taken, the file is created, and the process dies before the edit that records it. Reissuing
     * that number would meet a file that already exists, and `CREATE_NEW` would refuse — so the
     * directory listing, not the manifest, is the last word on what has been used.
     */
    fun reserveFileNumbersAbove(highest: Long): Unit = lock.withLock {
        nextFile = maxOf(nextFile, highest + 1)
    }

    fun rememberSequence(sequence: Long) = lock.withLock {
        lastSequence = maxOf(lastSequence, sequence)
    }

    /**
     * Records [edit] and installs the version it produces.
     *
     * @throws IOException if the manifest cannot be written, in which case nothing is installed and
     *   the previous version remains the live one.
     */
    fun apply(edit: VersionEdit) {
        val live = lock.withLock {
            edit.nextFileNumber = nextFile
            edit.logNumber = edit.logNumber ?: logNumber
            edit.lastSequence = edit.lastSequence ?: lastSequence

            val writer = checkNotNull(manifest) { "the version set has no manifest open" }
            writer.append(edit)

            installLocked(applyToMetadata(currentMetadata(), edit), edit)
        }
        // Outside the lock. An observer is somebody else's code and may do file IO of its own;
        // running it while holding the lock every read takes to pin a version would let a slow
        // catalog stall the read path.
        Observers.retain(options.segmentObserver, live)
    }

    /** The level metadata of the live version, as plain data the edit can be folded into. */
    private fun currentMetadata(): MutableList<MutableList<SegmentMetadata>> =
        MutableList(LEVEL_COUNT + 1) { level ->
            currentVersion.levels.getOrNull(level).orEmpty().mapTo(ArrayList()) { it.metadata }
        }

    private fun applyToMetadata(
        levels: MutableList<MutableList<SegmentMetadata>>,
        edit: VersionEdit,
    ): MutableList<MutableList<SegmentMetadata>> {
        for ((level, number) in edit.removed) {
            levels[level].removeAll { it.number == number }
        }
        for ((level, segment) in edit.added) {
            levels[level] += segment
        }
        return levels
    }

    /** Installs a new version and returns the file numbers it names, for [SegmentObserver.retain]. */
    private fun installLocked(
        levels: MutableList<MutableList<SegmentMetadata>>,
        edit: VersionEdit,
    ): Set<Long> {
        edit.logNumber?.let { logNumber = it }
        edit.lastSequence?.let { lastSequence = maxOf(lastSequence, it) }
        edit.nextFileNumber?.let { nextFile = maxOf(nextFile, it) }

        // Level 0 overlaps, so it is ordered by recency; every other level is ordered by key, which
        // is what makes the binary search in `Version.get` legitimate.
        levels[0].sortByDescending { it.number }
        for (level in 1..LEVEL_COUNT) levels[level].sortBy { it.smallestKey }

        val tables = levels.map { level -> level.map(::tableFor) }
        val previous = currentVersion
        val next = Version(tables)
        currentVersion = next
        previous.release()

        // Anything the new version does not name has left the tree. Dropping the version set's own
        // reference is what lets the last reader's release unmap the file and delete it.
        val live = levels.flatten().mapTo(HashSet()) { it.number }
        for (number in openTables.keys.filter { it !in live }) {
            departed += number
            openTables.remove(number)?.release()
        }
        return live
    }

    private fun tableFor(metadata: SegmentMetadata): SegmentTable = openTables.getOrPut(metadata.number) {
        SegmentTable.open(directory.resolve(segmentFileName(metadata.number)), metadata, ::deleteOnUnmap)
    }

    /**
     * Deletes a segment's file once its mapping is gone.
     *
     * The order is not negotiable on Windows, which refuses to delete a mapped file — so a leaked
     * mapping there is a store that never reclaims a byte. A failure to delete is recorded and
     * otherwise ignored: the file is already unreachable, no version names it, and the orphan sweep
     * on the next open will try again.
     */
    private fun deleteOnUnmap(table: SegmentTable) {
        if (table.number !in departed) return
        try {
            Files.deleteIfExists(table.path)
            departed -= table.number
        } catch (ignored: IOException) {
            undeleted++
        }
    }

    /** Segment files that could not be deleted so far. Watched by tests, not by callers. */
    val undeletedFiles: Int get() = undeleted

    /**
     * Reads `CURRENT` and the manifest it names, and installs the version they describe.
     *
     * @return `true` if a manifest was found, `false` for a directory that has never held one.
     */
    fun recover(): Boolean {
        val number = CurrentFile.read(directory) ?: return false
        val path = directory.resolve(manifestFileName(number))
        if (!Files.exists(path)) {
            throw CorruptManifestException("CURRENT names a manifest that is not there", manifestFileName(number))
        }
        val replay = ManifestReader.replay(path, options.recoveryMode)

        val levels = MutableList<MutableList<SegmentMetadata>>(LEVEL_COUNT + 1) { ArrayList() }
        val edit = VersionEdit()
        for (record in replay.edits) {
            record.logNumber?.let { edit.logNumber = it }
            record.lastSequence?.let { edit.lastSequence = it }
            record.nextFileNumber?.let { edit.nextFileNumber = it }
            applyToMetadata(levels, record)
        }

        val live = lock.withLock {
            manifestNumber = number
            manifest = ManifestWriter.openForAppend(path, number, replay.validLength)
            val installed = installLocked(levels, edit)
            // Every file that exists must have a number below the next one to hand out, or a crash
            // that lost the manifest's tail would reissue a number a file already has.
            nextFile = maxOf(nextFile, (levels.flatten().maxOfOrNull { it.number } ?: 0) + 1)
            installed
        }
        Observers.retain(options.segmentObserver, live)
        return true
    }

    /**
     * Creates the first manifest for a directory that has never had one.
     *
     * [firstLogNumber] is recorded straight away so that recovery never has to guess which logs a
     * fresh store still needs.
     */
    fun create(firstLogNumber: Long, firstFileNumber: Long) {
        lock.withLock {
            nextFile = firstFileNumber
            logNumber = firstLogNumber
            manifestNumber = nextFile++
            val writer = ManifestWriter.create(directory, manifestNumber)
            manifest = writer
            val edit = VersionEdit().also {
                it.logNumber = firstLogNumber
                it.nextFileNumber = nextFile
                it.lastSequence = 0
            }
            writer.append(edit)
            CurrentFile.write(directory, manifestNumber)
        }
    }

    /**
     * Writes a fresh manifest holding the live version as a single record, and swaps `CURRENT` to it.
     *
     * A manifest is a log of edits, so it grows without bound in a store that compacts. Rewriting it
     * as one snapshot is what bounds recovery time; the old manifest becomes an orphan and is swept
     * on the next open.
     */
    fun rewriteManifest() {
        lock.withLock {
            val number = nextFile++
            val writer = ManifestWriter.create(directory, number)
            try {
                val edit = VersionEdit().also {
                    it.logNumber = logNumber
                    it.lastSequence = lastSequence
                    it.nextFileNumber = nextFile
                }
                currentVersion.levels.forEachIndexed { level, tables ->
                    tables.forEach { edit.add(level, it.metadata) }
                }
                writer.append(edit)
                CurrentFile.write(directory, number)
            } catch (failure: Throwable) {
                writer.close()
                Files.deleteIfExists(directory.resolve(manifestFileName(number)))
                throw failure
            }
            manifest?.close()
            manifest = writer
            manifestNumber = number
        }
    }

    /** File numbers the live version knows about, plus the manifest in force. */
    fun liveFileNumbers(): Set<Long> = lock.withLock {
        currentVersion.segments().mapTo(HashSet()) { it.number } + manifestNumber
    }

    val currentManifestNumber: Long get() = manifestNumber

    override fun close() {
        lock.withLock {
            manifest?.close()
            manifest = null
            currentVersion.release()
            openTables.values.forEach(SegmentTable::release)
            openTables.clear()
        }
    }
}
