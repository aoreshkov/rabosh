package app.oreshkov.rabosh.core

import java.util.Arrays

/**
 * A document key: an opaque byte string ordered as **unsigned** bytes.
 *
 * Bytes rather than `String` because the ordering is the load-bearing part. An LSM-tree merges
 * sorted runs, so the comparator is not a detail of the API — it is the one thing every segment
 * ever written has to agree with, forever. Unsigned lexicographic order over bytes is the only
 * choice that is stable under UTF-8: it makes key order agree with code-point order for text keys,
 * while still accepting keys that are not text at all.
 *
 * `String.compareTo` would have been the wrong comparator even for string keys — it compares UTF-16
 * units, so `U+FF21` sorts before `U+10000` in UTF-8 and after it in UTF-16. The Variant codec has
 * the same rule for field names, for the same reason.
 *
 * Keys are immutable; the bytes handed to [of] are copied, and [toByteArray] copies back out.
 * An empty key is legal and sorts first.
 */
public class Key private constructor(private val bytes: ByteArray) : Comparable<Key> {

    /** Length of the key in bytes. */
    public val size: Int get() = bytes.size

    /** The byte at [index]. */
    public operator fun get(index: Int): Byte = bytes[index]

    /** A copy of the key's bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * The backing array, not copied.
     *
     * Internal because a caller that mutated it would corrupt the ordering of a live memtable.
     */
    internal val raw: ByteArray get() = bytes

    /**
     * The next key in this ordering: the smallest key strictly greater than this one.
     *
     * A zero byte appended, and it is exact rather than approximate. Under unsigned lexicographic
     * comparison a shorter key that is a prefix of a longer one sorts first, so nothing can lie
     * between `k` and `k + 0x00`. It is also **total** — keys have no maximum length, so there is no
     * "last key" for this to fail on, which is what lets a range walk use it without a special case
     * at the end.
     *
     * **This is how an exclusive lower bound is spelled.** Every range in this API is inclusive at
     * both ends, which is the right default for "delete July" and the wrong one for "carry on from
     * where I stopped" — a resumable walk that restarted at the key it last handled would hand that
     * key over twice. It is the one thing a drain loop needs that the inclusive bounds cannot say:
     *
     * ```kotlin
     * var watermark: Key? = null
     * while (true) {
     *     val batch = db.scan(from = watermark, snapshot = view).use { … }
     *     if (batch.isEmpty()) break
     *     ship(batch)
     *     watermark = batch.last().key.successor()   // resume *after* it, never at it
     * }
     * ```
     *
     * Cheap, and not free: the key is one byte longer than its predecessor, so a watermark carried
     * through many rounds should be recomputed from the last key handled rather than by calling this
     * on its own result.
     */
    public fun successor(): Key = Key(bytes + 0)

    /**
     * Unsigned lexicographic comparison; the shorter key wins when one is a prefix of the other.
     *
     * [Arrays.compareUnsigned] is used rather than a hand-written loop because the JDK intrinsifies
     * it, and key comparison is the innermost operation of every lookup and every merge.
     */
    override fun compareTo(other: Key): Int = Arrays.compareUnsigned(bytes, other.bytes)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Key && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /**
     * Printable ASCII is shown as text, anything else as hex.
     *
     * This exists for failure reports: a shrunk counterexample from the property harness is only
     * useful if the key it names is legible.
     */
    override fun toString(): String {
        val printable = bytes.all { it >= 0x20 && it < 0x7F }
        return if (printable) {
            "Key(${bytes.decodeToString()})"
        } else {
            bytes.joinToString(separator = "", prefix = "Key(0x", postfix = ")") {
                "%02x".format(it)
            }
        }
    }

    public companion object {
        /** Copies [bytes] into a new key. */
        public fun of(bytes: ByteArray): Key = Key(bytes.copyOf())

        /**
         * Encodes [text] as UTF-8.
         *
         * @throws IllegalArgumentException if [text] holds an unpaired surrogate. Such a string has
         *   no UTF-8 encoding, and substituting a replacement character — which
         *   `String.toByteArray` does silently — would store a key the caller never asked for and
         *   then fail to find it again.
         */
        public fun of(text: String): Key = Key(
            try {
                text.encodeToByteArray(throwOnInvalidSequence = true)
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("key text contains an unpaired surrogate", failure)
            },
        )

        /** Takes ownership of [bytes] without copying. Only for arrays this module just decoded. */
        internal fun wrap(bytes: ByteArray): Key = Key(bytes)
    }
}
