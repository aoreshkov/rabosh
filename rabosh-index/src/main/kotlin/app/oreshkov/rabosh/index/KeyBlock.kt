package app.oreshkov.rabosh.index

import java.util.Arrays

/**
 * Writes a segment's user keys in ordinal order, prefix-compressed.
 *
 * This block is what makes a per-segment ordinal mean anything outside the segment it came from. A
 * posting list is a set of ordinals, and nothing in `rabosh-core` exposes "the *n*-th key of segment
 * N" — the segment reader is internal, by design, because the shape of the tree is not an observer's
 * business. So the sidecar carries the mapping itself. Keys arrive in ascending order, one per
 * distinct key, which is exactly the input prefix compression is for.
 *
 * The cost is honest and worth writing down: this is a second copy of the segment's key space. For
 * keys with a common prefix — the ordinary case, `user:`, `order:2026-07-` — the compression is most
 * of it back. For keys with no shared prefix it is not, and the sidecar is then roughly the size of
 * the segment's keys.
 *
 * Phase 18 measured it and narrowed the entry rather than the keys. A block used to spend eight bytes
 * per key on a `u32` pair holding two lengths that are almost always below 128, which over the
 * benchmark's 200 000 thirteen-byte keys was **six of every ten bytes of a base sidecar**. Two
 * varints replace them, and that is the whole of [IndexFormat.BASE_VERSION] 2 — the terms were already
 * front-coded and the restart array is untouched, because a restart offset is *indexed into* and a
 * variable width belongs only where a walk was happening anyway.
 */
internal class KeyBlockWriter(initialCapacity: Int = 4096) {
    private val out = IndexWriter(initialCapacity)
    private val restarts = ArrayList<Int>()
    private var previous = ByteArray(0)
    private var count = 0

    /** Keys written so far. The next one is ordinal [size]. */
    val size: Int get() = count

    /**
     * Appends the next ordinal's key.
     *
     * @throws IllegalArgumentException if [key] does not follow the previous one. Ordinals come from
     *   a single ascending pass over a segment, so an out-of-order key is a bug in the caller rather
     *   than damage in a file, and it must fail where it happens rather than produce a block whose
     *   binary search silently misses.
     */
    fun add(key: ByteArray) {
        val restart = count % IndexFormat.KEY_RESTART_INTERVAL == 0
        if (restart) {
            restarts += out.size
        } else {
            require(Arrays.compareUnsigned(previous, key) < 0) {
                "keys must ascend: ordinal $count is not above its predecessor"
            }
        }

        // A restart entry shares nothing, so the key it holds is complete and can be compared without
        // reconstructing anything — which is what makes the binary search in the reader possible.
        val shared = if (restart) 0 else sharedPrefix(previous, key)
        // Shortest-form varints, so a set of keys has exactly one encoding. That is not a saving, it
        // is what lets `IndexByteIdentityTest` compare a flush-written sidecar with a backfill-rebuilt
        // one as *files*; `IndexBytes.varint` refuses a padded spelling from the other side.
        out.writeVarint(shared)
        out.writeVarint(key.size - shared)
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

/**
 * Reads a key block in place.
 *
 * Two lookups, and they are asymmetric on purpose.
 *
 * [keyAt] is **arithmetic**: restart *i* covers ordinals `[16i, 16i + 16)`, so resolving an ordinal is
 * one read of the restart array plus at most fifteen entry steps, with no search anywhere. That is
 * the direction the read path actually goes — a posting list yields ordinals and the caller needs
 * keys — and it is why the restart interval is a permanent constant rather than a tuning knob. The
 * segment's own `Block` bisects its restarts instead, because its entries are not positionally
 * uniform and it has no ordinal to arrive with.
 *
 * [ordinalOf] is the bisect, and nothing on the read path needs it: it exists so a sidecar can be
 * checked against the segment it claims to describe, and so that a future planner that arrives with a
 * key rather than an ordinal has a way in.
 *
 * **Two versions, one walk.** Phase 18 narrowed the entry header from a `u32` pair to two varints, so
 * a version-1 and a version-2 block differ in *nothing else*: both front-code, both restart every
 * [IndexFormat.KEY_RESTART_INTERVAL], both are walked from a restart. Everything above therefore lives
 * here once and the subclasses supply only [lengthAt] — phase 6's rule that a container's read
 * algorithm lives in one place, applied where the temptation to copy a twenty-line walk is strongest.
 * Two implementations of "the *n*-th key" that drifted would resolve one posting list to different
 * documents depending on when the sidecar was written, which is the quietest bug this module could
 * have. `BaseSidecar.open` is the one place that knows the version; nothing below it branches.
 */
internal sealed class KeyBlockReader(
    protected val bytes: IndexBytes,
    /**
     * How many keys the block holds.
     *
     * Taken from the sidecar's `META` section rather than stored here as well, so the count lives in
     * exactly one place and cannot disagree with itself — the same argument the bitmap's
     * `cardinalityBefore` prefix sum makes.
     */
    val count: Int,
) {
    private val restartCount: Int
    private val restartsAt: Int
    private val entriesEnd: Int

    init {
        require(count >= 0) { "count must not be negative, was $count" }
        if (bytes.length < 4) bytes.corrupt("a key block needs at least a restart count")
        restartCount = bytes.u32(bytes.length - 4, "key block restart count", (bytes.length - 4) / 4)
        restartsAt = bytes.length - 4 - restartCount * 4
        entriesEnd = restartsAt
        if (restartsAt < 0) bytes.corrupt("a key block's restart array does not fit the section")

        // The restart count and the key count determine each other, so a disagreement between them is
        // a sidecar describing a segment other than the one it is filed under.
        val expected = if (count == 0) 0 else (count - 1) / IndexFormat.KEY_RESTART_INTERVAL + 1
        if (restartCount != expected) {
            bytes.corrupt(
                "a key block for $count key(s) needs $expected restart point(s) but declares $restartCount",
                bytes.length - 4,
            )
        }
    }

    /**
     * The block's own first and last key, decoded once and **not until something searches**.
     *
     * Declared after `init` because it reads what `init` establishes, and lazily for the reason phase
     * 18 kept the restart check out of `init` altogether: a sidecar must *open* even when it is
     * damaged, so that `BaseSidecar.verify` is what reports the damage and `IndexCorruptionTest` can
     * assert the split by opening a damaged file successfully first. Decoding two keys is `O(1)` and
     * would not have cost an open anything measurable — but it would have moved where a corrupt block
     * is first noticed, which is a behaviour change and not a speed one.
     *
     * `PUBLICATION` rather than the default lock: two threads racing here compute the same two keys
     * from the same immutable mapping, so the wasted work is bounded at one duplicate decode and the
     * alternative is a monitor on a read path.
     */
    private val range: KeyRange? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (count == 0) null else KeyRange(keyAt(0), keyAt(count - 1))
    }

    /**
     * One length field of an entry header, at [at] and bounded by [limit].
     *
     * The single point of difference between the two layouts. It answers in the packing
     * `IndexBytes.varint` already defines — `(value shl 3) or width` — rather than a new one, so that
     * `varintValue` and `varintWidth` unpack it and there is no second convention to get wrong. The
     * width is what a caller needs and a fixed-width field does not advertise: the next read starts
     * where this one ended, in both versions, which is what makes one walk serve both.
     *
     * [limit] is applied at the read rather than at the use — `IndexBytes.u32`'s rule, and the one
     * place a corrupt length would otherwise become a wild read.
     */
    protected abstract fun lengthAt(at: Int, what: String, limit: Int): Long

    /** The key at [ordinal]. */
    fun keyAt(ordinal: Int): ByteArray {
        if (ordinal < 0 || ordinal >= count) {
            bytes.corrupt("ordinal $ordinal is outside the $count key(s) this block holds")
        }
        val walk = Walk()
        walk.restart(ordinal / IndexFormat.KEY_RESTART_INTERVAL)
        // The first step lands on the restart entry, whose shared length is zero, so the walk starts
        // from a complete key rather than from whatever was decoded last.
        repeat(ordinal % IndexFormat.KEY_RESTART_INTERVAL + 1) { step -> walk.step(walk.first + step) }
        return walk.key()
    }

    /**
     * The ordinal of [key], or `-(insertionPoint + 1)` if the block does not hold it.
     *
     * The sign convention `java.util.Arrays.binarySearch` uses, because a caller that wants to know
     * where a key *would* go is the only reason to ask about one that is absent.
     *
     * **The group is walked once, not once per ordinal.** Bisecting the restarts is cheap — a restart
     * entry shares nothing, so probing one is a single step — but the final scan looks at up to
     * [IndexFormat.KEY_RESTART_INTERVAL] keys, and reaching each of them through [keyAt] would restart
     * the walk from the restart point every time: `O(interval²)` entry steps for one lookup, where the
     * layout offers `O(interval)`. This is on the per-row path of every indexed query through
     * `SegmentSelection.isUniqueKey`, so the difference is not academic.
     */
    fun ordinalOf(key: ByteArray): Int {
        if (count == 0) return -1
        // A key outside the block's own range needs no search, and this is the shape the question
        // actually arrives in: `SegmentSelection.isUniqueKey` asks *every other* segment whether it
        // holds a candidate, so all but one answer no. Below the first key the bisect would already
        // conclude that in `low == 0`, but only after log2(restarts) probes; above the last it walks a
        // whole group. Both become one comparison.
        val range = range ?: return -1
        if (Arrays.compareUnsigned(key, range.first) < 0) return -1
        if (Arrays.compareUnsigned(key, range.last) > 0) return -(count + 1)

        val walk = Walk()
        // Bisect the restart points on their complete keys, then walk the group. `low` ends as the
        // first group whose key is above the target, so `low - 1` is the group that may contain it.
        var low = 0
        var high = restartCount
        while (low < high) {
            val middle = (low + high) ushr 1
            walk.restart(middle)
            walk.step(walk.first)
            if (walk.compareTo(key) <= 0) low = middle + 1 else high = middle
        }
        if (low == 0) return -1
        walk.restart(low - 1)
        val first = walk.first
        val last = minOf(first + IndexFormat.KEY_RESTART_INTERVAL, count) - 1
        for (ordinal in first..last) {
            walk.step(ordinal)
            val comparison = walk.compareTo(key)
            if (comparison == 0) return ordinal
            if (comparison > 0) return -(ordinal + 1)
        }
        return -(last + 2)
    }

    /**
     * Checks that the restart array ascends and begins the entry region.
     *
     * **Deliberately not in `init`, and that is the difference from `FrontCodedTermDictionary`**,
     * which does the equivalent when a posting file is opened. A posting file's restart count is a
     * sixteenth of its term count and the file is opened once; a key block's is a sixteenth of a
     * *segment's* document count, and [keyAt] resolves one ordinal in constant time. Walking
     * `documentCount / 16` offsets before the first lookup would put an `O(n)` cost on the read path
     * of a ten-million-key sidecar to catch damage that makes a key wrong rather than a read wild —
     * every offset here is already bounds-checked. So this is called from `BaseSidecar.verify`, where
     * the `O(documentCount)` diagnostic already lives, which is the same division of labour the
     * two-level checksum makes.
     */
    fun verifyRestarts() {
        var previous = -1
        for (group in 0 until restartCount) {
            val offset = restartOffset(group)
            if (offset <= previous) {
                bytes.corrupt(
                    "restart $group at $offset does not follow restart ${group - 1}",
                    restartsAt + group * 4,
                )
            }
            previous = offset
        }
        if (restartCount > 0 && restartOffset(0) != 0) {
            bytes.corrupt("the first restart must begin the key block", restartsAt)
        }
    }

    private fun restartOffset(group: Int): Int =
        bytes.u32(restartsAt + group * 4, "key block restart offset", entriesEnd)

    /** A block's own key extent, which is what makes a search for something outside it free. */
    private class KeyRange(val first: ByteArray, val last: ByteArray)

    /**
     * A sequential walk from a restart point, reconstructing each key over the last one.
     *
     * The whole of the read algorithm both public methods share, and it is one object rather than two
     * loops for the reason the abstract container classes are one: two implementations of "the *n*-th
     * key" that drifted would resolve a posting list to different documents depending on which of them
     * a caller happened to go through. [keyAt] and [ordinalOf] differ only in what they do with each
     * key as it appears.
     *
     * **One buffer, grown and never shrunk, and [length] rather than its size is the key.** Front
     * coding means every key is the previous one with its tail replaced, so writing the unshared bytes
     * over the buffer at `shared` *is* the reconstruction — no intermediate array, no second copy, and
     * the only allocation on the path is [key]'s. The trap that costs is reading past [length]: a key
     * shorter than its predecessor leaves the predecessor's tail in the buffer, so every read here is
     * bounded by [length] and `KeyBlockTest` arranges that shape deliberately rather than hoping a
     * generator produces it.
     *
     * The buffer is owned per call — a `Walk` is created inside [keyAt] and [ordinalOf] — because a
     * scratch buffer on the reader would make both non-reentrant, and `IndexReader` is public API a
     * caller may share between threads.
     */
    private inner class Walk {
        private var buffer = ByteArray(64)
        private var at = 0

        /** The ordinal the next [step] after a [restart] decodes. */
        var first: Int = 0
            private set

        /** Length of the key the last [step] produced. Bytes beyond it are a previous key's tail. */
        var length: Int = 0
            private set

        /** Positions the walk at the start of [group], discarding whatever it had decoded. */
        fun restart(group: Int) {
            at = restartOffset(group)
            first = group * IndexFormat.KEY_RESTART_INTERVAL
            length = 0
        }

        /** Decodes the next entry, which must be [ordinal]'s. Named only so a failure can say where. */
        fun step(ordinal: Int) {
            val sharedField = lengthAt(at, "key entry shared length", length)
            at += varintWidth(sharedField)
            val shared = varintValue(sharedField)
            val unsharedField = lengthAt(at, "key entry unshared length", entriesEnd - at)
            at += varintWidth(unsharedField)
            val unshared = varintValue(unsharedField)
            // Bounded against the entry region rather than against the section, because the restart
            // array sits between them: a key that ran into it would decode without a wild read and be
            // silently wrong, which is the failure a bound at the read exists to prevent.
            if (at + unshared > entriesEnd) {
                bytes.corrupt("the key at ordinal $ordinal runs past the entry region", at)
            }
            if (shared + unshared > buffer.size) buffer = buffer.copyOf(maxOf(shared + unshared, buffer.size * 2))
            bytes.copyInto(at, buffer, shared, unshared, "key entry")
            length = shared + unshared
            at += unshared
        }

        /** The key the last [step] produced, copied out. */
        fun key(): ByteArray = buffer.copyOf(length)

        /** How the key the last [step] produced orders against [target], unsigned. */
        fun compareTo(target: ByteArray): Int =
            Arrays.compareUnsigned(buffer, 0, length, target, 0, target.size)
    }
}

/**
 * A version-1 key block: `sharedLength:u32 unsharedLength:u32` per entry.
 *
 * Read, never written, for the reason [FlatTermDictionary] is: `SegmentIndex.open` throws when an
 * `.idx` that is present will not decode, so a build that refused version 1 would fail
 * `IndexCatalog.attach` on every store written before phase 18 rather than rebuilding it. The
 * committed golden stores are what keep this path honest on bytes nobody regenerated.
 */
internal class FixedWidthKeyBlockReader(bytes: IndexBytes, count: Int) : KeyBlockReader(bytes, count) {

    override fun lengthAt(at: Int, what: String, limit: Int): Long =
        (bytes.u32(at, what, limit).toLong() shl 3) or 4L
}

/**
 * A version-2 key block: `shared:varint unshared:varint` per entry.
 *
 * Two bytes where version 1 spends eight, at every key length below 128, and four below 16 KiB — so
 * this is never the larger of the two encodings for any key the engine can hold. Unlike the posting
 * file's front-coded term region there is no crossover to bound and pin, because nothing here trades
 * one field against another: the same front-coding, a narrower header.
 */
internal class VarintKeyBlockReader(bytes: IndexBytes, count: Int) : KeyBlockReader(bytes, count) {

    override fun lengthAt(at: Int, what: String, limit: Int): Long = bytes.varint(at, what, limit)
}
