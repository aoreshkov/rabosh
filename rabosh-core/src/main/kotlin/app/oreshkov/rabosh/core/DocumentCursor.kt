package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant

/**
 * An ordered walk over the documents a store holds, as one view of it sees them.
 *
 * ```kotlin
 * store.scan(from = Key.of("user:"), to = Key.of("user:~")).use { cursor ->
 *     while (cursor.next()) println("${cursor.key} -> ${cursor.document.toJsonString()}")
 * }
 * ```
 *
 * **One entry per key, newest first, tombstones skipped.** Underneath, a key exists once per commit
 * that touched it, spread across memtables and every level of the tree; the merge emits all of them
 * in order and this collapses them. A deleted key produces nothing rather than an entry with no
 * document, because a caller iterating documents should not have to know that deletions are stored.
 *
 * **A cursor is a view, not a copy.** [document] reads straight out of a mapped segment, so the
 * cursor holds the segments it is walking for as long as it is open — which is why it is
 * [AutoCloseable] and why leaving one open holds disk space the way a [Snapshot] does. Both [key]
 * and [document] are valid until the next [next].
 *
 * Not thread-safe: one cursor belongs to one thread. Any number of cursors may run at once.
 */
public class DocumentCursor internal constructor(
    private val merged: EntryCursor,
    private val maxSequence: Long,
    private val from: Key?,
    private val to: Key?,
    private val ownedSnapshot: Snapshot?,
) : AutoCloseable {

    private var currentKey: Key? = null
    private var currentDocument: Variant? = null
    private var started = false
    private var closed = false
    private var lastEmitted: Key? = null

    /**
     * The key the cursor is on.
     *
     * @throws IllegalStateException before the first [next], or after one returns `false`.
     */
    public val key: Key
        get() = checkNotNull(currentKey) { "the cursor is not on an entry" }

    /** The document the cursor is on. See [key] for when it is valid. */
    public val document: Variant
        get() = checkNotNull(currentDocument) { "the cursor is not on an entry" }

    /**
     * Advances to the next document, and returns whether there is one.
     *
     * @throws StoreClosedException if the store or the snapshot behind this cursor has been closed.
     */
    public fun next(): Boolean {
        check(!closed) { "the cursor is closed" }
        ownedSnapshot?.checkOpen()
        if (!started) {
            started = true
            if (from == null) merged.seekToFirst() else merged.seek(SegmentFormat.seekKey(from, maxSequence))
        }

        while (merged.valid()) {
            val userKey = merged.userKey()
            val sequence = merged.sequence()

            // A key already emitted is being seen again as an older version of itself; a key with a
            // sequence above the bound was written after this view was taken. Both are skipped
            // without any decision about what they hold.
            if (userKey == lastEmitted || sequence > maxSequence) {
                merged.next()
                continue
            }
            if (to != null && userKey > to) break

            // The first version at or below the bound is the current one, so whatever it says is
            // the answer — including a tombstone, which ends this key rather than continuing past it.
            lastEmitted = userKey
            val document = merged.document()
            if (document == null) {
                merged.next()
                continue
            }
            currentKey = userKey
            currentDocument = document
            merged.next()
            return true
        }

        currentKey = null
        currentDocument = null
        return false
    }

    /** Releases the segments this cursor was reading. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        currentKey = null
        currentDocument = null
        try {
            merged.close()
        } finally {
            ownedSnapshot?.close()
        }
    }
}
