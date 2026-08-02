package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentCursor
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.BitmapCursor
import app.oreshkov.rabosh.variant.Variant

/**
 * A stream of candidate keys in ascending order.
 *
 * Both kinds are ascending for the same underlying reason and it is worth stating, because the whole
 * execution shape depends on it: **ordinals are assigned in ascending key order** — one per distinct
 * user key as a segment is written — so walking a candidate bitmap upwards walks keys upwards, and a
 * `DocumentCursor` is ordered by key already. So the executor merges rather than sorts, holds no set
 * of every candidate, emits in key order, and a `LIMIT` genuinely stops work rather than truncating
 * a finished answer.
 */
internal interface KeySource : AutoCloseable {

    /** The key at the head, or `null` when this source is exhausted. */
    fun peek(): Key?

    /** Moves past the head. */
    fun advance()

    override fun close(): Unit = Unit
}

/** Candidate ordinals of one segment, decoded to keys one at a time. */
internal class SegmentKeys(
    val segment: Long,
    private val ordinals: Ordinals,
    private val reader: LeafReader,
) : KeySource {

    private val cursor: BitmapCursor = ordinals.candidates.cursor()
    private var current: Key? = null

    /**
     * The ordinal of the head, or `-1` when exhausted.
     *
     * Exposed because a projection read from a column is a read *at an ordinal*: the key it was
     * decoded into is not enough to find the value again without a second lookup.
     */
    var ordinal: Int = -1
        private set

    private var started = false

    /** Whether the index decided the head outright, rather than merely admitting it. */
    val isCertain: Boolean get() = ordinal >= 0 && ordinals.certain.contains(ordinal)

    override fun peek(): Key? {
        if (!started) {
            started = true
            step()
        }
        return current
    }

    override fun advance() {
        peek()
        step()
    }

    private fun step() {
        if (cursor.next()) {
            ordinal = cursor.value
            current = reader.keyAt(segment, ordinal)
        } else {
            ordinal = -1
            current = null
        }
    }
}

/**
 * Documents read from the store, over whichever sources the index could not answer for.
 *
 * The document is exposed because it is already in hand: where the plan can show these sources are
 * the whole of what a key could be in, the recheck is that document and costs nothing further.
 */
internal class ScanKeys(private val cursor: DocumentCursor) : KeySource {

    private var current: Key? = null
    private var document: Variant? = null
    private var started = false

    /** The document at the head. Valid until [advance]. */
    val head: Variant? get() = document

    override fun peek(): Key? {
        if (!started) {
            started = true
            step()
        }
        return current
    }

    override fun advance() {
        peek()
        step()
    }

    override fun close(): Unit = cursor.close()

    private fun step() {
        if (cursor.next()) {
            current = cursor.key
            document = cursor.document
        } else {
            current = null
            document = null
        }
    }
}
