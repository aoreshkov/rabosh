package app.oreshkov.rabosh.core

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A key together with the sequence number of the commit that wrote it.
 *
 * Ordered by key ascending, then by sequence **descending**, so the newest version of a key is the
 * first entry for that key. A lookup is then one `ceilingEntry` — no scan over older versions, and
 * no separate index of which version is current.
 */
internal class InternalKey(val userKey: Key, val sequence: Long) : Comparable<InternalKey> {
    override fun compareTo(other: InternalKey): Int {
        val byKey = userKey.compareTo(other.userKey)
        if (byKey != 0) return byKey
        return other.sequence.compareTo(sequence)
    }

    override fun toString(): String = "$userKey@$sequence"
}

/** What a memtable holds for one version of a key. */
internal sealed interface MemtableValue {
    /**
     * A tombstone.
     *
     * A deletion has to be *stored*, not applied by removing the entry: the key may also exist in a
     * segment that has not been read, and only a tombstone that sorts above it can hide it. This is
     * why an object rather than a `null` value — the absence of an entry and a recorded deletion
     * mean different things, and a map that cannot hold nulls would blur them.
     */
    data object Deleted : MemtableValue

    /** A document, as the metadata and value bytes exactly as they were committed. */
    class Present(val metadata: ByteArray, val value: ByteArray) : MemtableValue
}

/**
 * The in-memory half of the LSM-tree: everything committed but not yet in a segment.
 *
 * `ConcurrentSkipListMap` rather than a lock or a copy-on-write map, because it is exactly the shape
 * the engine's concurrency model wants. One writer inserts; any number of readers walk the map with
 * no lock and no coordination, and see a consistent view of every entry that was published before
 * they looked. It is also the same structure LevelDB and RocksDB use for their memtables, for the
 * same reason: ordered iteration is what the flush to a sorted segment needs, and a hash map cannot
 * give it.
 *
 * Every version is kept rather than overwritten. That is what lets a reader hold a sequence number
 * and see the store as it was, and it is the reason a memtable's size is bounded by bytes written
 * rather than by distinct keys.
 */
internal class Memtable {
    private val entries = ConcurrentSkipListMap<InternalKey, MemtableValue>()
    private val bytes = AtomicLong(0)

    /**
     * Approximate retained size in bytes: the data plus a fixed estimate per entry.
     *
     * Approximate is the honest word. The estimate covers the skip-list node, its forward pointers,
     * the internal key and the two array headers; the real figure depends on the collector's layout,
     * which no portable API reports. It is used only to decide when to seal the memtable, where
     * being within a factor of anything sensible is enough.
     */
    val approximateBytes: Long get() = bytes.get()

    /** Number of versions held, which is at least the number of distinct keys. */
    val entryCount: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun put(key: Key, sequence: Long, metadata: ByteArray, value: ByteArray) {
        insert(key, sequence, MemtableValue.Present(metadata, value), metadata.size + value.size)
    }

    fun delete(key: Key, sequence: Long) {
        insert(key, sequence, MemtableValue.Deleted, 0)
    }

    private fun insert(key: Key, sequence: Long, value: MemtableValue, payloadBytes: Int) {
        entries[InternalKey(key, sequence)] = value
        bytes.addAndGet(key.size.toLong() + payloadBytes + ENTRY_OVERHEAD_BYTES)
    }

    /**
     * The newest version of [key] at or below [maxSequence], or `null` if this memtable holds none.
     *
     * A returned [MemtableValue.Deleted] is an answer, not an absence: it means this memtable knows
     * the key was deleted, and no older source may be consulted.
     *
     * The bound is free. Sequences sort descending, so `(key, maxSequence)` is *below* every newer
     * version of the key and *above* every older one, and its ceiling is therefore the version that
     * was current at that sequence — with no scan over the newer ones to skip them. That is the
     * whole implementation of a snapshot read in memory, and the reason the comparator is arranged
     * this way.
     */
    fun get(key: Key, maxSequence: Long = Long.MAX_VALUE): MemtableValue? {
        val entry = entries.ceilingEntry(InternalKey(key, maxSequence)) ?: return null
        return if (entry.key.userKey == key) entry.value else null
    }

    /**
     * Every version held, in key order and newest-first within a key.
     *
     * This is the order a sorted segment is written in, so a flush is a single pass over it.
     */
    fun entries(): Sequence<Map.Entry<InternalKey, MemtableValue>> = entries.entries.asSequence()

    override fun toString(): String = "Memtable(entries=$entryCount, bytes=$approximateBytes)"

    companion object {
        /** Estimated per-entry overhead: skip-list node and forward pointers, key and value objects. */
        const val ENTRY_OVERHEAD_BYTES: Int = 96
    }
}
