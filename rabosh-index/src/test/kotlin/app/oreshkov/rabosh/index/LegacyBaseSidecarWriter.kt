package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.Key
import java.util.Arrays

/**
 * A version-1 base sidecar, written by the tests so the version-1 *reader* has something to face.
 *
 * **This lives in the test source set on purpose, and it must stay there** — the same rule
 * [LegacyPostingBuilder] states and for the same reasons. The engine has exactly one key-block writer;
 * a second one in `main` would be dead code the next reader of [KeyBlockWriter] has to reason about,
 * and a standing invitation to keep writing the old format "just for a while". What the version-1
 * reader needs is bytes, not a production path.
 *
 * The two instruments over version 1 are not interchangeable and neither replaces the other:
 *
 * - `FormatCompatibilityTest` reads the **committed golden stores**, which is the only evidence that
 *   bytes written by a build that no longer exists still mean what they meant. It cannot damage them,
 *   and there are only a handful of files.
 * - This writes version-1 bytes **on demand**, which is what lets `IndexCorruptionTest` truncate at
 *   every offset and flip every bit of a version-1 sidecar, and lets `KeyBlockTest` compare the two
 *   key blocks over generated keys. It proves nothing about history, because it is this build's idea
 *   of version 1.
 *
 * So this is a transcription of what phase 7 wrote, kept beside [KeyBlockWriter] rather than derived
 * from it. If the two ever have to agree about something, they are agreeing by transcription — and
 * the golden stores are what catch a transcription that drifted.
 */
internal class LegacyKeyBlockWriter(initialCapacity: Int = 4096) {
    private val out = IndexWriter(initialCapacity)
    private val restarts = ArrayList<Int>()
    private var previous = ByteArray(0)
    private var count = 0

    val size: Int get() = count

    fun add(key: ByteArray) {
        val restart = count % IndexFormat.KEY_RESTART_INTERVAL == 0
        if (restart) {
            restarts += out.size
        } else {
            require(Arrays.compareUnsigned(previous, key) < 0) {
                "keys must ascend: ordinal $count is not above its predecessor"
            }
        }
        val shared = if (restart) 0 else sharedPrefix(previous, key)
        // The whole of version 1: two `u32`s where version 2 writes two varints.
        out.writeU32(shared)
        out.writeU32(key.size - shared)
        out.write(if (shared == 0) key else key.copyOfRange(shared, key.size))
        previous = key
        count++
    }

    fun build(): ByteArray {
        for (offset in restarts) out.writeU32(offset)
        out.writeU32(restarts.size)
        return out.toByteArray()
    }

    private fun sharedPrefix(left: ByteArray, right: ByteArray): Int {
        val limit = minOf(left.size, right.size)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }
}

/** The bytes phase 7's `BaseSidecarBuilder.build` would have produced for these observations. */
internal class LegacyBaseSidecarBuilder {
    private val keys = LegacyKeyBlockWriter()
    private val present = Bitmap()
    private var tombstones = 0
    private var minSequence = Long.MAX_VALUE
    private var maxSequence = Long.MIN_VALUE

    var count: Int = 0
        private set

    fun observe(userKey: Key, sequence: Long, isPut: Boolean) {
        keys.add(userKey.toByteArray())
        if (isPut) present.add(count) else tombstones++
        minSequence = minOf(minSequence, sequence)
        maxSequence = maxOf(maxSequence, sequence)
        count++
    }

    fun build(segmentNumber: Long): ByteArray {
        val meta = IndexWriter(IndexFormat.META_BYTES)
        meta.writeLong(segmentNumber)
        meta.writeU32(count)
        meta.writeU32(tombstones)
        meta.writeLong(if (count == 0) 0 else minSequence)
        meta.writeLong(if (count == 0) 0 else maxSequence)

        return SectionDirectory.encode(
            IndexFormat.BASE_MAGIC,
            IndexFormat.BASE_VERSION_FLAT,
            listOf(
                IndexFormat.SECTION_KIND_META to meta.toByteArray(),
                IndexFormat.SECTION_KIND_KEYS to keys.build(),
                IndexFormat.SECTION_KIND_PRESENT to present.encode(),
            ),
        )
    }
}
