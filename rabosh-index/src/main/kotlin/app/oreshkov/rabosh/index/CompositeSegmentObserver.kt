package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObservation
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.SegmentSummary
import app.oreshkov.rabosh.variant.Variant

/**
 * Feeds several observers from the one `StoreOptions.segmentObserver` slot.
 *
 * A store that wants both a `SchemaCatalog` and an [IndexCatalog] needs two observers and core offers
 * one place to put them. Composing here rather than making the option a list is deliberate: the core
 * wraps whatever it is given in a guard that abandons an observation when a callback throws, and that
 * guard is *per observer* on purpose — a broken catalog must not cost a document, and by exactly the
 * same argument a broken catalog must not cost the index its segment either. A list inside core would
 * put that isolation policy in the one module that has no business deciding it.
 *
 * So the composite is the isolation. A child that throws is dropped **for that segment**, its own
 * `observerFailed` is told, and every other child carries on. Nothing propagates out of `observe` or
 * `complete`, which is what the core's guard would otherwise see.
 *
 * ```kotlin
 * val catalog = SchemaCatalog(directory)
 * val indexes = IndexCatalog(directory)
 * DocumentStore.open(
 *     directory,
 *     StoreOptions(segmentObserver = CompositeSegmentObserver(listOf(catalog, indexes))),
 * ).use { store ->
 *     catalog.attach(store)
 *     indexes.attach(store)
 *     // …
 * }
 * ```
 *
 * **Two attachments mean two full scans** of anything neither covers. Nothing here can avoid that:
 * `DocumentStore.backfill` takes one observer and each layer decides for itself which segments it
 * needs. The single co-attaching pass is `Rabosh` in `rabosh-api`, which attaches both layers with
 * `backfill = false` and then runs **one** backfill through a composite — so the composition above is
 * what makes that pass possible rather than merely tidy, and a caller who does not want the facade
 * can do the same three lines by hand.
 */
public class CompositeSegmentObserver(
    /** The observers, called in order. */
    public val observers: List<SegmentObserver>,
) : SegmentObserver {

    public constructor(vararg observers: SegmentObserver) : this(observers.toList())

    init {
        require(observers.isNotEmpty()) { "a composite observer needs at least one observer" }
    }

    override fun beginSegment(segmentNumber: Long): SegmentObservation? {
        // An observation is opened if *any* child wants one. A backfill where the catalog already
        // covers this segment and the index does not is the ordinary case, not an edge one.
        val opened = ArrayList<Pair<SegmentObserver, SegmentObservation>>(observers.size)
        for (observer in observers) {
            val observation = try {
                observer.beginSegment(segmentNumber)
            } catch (failure: Throwable) {
                report(observer, failure)
                null
            }
            if (observation != null) opened.add(observer to observation)
        }
        return if (opened.isEmpty()) null else Fanout(opened)
    }

    override fun retain(liveSegments: Set<Long>) {
        for (observer in observers) {
            try {
                observer.retain(liveSegments)
            } catch (failure: Throwable) {
                report(observer, failure)
            }
        }
    }

    /**
     * Reached only when a child's own [SegmentObserver.observerFailed] threw.
     *
     * The core treats a throw from there as the observer opting into being fatal, and that opt-in is
     * preserved rather than swallowed: a child that would rather stop the engine than run with a
     * stale model still can.
     */
    override fun observerFailed(cause: Throwable) {
        throw cause
    }

    override fun toString(): String = "CompositeSegmentObserver(${observers.joinToString()})"

    private fun report(observer: SegmentObserver, failure: Throwable) {
        observer.observerFailed(failure)
    }

    /** One segment, fanned out. A child that throws is dropped for this segment and no other. */
    private inner class Fanout(
        private val children: MutableList<Pair<SegmentObserver, SegmentObservation>>,
    ) : SegmentObservation {

        override fun observe(userKey: Key, sequence: Long, document: Variant?) {
            forEachLive { it.observe(userKey, sequence, document) }
        }

        override fun complete(summary: SegmentSummary) {
            forEachLive { it.complete(summary) }
            children.clear()
        }

        override fun abandon() {
            forEachLive { it.abandon() }
            children.clear()
        }

        private inline fun forEachLive(body: (SegmentObservation) -> Unit) {
            val iterator = children.iterator()
            while (iterator.hasNext()) {
                val (observer, observation) = iterator.next()
                try {
                    body(observation)
                } catch (failure: Throwable) {
                    // Abandon this child, tell it, and remove it. Everything else keeps its segment.
                    iterator.remove()
                    try {
                        observation.abandon()
                    } catch (secondary: Throwable) {
                        failure.addSuppressed(secondary)
                    }
                    report(observer, failure)
                }
            }
        }
    }
}
