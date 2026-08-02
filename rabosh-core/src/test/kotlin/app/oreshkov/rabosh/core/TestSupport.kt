package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.copyTo
import kotlin.io.path.name

/** A document whose contents identify [index], so a recovered store can be checked against it. */
internal fun documentFor(index: Int): Variant =
    Variant.fromJson("""{"index":$index,"label":"document-$index","tags":["a","b"]}""")

/** The key used for document [index] by every test that writes a numbered run. */
internal fun keyFor(index: Int): Key = Key.of("key:%06d".format(index))

/** The current value of [key] as JSON text, or `null`. */
internal fun DocumentStore.jsonAt(key: Key): String? = get(key)?.toJsonString()

internal fun logPath(directory: Path, number: Long): Path = directory.resolve(logFileName(number))

internal fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)

internal fun writeAllBytes(path: Path, bytes: ByteArray) {
    Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
}

/** Truncates [path] to [length] bytes, simulating a write that never completed. */
internal fun truncateTo(path: Path, length: Long) {
    java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { it.truncate(length) }
}

/** Flips the lowest bit of the byte at [offset], simulating a single-bit fault on the medium. */
internal fun flipBit(path: Path, offset: Int) {
    val bytes = readAllBytes(path)
    bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
    writeAllBytes(path, bytes)
}

/**
 * Copies a store directory, so one prepared store can be damaged many different ways.
 *
 * Building the original once and copying it is not only faster than rebuilding it: it guarantees
 * every case in a sweep is damaging the *same* bytes, which is what makes the results comparable.
 */
internal fun copyStore(source: Path, target: Path): Path {
    Files.createDirectories(target)
    Files.newDirectoryStream(source).use { entries ->
        for (entry in entries) entry.copyTo(target.resolve(entry.name), overwrite = true)
    }
    return target
}

/** Byte arrays compared and reported as hex, since they are keys and values rather than text. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Copies `[offset, offset + length)` out of a mapped or heap segment. */
internal fun copyOut(segment: java.lang.foreign.MemorySegment, offset: Long, length: Int): ByteArray =
    ByteArray(length).also {
        java.lang.foreign.MemorySegment.copy(
            segment,
            java.lang.foreign.ValueLayout.JAVA_BYTE,
            offset,
            it,
            0,
            length,
        )
    }

/** A [SegmentBytes] over a heap array, so block and footer code can be tested without a file. */
internal fun segmentBytesOf(bytes: ByteArray, file: String = "test.seg"): SegmentBytes =
    SegmentBytes(java.lang.foreign.MemorySegment.ofArray(bytes), file)

private val scratchCounter = AtomicLong(0)

/** A fresh subdirectory of [root]; each call returns a new one. */
internal fun scratch(root: Path, prefix: String = "store"): Path =
    root.resolve("$prefix-${scratchCounter.incrementAndGet()}")
