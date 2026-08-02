package app.oreshkov.rabosh.index

import java.util.Arrays

/**
 * The term half of a posting file's directory: what the *i*-th term is, and where a term is.
 *
 * Two implementations, because a version-1 `.pst` stores its terms whole behind per-entry offsets and
 * a version-2 one front-codes them behind restart points. There is exactly one place that knows which
 * — the version check in [PostingFile.open] — and nothing below it branches. That is the same rule
 * `IndexFormat.POSTING_ENCODING_SINGLE` states about posting lists, applied a level up: a reader
 * carrying two notions of what a *term* is would be a second definition of one, and the two would only
 * have to disagree once.
 *
 * So the contract is written here rather than in either subclass, and it is exact in the places that
 * are easy to get subtly different:
 *
 * - [termAt] answers in ascending unsigned byte order, `0 until termCount`.
 * - [search] returns the index of [term], or `-(insertionPoint + 1)` if it is absent — the sign
 *   convention `java.util.Arrays.binarySearch` uses, which callers rely on to distinguish "not here"
 *   from "position zero".
 * - [entryBytes] is the width of one *posting* entry in the directory, which differs between versions
 *   only because version 1 spends eight of them on a term offset and length.
 *
 * `TermDictionaryTest` asserts the two agree on all three over the same terms, which is a claim a
 * comment cannot make.
 */
internal sealed class TermDictionary(
    protected val bytes: IndexBytes,
    protected val directoryOffset: Int,
    val termCount: Int,
) {
    /** Bytes per directory entry, so [PostingFile] can find entry *i* without knowing the version. */
    abstract val entryBytes: Int

    /**
     * Where an entry's `postingOffset postingLength encoding crc32c` quartet begins within it.
     *
     * Eight in version 1, which spends its first two fields on the term, and zero in version 2, which
     * does not. Exposing it here is what keeps `PostingFile.postingAt` — the read every query makes —
     * a single code path over both layouts. The alternative, a `when` on the version at that call
     * site, would put the version check on the hot path *and* create the second definition of a
     * posting list this class exists to prevent.
     */
    abstract val postingFieldOffset: Int

    /** The [index]-th term in ascending unsigned order. */
    abstract fun termAt(index: Int): ByteArray

    /** The index of [term], or `-(insertionPoint + 1)`. */
    abstract fun search(term: ByteArray): Int

    /** Where entry [index] begins. Arithmetic in both versions, which is the point of a fixed width. */
    fun entryAt(index: Int): Int = directoryOffset + index * entryBytes

    /** Where entry [index]'s posting fields begin. */
    fun postingFieldsAt(index: Int): Int = entryAt(index) + postingFieldOffset

    /**
     * The plain bisect, over whatever [termAt] answers.
     *
     * Shared so that the two versions cannot drift on the *convention* even where they differ on the
     * strategy. [FrontCodedTermDictionary] overrides it with a restart bisect for the reason that
     * layout exists; this remains the definition of what the answer means.
     */
    protected fun bisect(term: ByteArray): Int {
        var low = 0
        var high = termCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = Arrays.compareUnsigned(termAt(middle), term)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }
}

/**
 * A version-1 dictionary: whole terms, reached through a `(termOffset, termLength)` pair per entry.
 *
 * Read, never written. It exists because `SegmentIndex.open` throws when a `.pst` that is present will
 * not decode — a sidecar may be *missing* but never unintelligible — so a build that refused version 1
 * would fail `IndexCatalog.attach` on every store written before phase 17 rather than rebuilding it.
 * The committed golden stores are what keep this path honest on bytes nobody regenerated.
 */
internal class FlatTermDictionary(
    bytes: IndexBytes,
    directoryOffset: Int,
    termCount: Int,
) : TermDictionary(bytes, directoryOffset, termCount) {

    override val entryBytes: Int get() = IndexFormat.POSTING_V1_TERM_ENTRY_BYTES

    override val postingFieldOffset: Int get() = 8

    override fun termAt(index: Int): ByteArray {
        val at = entryAt(index)
        val offset = bytes.u32(at, "term offset", bytes.length)
        val length = bytes.u32(at + 4, "term length", IndexFormat.MAX_TERM_BYTES)
        return bytes.bytes(offset, length, "term $index")
    }

    override fun search(term: ByteArray): Int = bisect(term)
}

/**
 * A version-2 dictionary: front-coded terms behind restart points every
 * [IndexFormat.POSTING_TERM_RESTART_INTERVAL].
 *
 * The same scheme `KeyBlockReader` uses for a segment's key block, and it is deliberately *not* shared
 * code with it: that block is entered by ordinal and bounded by `bytes.length`, this one is entered by
 * value and bounded by a region inside a larger file, and the two are pinned by different version
 * fields. What is shared is the idea, and the arithmetic is small enough that a common abstraction
 * would cost more in indirection than it saves in lines.
 *
 * [termAt] walks from the restart covering [index], so it decodes at most sixteen entries and starts
 * from a complete term rather than from whatever was decoded last. [search] bisects the restarts —
 * each of which carries a complete term, which is the whole reason a restart shares nothing — and then
 * walks the one group that can hold the answer.
 */
internal class FrontCodedTermDictionary(
    bytes: IndexBytes,
    directoryOffset: Int,
    termCount: Int,
    /** Where the front-coded region begins, and what every restart offset is relative to. */
    private val termsOffset: Int,
    /** Where the restart array begins. */
    private val restartsOffset: Int,
    /** Bytes in the term region, so a walk cannot run into the presence bitmap. */
    private val regionLength: Int,
) : TermDictionary(bytes, directoryOffset, termCount) {

    private val restartCount = IndexFormat.postingRestartCount(termCount)

    override val entryBytes: Int get() = IndexFormat.POSTING_V2_TERM_ENTRY_BYTES

    override val postingFieldOffset: Int get() = 0

    override fun termAt(index: Int): ByteArray {
        if (index < 0 || index >= termCount) {
            bytes.corrupt("term $index is outside the $termCount this dictionary holds")
        }
        val group = index / IndexFormat.POSTING_TERM_RESTART_INTERVAL
        var at = termsOffset + restartOffset(group)
        var term = ByteArray(0)
        // The first step lands on the restart record, whose shared length is zero, so the walk starts
        // from a complete term. Every subsequent step extends it, which is what front-coding is.
        repeat(index % IndexFormat.POSTING_TERM_RESTART_INTERVAL + 1) {
            val sharedField = bytes.varint(at, "term shared length", term.size)
            at += varintWidth(sharedField)
            val shared = varintValue(sharedField)
            val unsharedField = bytes.varint(at, "term unshared length", IndexFormat.MAX_TERM_BYTES - shared)
            at += varintWidth(unsharedField)
            val unshared = varintValue(unsharedField)
            if (at + unshared > termsOffset + regionLength) {
                bytes.corrupt("term $index runs past the ${regionLength}-byte term region", at)
            }
            val next = ByteArray(shared + unshared)
            term.copyInto(next, 0, 0, shared)
            bytes.bytes(at, unshared, "term $index").copyInto(next, shared)
            term = next
            at += unshared
        }
        return term
    }

    override fun search(term: ByteArray): Int {
        if (termCount == 0) return -1
        // Bisect the restarts on their complete terms. `low` ends as the first group whose term is
        // above the target, so `low - 1` is the only group that can hold it.
        var low = 0
        var high = restartCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Arrays.compareUnsigned(termAt(middle * IndexFormat.POSTING_TERM_RESTART_INTERVAL), term) <= 0) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        if (low == 0) return -1
        val first = (low - 1) * IndexFormat.POSTING_TERM_RESTART_INTERVAL
        val last = minOf(first + IndexFormat.POSTING_TERM_RESTART_INTERVAL, termCount) - 1
        for (index in first..last) {
            val comparison = Arrays.compareUnsigned(termAt(index), term)
            if (comparison == 0) return index
            if (comparison > 0) return -(index + 1)
        }
        return -(last + 2)
    }

    /**
     * Checks the restart array without walking a single term.
     *
     * This is what makes opening a version-2 file cheaper than opening a version-1 one rather than
     * merely no dearer. Version 1 has to visit every entry to derive where the terms end; here the
     * header checksum already covers the directory, the restarts and the whole term region, so once it
     * verifies, the region's extent is a fact and only the restarts need to be structurally sound.
     * Per-term validation is deferred to [termAt], which is the same division of labour the two-level
     * checksum makes: pay on open for what decides *where* a byte is, pay on read for the rest.
     */
    fun verifyRestarts() {
        var previous = -1
        for (group in 0 until restartCount) {
            val offset = restartOffset(group)
            if (offset <= previous) {
                bytes.corrupt("restart $group at $offset does not follow restart ${group - 1}", restartsOffset + group * 4)
            }
            previous = offset
        }
        if (restartCount > 0 && restartOffset(0) != 0) {
            bytes.corrupt("the first restart must begin the term region", restartsOffset)
        }
    }

    private fun restartOffset(group: Int): Int =
        bytes.u32(restartsOffset + group * 4, "term restart offset", regionLength)
}
