package app.oreshkov.rabosh.index

/**
 * A walk over a bitmap's ordinals, ascending.
 *
 * ```kotlin
 * val cursor = bitmap.cursor()
 * while (cursor.next()) { /* cursor.value */ }
 * ```
 *
 * Owns nothing and closes nothing — but a cursor over a [BitmapView] reads through the view's mapping,
 * so it lives under the same rule the view does: it must not outlive the arena that mapped the file.
 *
 * [advanceTo] is what makes an index intersection cheaper than a merge. Walking two bitmaps from the
 * start costs the sum of their cardinalities; walking the sparser one and jumping the denser costs the
 * sparser, which is the difference between reading a sidecar and reading past it.
 */
public class BitmapCursor internal constructor(private val source: ContainerSource) {

    private var blockIndex = -1
    private var block: ContainerCursor? = null
    private var current = -1

    /**
     * The ordinal the cursor sits on.
     *
     * Valid only after [next] or [advanceTo] returned `true`. Before the first call it is `-1`, which is
     * not an ordinal — so a caller who reads it early gets a value that cannot be mistaken for one.
     */
    public val value: Int get() = current

    /** Advances to the next ordinal. `false` once the bitmap is exhausted. */
    public fun next(): Boolean {
        if (blockIndex >= source.containerCount) return false
        if (blockIndex < 0) {
            blockIndex = 0
            block = null
        }
        while (blockIndex < source.containerCount) {
            val cursor = block ?: source.containerAt(blockIndex).cursor().also { block = it }
            if (cursor.next()) {
                current = BitmapFormat.valueOf(source.keyAt(blockIndex), cursor.low)
                return true
            }
            blockIndex++
            block = null
        }
        return false
    }

    /**
     * Advances to the first ordinal at or above [ordinal]. `false` once the bitmap is exhausted.
     *
     * Never moves backwards: a cursor already sitting on an ordinal at or above [ordinal] stays where it
     * is and answers `true`, which is what lets a leapfrog join ask the same cursor about a value it may
     * already have passed. Whole blocks below the target are skipped without being opened.
     */
    public fun advanceTo(ordinal: Int): Boolean {
        require(ordinal >= 0) { "a cursor advances to an ordinal, not $ordinal" }
        if (current >= ordinal && blockIndex in 0 until source.containerCount) return true
        val targetKey = BitmapFormat.high(ordinal)
        var at = maxOf(blockIndex, 0)
        while (at < source.containerCount && source.keyAt(at) < targetKey) at++
        while (at < source.containerCount) {
            val key = source.keyAt(at)
            if (at != blockIndex || block == null) {
                blockIndex = at
                block = source.containerAt(at).cursor()
            }
            // Only the block holding the target key starts part-way in; a later block is entered from
            // its beginning, because every ordinal in it is already above the target.
            val within = if (key == targetKey) BitmapFormat.low(ordinal) else 0
            val cursor = checkNotNull(block)
            if (cursor.advanceTo(within)) {
                current = BitmapFormat.valueOf(key, cursor.low)
                return true
            }
            at++
            block = null
        }
        blockIndex = source.containerCount
        return false
    }
}
