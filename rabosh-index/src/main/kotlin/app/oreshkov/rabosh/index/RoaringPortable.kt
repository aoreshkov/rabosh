package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import java.lang.foreign.MemorySegment

/**
 * Import and export in the RoaringBitmap **portable serialization format**.
 *
 * rabosh's own bitmap layout is not this one, for the five reasons [BitmapFormat] gives — and the
 * whole read path depends on those reasons, so the layout is not going to change. What this adds is an
 * exchange: the portable format is what Lucene, Druid, Spark, CRoaring and pyroaring read and write, so
 * a set of document ordinals can leave this engine and be understood, or arrive from one of them and be
 * queried.
 *
 * ```kotlin
 * val exported = RoaringPortable.encode(bitmap)     // hand to Lucene, Spark, pyroaring, …
 * val imported = RoaringPortable.decode(exported)   // and back, as an ordinary Bitmap
 * ```
 *
 * **This is an exchange format, not the storage form**, and everything about it follows from that.
 * Nothing rabosh writes to disk is in this format, no sidecar reads it, and no id here is one of the
 * permanent ones in `.claude/rules/format-permanence.md` — they belong to a specification this
 * project does not own. A stream is always decoded into an ordinary heap [Bitmap] rather than read
 * in place, which is the one thing
 * [BitmapView] refuses to do for rabosh's own format: this layout's offset header is *conditional*,
 * so finding a container is a branch rather than arithmetic, and that is precisely what the format was
 * declined for as a storage form.
 *
 * The two directions are not symmetric, and the asymmetry is honest rather than a gap. [encode] always
 * writes the smallest encoding of each container, so it produces what RoaringBitmap produces after
 * `runOptimize()`; a stream written *without* `runOptimize()` uses no run containers at all, and
 * [decode] reads that perfectly well while [encode] will never write it. Re-exporting such a stream
 * therefore shrinks it, and does not return the bytes that came in.
 */
@RaboshExperimental
public object RoaringPortable {

    /**
     * [bitmap] in the portable format.
     *
     * Each container is written in whichever of the three encodings is smallest for it — the same rule
     * rabosh's own format uses, over this format's two-byte-cheaper run container, which is why
     * [RoaringPortableFormat.kindFor] is a separate function from [BitmapFormat.smallestKind]. Two
     * consequences worth knowing:
     *
     * - the output is **canonical**, so two bitmaps holding the same ordinals export to identical
     *   bytes however they were built, exactly as [ReadableBitmap.encode] does for rabosh's format;
     * - the output is byte-identical to what RoaringBitmap itself writes for the same ordinals after
     *   `runOptimize()`, which the committed conformance fixtures assert rather than assume.
     *
     * An empty bitmap is eight bytes: the no-run cookie, a zero count and an empty offset header.
     */
    public fun encode(bitmap: ReadableBitmap): ByteArray {
        val source = bitmap.containerSource()
        val count = source.containerCount

        val containers = arrayOfNulls<ReadableContainer>(count)
        val kinds = IntArray(count)
        var runContainers = 0
        var bodyBytes = 0
        for (index in 0 until count) {
            val container = source.containerAt(index)
            val kind = RoaringPortableFormat.kindFor(container.cardinality, container.runCount)
            containers[index] = container
            kinds[index] = kind
            if (kind == BitmapFormat.KIND_RUN) runContainers++
            bodyBytes += RoaringPortableFormat.containerBytes(kind, container.cardinality, container.runCount)
        }

        val anyRun = runContainers > 0
        val runBitmapBytes = if (anyRun) (count + 7) / 8 else 0
        val hasOffsets = !anyRun || count >= RoaringPortableFormat.NO_OFFSET_THRESHOLD
        val headerBytes =
            if (anyRun) RoaringPortableFormat.RUN_HEADER_BYTES + runBitmapBytes
            else RoaringPortableFormat.PLAIN_HEADER_BYTES
        val out = IndexWriter(
            headerBytes +
                RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * count +
                (if (hasOffsets) RoaringPortableFormat.OFFSET_BYTES * count else 0) +
                bodyBytes,
        )

        if (anyRun) {
            // `count - 1` rides in the high half, which is why this branch cannot be reached with no
            // containers: a stream with none has no run containers either.
            out.writeU32(RoaringPortableFormat.SERIAL_COOKIE or ((count - 1) shl 16))
            for (byteIndex in 0 until runBitmapBytes) {
                var bits = 0
                for (bit in 0 until 8) {
                    val container = byteIndex * 8 + bit
                    if (container < count && kinds[container] == BitmapFormat.KIND_RUN) bits = bits or (1 shl bit)
                }
                out.writeByte(bits)
            }
        } else {
            out.writeU32(RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER)
            out.writeU32(count)
        }

        for (index in 0 until count) {
            out.writeU16(source.keyAt(index))
            // Stored one short, so that a container holding all 65 536 values still fits sixteen bits —
            // the same trick `lengthMinusOne` plays in a run, and the reason a cardinality of zero
            // cannot be expressed. It never needs to be: an empty container is not in the directory.
            out.writeU16(checkNotNull(containers[index]).cardinality - 1)
        }

        val offsetFields = IntArray(count)
        if (hasOffsets) {
            for (index in 0 until count) {
                offsetFields[index] = out.size
                out.writeU32(0)
            }
        }

        for (index in 0 until count) {
            if (hasOffsets) out.patchU32(offsetFields[index], out.size)
            writeContainer(out, checkNotNull(containers[index]), kinds[index])
        }
        return out.toByteArray()
    }

    /**
     * The bitmap a portable-format stream holds.
     *
     * [source] names the origin of the bytes in any failure, the way a file name does everywhere else
     * in this module; it is not a path and nothing opens it.
     *
     * Three things about the checking are decisions rather than defaults:
     *
     * - **Every container is validated as it is built** — an array's values strictly ascend, a bitset's
     *   population count is the cardinality its header declared, a run list's runs ascend, are separated
     *   by at least one absent value and sum to that cardinality. [BitmapView.open] deliberately defers
     *   all of this to [BitmapView.verify] because it reads *in place* and a walk would defeat the
     *   point. Here the values are being copied onto the heap regardless, so the walk is already paid
     *   for and refusing to look would be a choice to trust bytes this engine did not write.
     * - **Containers are bounds-checked, not required to tile the stream.** [BitmapView.open] requires
     *   its blocks to tile its slice exactly, which catches truncation, a gap, an overlap and trailing
     *   bytes in one check — sound because the slice is ours and its writer is this file. A foreign
     *   writer's offset header may legitimately point anywhere inside its payload, so each container is
     *   required to *fit* and nothing is claimed about the space between them.
     * - **A value this engine cannot hold is unsupported, not damage.** The portable format is over
     *   *unsigned* 32-bit values and a rabosh ordinal is `0..`[BitmapFormat.MAX_ORDINAL], so a stream
     *   from another system may hold values that are intact and unrepresentable here. That is the
     *   distinction [UnsupportedBitmapFormatException] exists for; calling it corruption would send
     *   somebody looking for a disk fault.
     *
     * @throws UnsupportedBitmapFormatException if the cookie is not one this format defines, or the
     *   stream holds a value above [BitmapFormat.MAX_ORDINAL].
     * @throws CorruptBitmapException if the structure does not hold together.
     */
    public fun decode(bytes: ByteArray, source: String = "<roaring>"): Bitmap =
        decode(MemorySegment.ofArray(bytes), 0, bytes.size, source)

    private fun decode(segment: MemorySegment, offset: Long, length: Int, source: String): Bitmap {
        val bytes = IndexBytes(segment, offset, length, source)
        if (length < RoaringPortableFormat.RUN_HEADER_BYTES) {
            bytes.corrupt("a portable roaring bitmap is at least 4 bytes, not $length", 0)
        }

        val cookie = bytes.i32(0, "cookie")
        val count: Int
        val runBitmapBytes: Int
        val descriptiveAt: Int
        val hasOffsets: Boolean
        if (cookie and 0xFFFF == RoaringPortableFormat.SERIAL_COOKIE) {
            count = (cookie ushr 16) + 1
            runBitmapBytes = (count + 7) / 8
            descriptiveAt = RoaringPortableFormat.RUN_HEADER_BYTES + runBitmapBytes
            hasOffsets = count >= RoaringPortableFormat.NO_OFFSET_THRESHOLD
        } else if (cookie == RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER) {
            count = bytes.u32(4, "container count", RoaringPortableFormat.MAX_CONTAINERS)
            runBitmapBytes = 0
            descriptiveAt = RoaringPortableFormat.PLAIN_HEADER_BYTES
            hasOffsets = true
        } else {
            throw UnsupportedBitmapFormatException(
                "$source begins with cookie $cookie; the portable roaring format is " +
                    "${RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER} or " +
                    "${RoaringPortableFormat.SERIAL_COOKIE}",
            )
        }

        val offsetsAt = descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * count
        val bodiesAt = offsetsAt + if (hasOffsets) RoaringPortableFormat.OFFSET_BYTES * count else 0
        bytes.requireRange(0, bodiesAt, "container directory")

        val keys = IntArray(maxOf(count, 1))
        val blocks = arrayOfNulls<Container>(maxOf(count, 1))
        var previousKey = -1
        var position = bodiesAt
        for (index in 0 until count) {
            val entry = descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * index
            val key = bytes.u16(entry, "container key")
            if (key <= previousKey) {
                bytes.corrupt("container keys do not ascend: $key follows $previousKey", entry)
            }
            previousKey = key
            requireRepresentable(key, source)

            val cardinality = bytes.u16(entry + 2, "container cardinality") + 1
            val isRun = runBitmapBytes > 0 &&
                (bytes.u8(RoaringPortableFormat.RUN_HEADER_BYTES + index / 8, "run bitmap") and
                    (1 shl (index % 8))) != 0
            val kind = RoaringPortableFormat.derivedKind(cardinality, isRun)

            val at = if (hasOffsets) {
                bytes.u32(offsetsAt + RoaringPortableFormat.OFFSET_BYTES * index, "container offset", length)
            } else {
                position
            }
            val (container, extent) = readContainer(bytes, at, key, kind, cardinality, source)
            keys[index] = key
            blocks[index] = container
            position = at + extent
        }
        return Bitmap.fromBlocks(keys, blocks, count)
    }

    // --- writing --------------------------------------------------------------------------------

    private fun writeContainer(out: IndexWriter, container: ReadableContainer, kind: Int) {
        when (kind) {
            BitmapFormat.KIND_ARRAY -> {
                val cursor = container.cursor()
                while (cursor.next()) out.writeU16(cursor.low)
            }

            BitmapFormat.KIND_BITSET -> {
                // A bitset is written from the words when the source has them, which is the common case
                // and copies nothing. A run container dense enough to export as a bitset does not, so it
                // fills one 8 KB array — bounded per container, the same bound `BitmapAlgebra` works to.
                if (container is BitsetSource) {
                    for (index in 0 until BitmapFormat.BITSET_WORDS) out.writeLong(container.word(index))
                } else {
                    val words = LongArray(BitmapFormat.BITSET_WORDS)
                    val cursor = container.cursor()
                    while (cursor.next()) words[cursor.low ushr 6] = words[cursor.low ushr 6] or
                        (1L shl (cursor.low and 63))
                    for (word in words) out.writeLong(word)
                }
            }

            else -> {
                out.writeU16(container.runCount)
                val cursor = container.cursor()
                var start = -1
                var previous = -1
                while (cursor.next()) {
                    if (start < 0) {
                        start = cursor.low
                    } else if (cursor.low != previous + 1) {
                        out.writeU16(start)
                        out.writeU16(previous - start)
                        start = cursor.low
                    }
                    previous = cursor.low
                }
                if (start >= 0) {
                    out.writeU16(start)
                    out.writeU16(previous - start)
                }
            }
        }
    }

    // --- reading --------------------------------------------------------------------------------

    /** The container at [at] and how many bytes it occupied. */
    private fun readContainer(
        bytes: IndexBytes,
        at: Int,
        key: Int,
        kind: Int,
        cardinality: Int,
        source: String,
    ): Pair<Container, Int> = when (kind) {
        BitmapFormat.KIND_ARRAY -> readArray(bytes, at, key, cardinality, source) to
            BitmapFormat.arrayBytes(cardinality)

        BitmapFormat.KIND_BITSET -> readBitset(bytes, at, key, cardinality, source) to BitmapFormat.BITSET_BYTES

        else -> readRun(bytes, at, key, cardinality, source)
    }

    private fun readArray(bytes: IndexBytes, at: Int, key: Int, cardinality: Int, source: String): Container {
        val values = CharArray(cardinality)
        var previous = -1
        for (index in 0 until cardinality) {
            val value = bytes.u16(at + index * 2, "array value")
            if (value <= previous) {
                bytes.corrupt("block $key holds $value after $previous in an array container", at + index * 2)
            }
            previous = value
            values[index] = value.toChar()
        }
        requireRepresentable(key, previous, source)
        return ArrayContainer.ofSorted(values, cardinality)
    }

    private fun readBitset(bytes: IndexBytes, at: Int, key: Int, cardinality: Int, source: String): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        bytes.words(at, words, BitmapFormat.BITSET_WORDS, "bitset container")
        var counted = 0
        for (word in words) counted += word.countOneBits()
        if (counted != cardinality) {
            bytes.corrupt("block $key holds $counted value(s) but its header claims $cardinality", at)
        }
        for (index in BitmapFormat.BITSET_WORDS - 1 downTo 0) {
            if (words[index] != 0L) {
                requireRepresentable(key, (index shl 6) + (63 - words[index].countLeadingZeroBits()), source)
                break
            }
        }
        return BitsetContainer.ofWords(words, cardinality)
    }

    private fun readRun(
        bytes: IndexBytes,
        at: Int,
        key: Int,
        cardinality: Int,
        source: String,
    ): Pair<Container, Int> {
        val runCount = bytes.u16(at, "run count")
        if (runCount < 1) bytes.corrupt("block $key holds no runs", at)
        val runs = CharArray(runCount * 2)
        var counted = 0
        var previousLast = -1
        for (index in 0 until runCount) {
            val entry = at + 2 + index * 4
            val start = bytes.u16(entry, "run start")
            val lengthMinusOne = bytes.u16(entry + 2, "run length")
            if (index > 0 && start <= previousLast + 1) {
                bytes.corrupt(
                    "block $key has a run at $start following one ending at $previousLast, " +
                        "which are not separated",
                    entry,
                )
            }
            val last = start + lengthMinusOne
            if (last > 0xFFFF) {
                bytes.corrupt("block $key has a run $start..$last, which leaves the block", entry)
            }
            runs[index * 2] = start.toChar()
            runs[index * 2 + 1] = lengthMinusOne.toChar()
            previousLast = last
            counted += lengthMinusOne + 1
        }
        if (counted != cardinality) {
            bytes.corrupt("block $key holds $counted value(s) but its header claims $cardinality", at)
        }
        requireRepresentable(key, previousLast, source)
        return RunContainer.ofRuns(runs, runCount, cardinality) to RoaringPortableFormat.runBytes(runCount)
    }

    /**
     * Refuses a container key this engine's ordinal domain cannot reach.
     *
     * The portable format keys containers by an unsigned 16-bit value over an unsigned 32-bit domain;
     * an ordinal here is signed and stops one short of `Int.MAX_VALUE`. So every key above 32767 is out
     * of reach entirely, and inside key 32767 the single value 65535 is too — it is the one ordinal
     * [BitmapFormat.MAX_ORDINAL] explains giving up, and it turns up here as the first thing a foreign
     * bitmap could hold that this one cannot.
     */
    private fun requireRepresentable(key: Int, source: String) {
        val highest = BitmapFormat.high(BitmapFormat.MAX_ORDINAL)
        if (key > highest) {
            throw UnsupportedBitmapFormatException(
                "$source holds a container keyed $key, whose values are above " +
                    "${BitmapFormat.MAX_ORDINAL}, the largest ordinal this build represents",
            )
        }
    }

    private fun requireRepresentable(key: Int, low: Int, source: String) {
        // The key has already been bounded, so this arithmetic cannot overflow into a negative `Int`:
        // the largest value it can produce is exactly `Int.MAX_VALUE`, which is the one being refused.
        val value = BitmapFormat.valueOf(key, low)
        if (value > BitmapFormat.MAX_ORDINAL) {
            throw UnsupportedBitmapFormatException(
                "$source holds the value $value, above ${BitmapFormat.MAX_ORDINAL}, " +
                    "the largest ordinal this build represents",
            )
        }
    }
}
