package app.oreshkov.rabosh.core

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What a [DocumentStore.checkpoint] produced.
 *
 * @property directory the directory that now holds the checkpoint. Opens as a store.
 * @property sequence the sequence the checkpoint was taken at. Every commit at or below it is in the
 *   copy; nothing above it is.
 * @property segmentCount segments the checkpoint holds.
 * @property fileCount files written, which is the segments plus their sidecars plus a manifest and a
 *   `CURRENT`.
 * @property bytes total size of the data files, as the source reported them. Not the space the
 *   checkpoint *occupies* — see [hardLinked], where the answer is close to nothing.
 * @property hardLinked whether the data files are links to the originals rather than copies. A link
 *   costs a directory entry and no data blocks, which is what makes a checkpoint cheap enough to
 *   take often; it also means the checkpoint shares its blocks with the source, so it is a
 *   consistent *view* and not an off-site backup.
 */
public class CheckpointInfo internal constructor(
    public val directory: Path,
    public val sequence: Long,
    public val segmentCount: Int,
    public val fileCount: Int,
    public val bytes: Long,
    public val hardLinked: Boolean,
) {
    override fun toString(): String =
        "CheckpointInfo($directory at sequence $sequence, $segmentCount segment(s), " +
            "$fileCount file(s), $bytes byte(s), ${if (hardLinked) "hard-linked" else "copied"})"
}

/**
 * Writes a checkpoint of [version] into [target], at [sequence].
 *
 * **The ordering rule governs the target as much as the source**: *log, then memtable, then segment,
 * then manifest, then delete*. So every data file is in place and durable **before** the manifest
 * that names it is written, and `CURRENT` is written last of all. A checkpoint that forced its
 * manifest before the files it names is the same bug the write path exists to avoid, in a new place
 * — and it fails the same way, as a store that opens and then cannot find a segment.
 *
 * **No log is copied, and that is what [DocumentStore.flush] is for.** A checkpoint is taken at a
 * flushed snapshot, so every commit at or below [sequence] is already in a segment; a copied log
 * would be a second, older copy of data the segments already hold, replayed on open into sequence
 * numbers the manifest has already issued. The checkpoint therefore opens with no log at all, which
 * is a state the recovery path already handles — it is what a store that was closed cleanly and
 * fully flushed looks like.
 *
 * **The caller holds a snapshot open across this call**, which is what stops a compaction reclaiming
 * a segment out from under the copy. That is why the snapshot is part of the design rather than a
 * detail: without it the source is free to delete exactly the files being linked, and on a
 * filesystem where that succeeds the checkpoint would be missing a segment its own manifest names.
 */
internal fun writeCheckpoint(
    source: Path,
    target: Path,
    version: Version,
    sequence: Long,
): CheckpointInfo {
    prepareTarget(target)

    val segments = version.segments()
    val numbers = segments.mapTo(HashSet()) { it.number }

    // Every file the segments own, found by number rather than by extension. `rabosh-core` does not
    // know what a `.cat`, `.idx`, `.pst` or `.col` is and deliberately does not need to: the rule is
    // that a file numbered after a segment belongs to that segment, which is the same rule sidecar
    // reclamation runs on from the other side. A layer added later gets copied with no change here.
    //
    // Logs are excluded by name and not by number. They cannot collide — file numbers come from one
    // counter, so a live segment's number is never also a log's — but saying so costs one line and
    // makes the exclusion a decision rather than an accident.
    val payload = Files.newDirectoryStream(source).use { entries ->
        entries.filter { entry ->
            val name = entry.fileName.toString()
            val file = classifyFile(name)
            when {
                file.kind == StoreFileKind.LOG -> false
                file.kind == StoreFileKind.SEGMENT -> file.number in numbers
                file.kind == StoreFileKind.UNKNOWN -> numberedAfterLiveSegment(name, numbers)
                else -> false
            }
        }.sortedBy { it.fileName.toString() }
    }

    var hardLinked = true
    var bytes = 0L
    for (file in payload) {
        val destination = target.resolve(file.fileName.toString())
        if (!link(file, destination)) hardLinked = false
        bytes += runCatching { Files.size(destination) }.getOrDefault(0L)
    }

    // The data is durable before anything names it. A hard link needs no force — the bytes are the
    // source's, already forced when the segment was written — but a *copy* is this process's own
    // write and is not durable until it says so, and the directory entry needs its own sync either
    // way. Doing this unconditionally costs a checkpoint nothing it can measure and removes the
    // branch where the fallback path is the one nobody tested.
    for (file in payload) forceFile(target.resolve(file.fileName.toString()))
    syncDirectory(target)

    // Only now the manifest, and only then CURRENT.
    val manifestNumber = 1L
    ManifestWriter.create(target, manifestNumber).use { manifest ->
        val edit = VersionEdit()
        edit.logNumber = 0L
        // One above the highest number in use, so the reopened store issues names that collide with
        // nothing it inherited. Derived from the files rather than carried over from the source,
        // whose counter has run on past everything this checkpoint holds.
        edit.nextFileNumber = (numbers.maxOrNull() ?: 0L) + 1L
        edit.lastSequence = sequence
        for ((level, tables) in version.levels.withIndex()) {
            for (table in tables) edit.added += level to table.metadata
        }
        manifest.append(edit)
    }
    CurrentFile.write(target, manifestNumber)

    return CheckpointInfo(
        directory = target,
        sequence = sequence,
        segmentCount = segments.size,
        fileCount = payload.size + 2,
        bytes = bytes,
        hardLinked = hardLinked,
    )
}

/**
 * Whether [name] is a sidecar of one of [numbers].
 *
 * Every file the engine writes beside a segment begins with that segment's ten-digit number —
 * `%010d.cat`, `%010d.idx`, `%010d.%04d.pst`, `%010d.%04d.col`. Matching on the prefix rather than
 * on a list of suffixes is what lets a checkpoint carry a file kind this module has never heard of.
 */
private fun numberedAfterLiveSegment(name: String, numbers: Set<Long>): Boolean {
    if (name.length < NUMBER_DIGITS || name.getOrNull(NUMBER_DIGITS) != '.') return false
    val number = name.take(NUMBER_DIGITS).toLongOrNull() ?: return false
    return number in numbers
}

private const val NUMBER_DIGITS = 10

/**
 * Creates [target] and refuses to write into one that already holds anything.
 *
 * A checkpoint written over an existing store would produce a directory whose manifest names this
 * snapshot's segments while the files of another one sit beside them — which opens, and is wrong.
 * Refusing is the only safe answer, and it is a refusal rather than a delete because the alternative
 * is a method that empties a directory the caller named by mistake.
 */
private fun prepareTarget(target: Path) {
    if (Files.exists(target)) {
        if (!Files.isDirectory(target)) throw FileAlreadyExistsException("$target exists and is not a directory")
        val occupied = Files.newDirectoryStream(target).use { it.iterator().hasNext() }
        if (occupied) {
            throw FileAlreadyExistsException(
                "$target is not empty; a checkpoint is written into a new directory, never merged into a store",
            )
        }
    } else {
        Files.createDirectories(target)
    }
}

/** Links [source] to [destination], falling back to a copy. Answers whether the link was taken. */
private fun link(source: Path, destination: Path): Boolean = try {
    Files.createLink(destination, source)
    true
} catch (unsupported: UnsupportedOperationException) {
    // The filesystem has no hard links at all.
    copy(source, destination)
    false
} catch (refused: IOException) {
    // A different device, a link count already at its maximum, or a filesystem that permits links
    // but not this one. Every case has the same answer and none of them is a checkpoint failure.
    copy(source, destination)
    false
}

private fun copy(source: Path, destination: Path) {
    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
}

private fun forceFile(path: Path) {
    java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
}
