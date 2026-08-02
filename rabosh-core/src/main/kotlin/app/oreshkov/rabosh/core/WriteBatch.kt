package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant

/** What an operation does. The ids are written to disk and are therefore permanent. */
internal enum class OperationKind(val id: Int) {
    PUT(1),
    DELETE(2),
    ;

    companion object {
        /**
         * The kind with this id, or `null` if this build does not know it.
         *
         * `null` rather than a default: an unknown operation id means the log was written by a
         * newer implementation and cannot be replayed, not that the operation can be skipped.
         */
        fun ofId(id: Int): OperationKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One operation, with the document bytes it carries.
 *
 * A deletion holds [EMPTY] for both byte arrays rather than nulls, which keeps the encoder and the
 * memtable free of a nullable it would only have to re-check.
 */
internal class Operation(
    val kind: OperationKind,
    val key: Key,
    val metadata: ByteArray,
    val value: ByteArray,
) {
    companion object {
        val EMPTY: ByteArray = ByteArray(0)

        fun delete(key: Key): Operation = Operation(OperationKind.DELETE, key, EMPTY, EMPTY)
    }
}

/**
 * A group of writes that commit together.
 *
 * A batch is one log record, one checksum and — under [Durability.SYNC] — one `fsync`, so batching
 * is both the atomicity mechanism and the throughput mechanism. Recovery replays a record only if
 * its checksum holds, which is what makes a batch all-or-nothing across a crash.
 *
 * Atomicity here is a statement about *durability*: after recovery a batch is either wholly present
 * or wholly absent. It is not yet a statement about *visibility* — a concurrent reader may observe
 * part of a batch that is mid-apply, which is what snapshots exist to fix and where they will.
 *
 * Not thread-safe; build a batch on one thread and hand it to [DocumentStore.write].
 */
public class WriteBatch {
    private val operations = ArrayList<Operation>()

    /** Number of operations, which is also the number of sequence numbers the commit consumes. */
    public val size: Int get() = operations.size

    /** `true` when nothing has been staged. Writing an empty batch does nothing. */
    public fun isEmpty(): Boolean = operations.isEmpty()

    /**
     * Stages [document] under [key], replacing whatever is there.
     *
     * The document's metadata and value bytes are copied now, not at commit time. A [Variant] is a
     * view over a buffer, and [app.oreshkov.rabosh.variant.VariantBuilder] reuses its buffer for
     * the next document — so a batch that kept the view would commit whatever happened to be in
     * that buffer later.
     */
    public fun put(key: Key, document: Variant): WriteBatch {
        operations += Operation(
            OperationKind.PUT,
            key,
            document.metadata.toByteArray(),
            document.toByteArray(),
        )
        return this
    }

    /**
     * Stages a deletion of [key].
     *
     * Deleting an absent key is legal and still costs a record: an LSM-tree cannot know whether the
     * key exists in some segment it has not read, so a deletion is written as a tombstone rather
     * than resolved on the spot.
     */
    public fun delete(key: Key): WriteBatch {
        operations += Operation.delete(key)
        return this
    }

    /** Discards every staged operation, so the batch can be reused. */
    public fun clear(): Unit = operations.clear()

    internal fun operations(): List<Operation> = operations

    override fun toString(): String = "WriteBatch(size=$size)"
}
