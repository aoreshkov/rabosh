package app.oreshkov.rabosh.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a sealed memtable out as one level-0 segment.
 *
 * The order is the whole of the durability argument, and it is the same shape as the write path's:
 * the segment is written **and forced** first, the manifest record naming it is written and forced
 * second, and only then are the logs it accounts for deleted. A crash between the first and the
 * second leaves an unreferenced file, which the next open sweeps away; a crash after the second
 * leaves logs that will be replayed into a memtable whose contents are already in a segment, which
 * is harmless because the segment holds the same versions at the same sequence numbers.
 *
 * @param nextLogNumber the oldest log still needed *after* this flush — the log number of whatever
 *   memtable becomes the oldest unflushed one. Everything below it is deleted.
 */
internal fun flushMemtable(
    directory: Path,
    options: StoreOptions,
    versions: VersionSet,
    sealed: SealedMemtable,
    nextLogNumber: Long,
): SegmentMetadata? {
    if (sealed.memtable.isEmpty()) return null

    val number = versions.newFileNumber()
    val path = directory.resolve(segmentFileName(number))
    val writer = SegmentWriter(path, number, options)
    val metadata = try {
        MemtableCursor(sealed.memtable).use { cursor ->
            cursor.seekToFirst()
            while (cursor.valid()) {
                writer.add(cursor.key, cursor.keyLength, cursor.document())
                cursor.next()
            }
        }
        writer.finish()
    } catch (failure: Throwable) {
        writer.close()
        throw failure
    }

    val edit = VersionEdit().add(0, metadata)
    edit.logNumber = nextLogNumber
    edit.lastSequence = metadata.largestSequence
    versions.apply(edit)

    deleteObsoleteLogs(directory, nextLogNumber)
    return metadata
}

/** Removes logs whose contents are all in segments. Failures are left for the next open to retry. */
internal fun deleteObsoleteLogs(directory: Path, keepFrom: Long) {
    for (file in listStoreFiles(directory)) {
        if (file.kind == StoreFileKind.LOG && file.number < keepFrom) {
            runCatching { Files.deleteIfExists(directory.resolve(file.name)) }
        }
    }
}

/**
 * Which level wants compacting, and which files it would merge.
 *
 * Level 0 is scored by **file count** and every other level by **bytes**, because they are bounded
 * by different things. Level 0's files overlap, so every one of them is consulted on every lookup
 * that misses — its cost is a count. Below that, files do not overlap and a lookup touches one, so
 * what matters is how much data has to be rewritten to keep the shape, which is a size.
 */
internal class Compaction(
    val level: Int,
    val outputLevel: Int,
    val inputs: List<SegmentTable>,
    val overlaps: List<SegmentTable>,
) {
    val all: List<SegmentTable> get() = inputs + overlaps

    override fun toString(): String =
        "Compaction(L$level -> L$outputLevel, ${inputs.size} + ${overlaps.size} file(s))"
}

/**
 * Picks the compaction most worth doing, or `null` when the tree is in shape.
 *
 * The pointer per level is what keeps compaction from grinding the same key range for ever: a level
 * is compacted a file at a time, moving rightwards and wrapping, so every part of it gets a turn.
 * Without it a level whose first file always scores highest would starve the rest.
 */
internal class CompactionPicker(private val options: StoreOptions) {
    private val pointers = arrayOfNulls<Key>(LEVEL_COUNT + 1)

    fun pick(version: Version): Compaction? {
        var bestLevel = -1
        var bestScore = 1.0
        for (level in 0 until LEVEL_COUNT) {
            val score = if (level == 0) {
                version.countAt(0).toDouble() / options.l0CompactionTrigger
            } else {
                version.bytesAt(level).toDouble() / options.levelBudget(level)
            }
            if (score >= bestScore) {
                bestScore = score
                bestLevel = level
            }
        }
        if (bestLevel < 0) return null

        val inputs = if (bestLevel == 0) {
            // Level 0 overlaps, so a compaction of it has to take all of it: merging a subset would
            // leave versions of the same key on both sides of the boundary, with the newer one in
            // the level *below*, where the read path would never look for it first.
            version.levels[0]
        } else {
            listOf(pickByPointer(version, bestLevel) ?: return null)
        }
        if (inputs.isEmpty()) return null

        val from = inputs.minOf { it.metadata.smallestKey }
        val to = inputs.maxOf { it.metadata.largestKey }
        val outputLevel = bestLevel + 1
        val overlaps = version.overlapping(outputLevel, from, to)
        pointers[bestLevel] = to
        return Compaction(bestLevel, outputLevel, inputs, overlaps)
    }

    private fun pickByPointer(version: Version, level: Int): SegmentTable? {
        val tables = version.levels[level]
        if (tables.isEmpty()) return null
        val pointer = pointers[level]
        return tables.firstOrNull { pointer == null || it.metadata.largestKey > pointer } ?: tables.first()
    }
}

/**
 * Merges a compaction's inputs into new segments at the level below.
 *
 * Two rules decide what is dropped, and they are stated separately because they fail differently.
 *
 * **A superseded version may go once nothing can ask for it.** A newer version of the same user key
 * has already been emitted, and if that newer version is at or below the oldest live snapshot then
 * every reader that exists sees it rather than this one.
 *
 * **A tombstone may go only at the bottom.** It may be dropped only when no level deeper than the
 * output can contain the key *and* it is below the oldest live snapshot. Dropping one early is the
 * classic LSM bug: the deletion disappears, the deeper level still holds the document, and a
 * deleted document comes back to life. Keeping a tombstone too long costs space; dropping one too
 * early costs correctness, so the asymmetry is deliberate.
 *
 * Output is cut at [StoreOptions.segmentMaxBytes] but **only on a user-key boundary**. Two segments
 * in the same level holding versions of one key would break the non-overlap invariant that makes a
 * lookup below level 0 a binary search.
 */
internal fun runCompaction(
    directory: Path,
    options: StoreOptions,
    versions: VersionSet,
    version: Version,
    compaction: Compaction,
    oldestSnapshot: Long,
) {
    val cursors = compaction.all.map { it.cursor() }
    val outputs = ArrayList<SegmentMetadata>()
    var writer: SegmentWriter? = null

    try {
        MergingCursor(cursors).use { merged ->
            merged.seekToFirst()
            var previousKey: Key? = null
            var lastSequenceForKey = Long.MAX_VALUE

            while (merged.valid()) {
                val userKey = merged.userKey()
                val sequence = merged.sequence()
                val document = merged.document()

                if (userKey != previousKey) {
                    // A new user key: nothing has been emitted for it yet, so nothing hides it.
                    lastSequenceForKey = Long.MAX_VALUE
                    // Only a key boundary is a legal place to cut, so it is the only place we look.
                    val current = writer
                    if (current != null && current.approximateBytes >= options.segmentMaxBytes) {
                        outputs += current.finish()
                        writer = null
                    }
                    previousKey = userKey
                }

                val drop = when {
                    lastSequenceForKey <= oldestSnapshot -> true
                    document == null && sequence <= oldestSnapshot &&
                        !version.mayContainBelow(userKey, compaction.outputLevel) -> true

                    else -> false
                }
                lastSequenceForKey = sequence

                if (!drop) {
                    val target = writer ?: newOutput(directory, versions, options).also { writer = it }
                    target.add(merged.key, merged.keyLength, document)
                }
                merged.next()
            }
        }
        writer?.let { outputs += it.finish() }
        writer = null
    } catch (failure: Throwable) {
        writer?.close()
        outputs.forEach { runCatching { Files.deleteIfExists(directory.resolve(segmentFileName(it.number))) } }
        throw failure
    }

    // One record: the inputs leave and the outputs arrive together, so a crash between them is not
    // a state this design has to be able to recover from.
    val edit = VersionEdit()
    compaction.inputs.forEach { edit.remove(compaction.level, it.number) }
    compaction.overlaps.forEach { edit.remove(compaction.outputLevel, it.number) }
    outputs.forEach { edit.add(compaction.outputLevel, it) }
    versions.apply(edit)
}

private fun newOutput(directory: Path, versions: VersionSet, options: StoreOptions): SegmentWriter {
    val number = versions.newFileNumber()
    return SegmentWriter(directory.resolve(segmentFileName(number)), number, options)
}

/**
 * Whether any level deeper than [level] could hold [key].
 *
 * The precise question the tombstone rule asks. A cheaper approximation — "is this the last
 * level" — would keep every tombstone in the store for ever in a tree that never reaches its
 * bottom level, which is most trees.
 */
internal fun Version.mayContainBelow(key: Key, level: Int): Boolean {
    for (deeper in (level + 1) until levels.size) {
        if (levels[deeper].any { key >= it.metadata.smallestKey && key <= it.metadata.largestKey }) return true
    }
    return false
}
