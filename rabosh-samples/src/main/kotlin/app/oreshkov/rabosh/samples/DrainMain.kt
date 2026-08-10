package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import java.nio.file.Path

/**
 * **A staging buffer, drained.** Ingest, ship a batch onward, record how far you got, retire what you
 * shipped — and prove nothing was lost or shipped twice.
 *
 * ```
 * ./gradlew :rabosh-samples:runDrain
 * ```
 *
 * This is the one part of the lakehouse-staging case that is pure integration: rabosh holds events
 * until something downstream — a Parquet writer, an Iceberg commit, a queue — has taken them, and the
 * loop that hands them over is the caller's to write. Every mistake in it is **silent**. A watermark
 * advanced before the ship succeeds loses data with nothing to say so. A scan without a snapshot can
 * see a compaction land underneath it and hand over a document twice or not at all. A drain that
 * never compacts leaves the tombstones and grows for ever while reporting that it deleted everything.
 *
 * So the order is the deliverable, and it is five calls:
 *
 * ```kotlin
 * db.snapshot().use { view ->                       // 1. pin the view
 *     val shipped = db.scan(from = watermark, snapshot = view).use { … }   // 2. read from it
 *     ship(shipped)                                 // 3. hand over — and only if it returns
 *     watermark = shipped.last().key.successor()    // 4. *then* record how far
 * }
 * db.deleteRange(to = lastShipped)                  // 5. retire what was shipped
 * db.compact()                                      //    and let compaction reclaim it
 * ```
 *
 * **Deliberately not a `DrainCursor`.** The pattern is those calls in that order; wrapping them would
 * add a concept the layers below do not have, and the facade rule is that it may change ergonomics
 * and never answers. What is worth showing is the *order*, which a wrapper would hide.
 *
 * The watermark is kept in memory here because a sample has nowhere better. In a real deployment it
 * belongs wherever the downstream commit is recorded — the same transaction, ideally — because the
 * one thing that must never happen is a watermark that advanced past a ship that did not.
 */
object DrainMain {

    private const val EVENT_COUNT = 6_000
    private const val BATCH_SIZE = 500

    /** Events handed downstream per drain round. Small enough that the sample runs several rounds. */
    private const val DRAIN_BATCH = 1_500

    @JvmStatic
    fun main(arguments: Array<String>) {
        SampleRun.entryPoint(arguments, "drain", ::run)
    }

    /** The sample itself. Takes a directory so the suite can run it against a temporary one. */
    fun run(directory: Path) {
        Rabosh.open(directory, SampleRun.options()).use { db ->
            fillTheBuffer(db)
            val shipped = drainInRounds(db)
            checkpointWhileWriting(db, directory)
            reportWhatIsLeft(db, shipped)
        }
    }

    // --- 1. the buffer fills ---------------------------------------------------------------------

    private fun fillTheBuffer(db: Rabosh) {
        SampleRun.heading("1.", "Ingest")
        println("writing $EVENT_COUNT events into the staging buffer")
        SampleRun.load(db, EVENT_COUNT, BATCH_SIZE)
        println("buffered: ${db.stats.segmentCount} segment(s), ${db.stats.segmentBytes} bytes on disk")
        SampleRun.note("the buffer is an ordinary store; nothing here is a queue")
        SampleRun.note("keys are time-ordered, which is what makes retention a key range")
    }

    // --- 2. the drain loop ------------------------------------------------------------------------

    /**
     * Drains until the buffer is empty, returning every key that was handed downstream.
     *
     * The returned list is what the assertions are made against: it must hold every event exactly
     * once, in order, and it is built only from events the downstream actually accepted.
     */
    private fun drainInRounds(db: Rabosh): List<Key> {
        SampleRun.heading("2.", "Drain")

        val shipped = ArrayList<Key>(EVENT_COUNT)
        var watermark: Key? = null
        var round = 0

        while (true) {
            round++
            // 1. Pin the view. Everything this round reads comes from here, so a flush or a
            //    compaction landing mid-round cannot change what it sees.
            val batch = db.snapshot().use { view -> readBatch(db, view, watermark) }
            if (batch.isEmpty()) break

            // 2. Hand over. If this throws, nothing below runs: the watermark does not move and the
            //    events are still in the buffer, so the next attempt ships them again. At-least-once
            //    is the honest guarantee here, and it is the reason the order is this way round.
            shipDownstream(round, batch)

            // 3. *Then* record how far. After the ship, never before.
            shipped += batch.map { it.key }
            watermark = batch.last().key.successor()

            // 4. Retire what was shipped, and let compaction reclaim it. `deleteRange` writes the
            //    tombstones; only a compaction removes the documents they hide.
            val retired = db.deleteRange(to = batch.last().key)
            db.compact()
            println(
                "   round $round: shipped ${batch.size}, retired $retired, " +
                    "${db.stats.segmentCount} segment(s) left",
            )
        }

        SampleRun.note("the watermark moves only after a successful ship, so a failure re-ships")
        SampleRun.note("deleteRange writes one tombstone per key; compact() is what reclaims the space")
        check(shipped.size == EVENT_COUNT) {
            "every event must be shipped exactly once: ${shipped.size} of $EVENT_COUNT"
        }
        check(shipped == shipped.sorted()) { "events must be shipped in key order" }
        check(shipped.toSet().size == shipped.size) { "no event may be shipped twice" }
        println()
        println("drained $EVENT_COUNT events in $round round(s), each exactly once, in key order")
        return shipped
    }

    /** One round's worth of events, read from the pinned view and copied out of it. */
    private fun readBatch(db: Rabosh, view: Snapshot, watermark: Key?): List<Event> {
        val batch = ArrayList<Event>(DRAIN_BATCH)
        db.scan(from = watermark, snapshot = view).use { cursor ->
            while (batch.size < DRAIN_BATCH && cursor.next()) {
                // Copied, not referenced. A document is a view over a mapped segment and is valid
                // only until the next `next()` — so anything that outlives the loop must be a copy,
                // which for a hand-off downstream is what you wanted anyway.
                batch += Event(cursor.key, cursor.document.toByteArray())
            }
        }
        return batch
    }

    /**
     * Stands in for the thing that actually takes the events: a Parquet writer, an Iceberg commit,
     * a queue.
     *
     * It only has to return normally to mean "these are yours now". Throwing here would leave the
     * watermark where it was, which is the property the loop is arranged around.
     */
    private fun shipDownstream(round: Int, batch: List<Event>) {
        var bytes = 0L
        for (event in batch) bytes += event.bytes.size
        check(bytes > 0) { "round $round shipped no bytes" }
    }

    private class Event(val key: Key, val bytes: ByteArray)

    // --- 3. a checkpoint, taken while the writer runs ----------------------------------------------

    /**
     * A consistent copy, taken without stopping.
     *
     * The other half of what a staging buffer needs: the recipe before `checkpoint` existed was *stop
     * writing and copy the directory*, and a buffer that is being written to cannot stop. Writes that
     * land during the call are simply above the checkpoint's sequence.
     */
    private fun checkpointWhileWriting(db: Rabosh, directory: Path) {
        SampleRun.heading("3.", "Checkpoint")

        for (index in EVENT_COUNT until EVENT_COUNT + 200) {
            db.put(SampleCorpus.key(index), SampleCorpus.json(index))
        }

        val target = directory.resolveSibling("${directory.fileName}-checkpoint")
        val info = db.checkpoint(target)
        println("checkpoint at sequence ${info.sequence}: ${info.segmentCount} segment(s), ${info.fileCount} file(s)")
        println("   ${if (info.hardLinked) "hard-linked" else "copied"}, ${info.bytes} bytes of segment data")

        // Writes that arrive after the checkpoint are above its sequence and are not in it.
        for (index in EVENT_COUNT + 200 until EVENT_COUNT + 400) {
            db.put(SampleCorpus.key(index), SampleCorpus.json(index))
        }

        Rabosh.open(target, SampleRun.options()).use { copy ->
            val inCopy = copy.get(SampleCorpus.key(EVENT_COUNT + 100)) != null
            val afterCopy = copy.get(SampleCorpus.key(EVENT_COUNT + 300)) != null
            check(inCopy) { "an event written before the checkpoint must be in it" }
            check(!afterCopy) { "an event written after the checkpoint must not be" }
            println("   the copy opens and holds the prefix as of that sequence, and nothing after it")
        }
        target.toFile().deleteRecursively()

        SampleRun.note("hard links mean a checkpoint costs a directory entry per file, not its bytes")
        SampleRun.note("it is a consistent view, not an off-site backup: moving it is the next step")
    }

    // --- 4. what is left --------------------------------------------------------------------------

    private fun reportWhatIsLeft(db: Rabosh, shipped: List<Key>) {
        SampleRun.heading("4.", "What is left")

        db.deleteRange()
        db.compact()

        var remaining = 0
        db.scan().use { cursor -> while (cursor.next()) remaining++ }
        check(remaining == 0) { "the buffer should be empty, and $remaining events are left" }

        println("shipped ${shipped.size} events, retired all of them, $remaining left in the buffer")
        println("on disk now: ${db.stats.segmentCount} segment(s), ${db.stats.segmentBytes} bytes")
        SampleRun.note("a drain that never compacted would report the same counts and keep the bytes")
    }
}
