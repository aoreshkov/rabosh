package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import java.util.Arrays

/**
 * A listener on the documents that pass through segment writing.
 *
 * This is the hook the modelling and indexing layers hang off, and it exists because **flush and
 * compaction already walk every document**. Statistics gathered on a pass the engine is making
 * anyway cost the walk, not a scan — which is the whole reason "model later" is cheap here rather
 * than a batch job somebody has to schedule.
 *
 * It is declared in `rabosh-core` and implemented above it. Dependencies flow strictly downward, so
 * the core cannot know what a catalog or an index is; what it can do is say *when* a document is
 * being written and to which segment, and let the layer that cares decide what to keep.
 *
 * **What an implementation may assume.** Calls for one segment are made on one thread, in key order,
 * between one [beginSegment] and the matching [SegmentObservation.complete] or
 * [SegmentObservation.abandon]. Several segments may be in flight at once — a compaction cuts its
 * output into more than one file — so per-segment state must live in the [SegmentObservation], not
 * in the observer.
 *
 * **What an implementation must not do.** Block for long, or throw. A failure in derived data must
 * not take down the engine, so a callback that throws abandons that segment's observation and is
 * reported to [observerFailed]; the write itself carries on. An observer that would rather be fatal
 * says so by throwing from [observerFailed], which does propagate.
 */
public interface SegmentObserver {
    /**
     * A segment is about to be written, or an existing one rescanned by [DocumentStore.backfill].
     *
     * @return an observation to receive the segment's documents, or `null` to skip this segment —
     *   which is how a backfill says "I already have this one".
     */
    public fun beginSegment(segmentNumber: Long): SegmentObservation?

    /**
     * The segments the live version names, as of now.
     *
     * Anything absent has **left the tree** and will never be read again, so whatever was derived
     * from it can be dropped. Called after every change to the set of live segments and once when a
     * store finishes recovery.
     *
     * Note the ordering trap for an implementation that deletes files here: this is called during
     * [DocumentStore.open], which is before an implementation attached to the store has had a chance
     * to load anything of its own. Deleting on the first call would delete derived data that has not
     * been read yet.
     */
    public fun retain(liveSegments: Set<Long>)

    /**
     * One of this observer's own callbacks threw, and the segment it was observing was abandoned.
     *
     * The default does nothing, which keeps a broken observer from stopping the engine — derived
     * data is rebuildable and documents are not. Throwing from here **does** propagate, so an
     * implementation that would rather fail loudly than run with a stale model can say so.
     */
    public fun observerFailed(cause: Throwable) {
        // Deliberately empty: see the KDoc. Overriding is how an observer opts into being fatal.
    }
}

/**
 * One segment's worth of observation.
 *
 * Exactly one of [complete] and [abandon] is called, once, and nothing is called after it.
 */
public interface SegmentObservation {
    /**
     * The newest version of one user key in this segment.
     *
     * **One call per distinct key, not per version.** A memtable can hold three writes of the same
     * key and a compaction can carry several versions of one forward; counting all of them would
     * make a document that was updated look like three documents.
     *
     * [document] is `null` for a tombstone — the segment records that the key was deleted, which is
     * a fact rather than an absence. It is a view over bytes that are valid **only for the duration
     * of this call**; anything kept must be copied.
     */
    public fun observe(userKey: Key, sequence: Long, document: Variant?)

    /**
     * The segment is written and forced. The manifest is about to name it.
     *
     * Derived data written here does not have to be forced before the manifest record the way the
     * segment itself does: it can always be rebuilt from the segment by rescanning, so losing it to
     * a power cut costs time rather than data. What it must not do is claim coverage it does not
     * have — a missing sidecar has to read as "not collected", never as "collected and empty".
     */
    public fun complete(summary: SegmentSummary)

    /**
     * The segment was abandoned and no version will ever name it.
     *
     * A failed compaction, a failed flush, or a failure inside this observation itself. Whatever was
     * accumulated is to be discarded.
     */
    public fun abandon()
}

/**
 * What an observer is told about a finished segment.
 *
 * Deliberately not `SegmentMetadata`, which is internal and carries the key range the version set
 * routes on — an observer has no business with the shape of the tree, only with the file it was
 * just shown.
 */
public class SegmentSummary internal constructor(
    /** The segment's file number. */
    public val segmentNumber: Long,
    /** Versions written, counting superseded ones and tombstones. */
    public val entryCount: Long,
    /** Distinct user keys, which is how many times [SegmentObservation.observe] was called. */
    public val distinctKeyCount: Long,
    /** Size of the finished file on disk. */
    public val fileBytes: Long,
) {
    override fun toString(): String =
        "SegmentSummary(#$segmentNumber, $entryCount entries, $distinctKeyCount keys, $fileBytes bytes)"
}

/**
 * Calls an observer's entry points without letting a failure in one reach the write path.
 *
 * The asymmetry is deliberate and is stated in [SegmentObserver]: a broken catalog must not cost a
 * document. Once a callback throws, the observation is abandoned and nothing more is sent to it.
 */
internal object Observers {
    fun begin(observer: SegmentObserver?, segmentNumber: Long): ObservationGuard? {
        if (observer == null) return null
        val observation = try {
            observer.beginSegment(segmentNumber)
        } catch (failure: Throwable) {
            observer.observerFailed(failure)
            null
        }
        return observation?.let { ObservationGuard(observer, it) }
    }

    fun retain(observer: SegmentObserver?, liveSegments: Set<Long>) {
        if (observer == null) return
        try {
            observer.retain(liveSegments)
        } catch (failure: Throwable) {
            observer.observerFailed(failure)
        }
    }
}

/** A [SegmentObservation] that has been made safe to call. See [Observers]. */
internal class ObservationGuard(
    private val observer: SegmentObserver,
    private val observation: SegmentObservation,
) {
    private var live = true

    fun observe(userKey: Key, sequence: Long, document: Variant?) {
        if (!live) return
        guarded { observation.observe(userKey, sequence, document) }
    }

    fun complete(summary: SegmentSummary) {
        if (!live) return
        live = false
        guarded { observation.complete(summary) }
    }

    fun abandon() {
        if (!live) return
        live = false
        try {
            observation.abandon()
        } catch (failure: Throwable) {
            observer.observerFailed(failure)
        }
    }

    private inline fun guarded(body: () -> Unit) {
        try {
            body()
        } catch (failure: Throwable) {
            live = false
            try {
                observation.abandon()
            } catch (secondary: Throwable) {
                failure.addSuppressed(secondary)
            }
            // Not swallowed: the observer is told, and an observer that wants this to be fatal
            // throws from here.
            observer.observerFailed(failure)
        }
    }
}

/**
 * Reduces a run of internal keys in order to one event per distinct user key.
 *
 * Shared by [SegmentWriter] and [DocumentStore.backfill] rather than written twice, because the two
 * have to agree: a segment sketched on the write path and the same segment sketched by a backfill
 * must produce the same counts, or "model later" would quietly mean "model differently".
 *
 * Takes bytes rather than a [Key] because callers have an encoded internal key in hand and this runs
 * once per version of every document; extracting a [Key] to compare would allocate for all of them.
 */
internal class DistinctKeyFilter {
    private var lastKey = ByteArray(0)
    private var lastLength = -1

    /** Distinct user keys seen so far. */
    var count: Long = 0
        private set

    /** Whether the user key in `[0, userKeyLength)` of [key] differs from the previous one. */
    fun isNewKey(key: ByteArray, userKeyLength: Int): Boolean {
        if (userKeyLength == lastLength &&
            Arrays.equals(lastKey, 0, userKeyLength, key, 0, userKeyLength)
        ) {
            return false
        }
        if (lastKey.size < userKeyLength) lastKey = ByteArray(userKeyLength)
        key.copyInto(lastKey, 0, 0, userKeyLength)
        lastLength = userKeyLength
        count++
        return true
    }
}
