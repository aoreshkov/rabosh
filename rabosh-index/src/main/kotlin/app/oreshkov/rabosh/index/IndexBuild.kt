package app.oreshkov.rabosh.index

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What a background index build is doing, or what it ended as. */
public enum class IndexBuildState {
    /** Queued or scanning. The only state that is not terminal. */
    RUNNING,

    /** Every segment the pass saw was covered or built. */
    COMPLETED,

    /**
     * Stopped by [IndexBuild.cancel] or by the catalog closing.
     *
     * **Not a failure, and not a state anything has to repair.** A cancelled build leaves an index
     * that is defined and partially covered, which is exactly what a crashed build leaves and exactly
     * what a build still running looks like from outside — and every query already handles it by
     * scanning the segments the index does not cover. That is why cancellation needed no rollback:
     * there is nothing to roll back to that is not already a state the engine lives in.
     */
    CANCELLED,

    /** The pass threw. See [IndexBuild.failure]; whatever was built before it is still valid. */
    FAILED,
}

/**
 * How far a background build has got.
 *
 * A snapshot, taken under the build's lock: the four fields agree with each other, which they would
 * not if a caller read four properties in a row while the worker ran.
 */
public class IndexBuildProgress internal constructor(
    /**
     * Segments live when the pass began.
     *
     * An estimate rather than a contract. It is read immediately before the scan, and the scan pins
     * its own version, so a flush landing in that window can leave [segmentsVisited] one short or one
     * over. Deliberately not renormalised afterwards: a percentage that moved backwards because the
     * store grew would be worse than one that can miss its own total by a segment.
     */
    public val segmentsTotal: Int,
    /**
     * Segments the pass has reached, whether or not they needed building.
     *
     * Skips count as progress, because they are progress: a second build over a mostly covered store
     * visits every segment and builds almost none, and a figure that only moved on a build would show
     * that one as stalled from start to finish.
     */
    public val segmentsVisited: Int,
    /** Segments this pass actually wrote sidecars for. */
    public val segmentsBuilt: Int,
    public val state: IndexBuildState,
) {
    /** Whether the build has stopped, for any of the three reasons it can. */
    public val isDone: Boolean get() = state != IndexBuildState.RUNNING

    /**
     * How much of the pass is done, in `0.0..1.0`.
     *
     * Clamped, because [segmentsTotal] is an estimate and a progress bar that reads 103% is a worse
     * answer than one that sits at 100% for a moment.
     */
    public val fraction: Double
        get() = when {
            segmentsTotal <= 0 -> if (isDone) 1.0 else 0.0
            else -> (segmentsVisited.toDouble() / segmentsTotal).coerceIn(0.0, 1.0)
        }

    override fun toString(): String =
        "IndexBuildProgress($state, $segmentsVisited/$segmentsTotal segment(s), $segmentsBuilt built)"
}

/**
 * A sidecar build running on the index catalog's own thread.
 *
 * ```kotlin
 * val build = indexes.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
 * build.handle                       // usable immediately — see below
 * println(build.progress)            // IndexBuildProgress(RUNNING, 12/40 segment(s), 12 built)
 * build.cancel()                     // stops at the next segment boundary
 * build.await()                      // blocks until it has, rethrowing any failure
 * ```
 *
 * **The handle is usable the moment this returns, and that is not a convenience.** `createIndex`
 * already makes the definition durable *before* a single posting file exists, because the alternative
 * leaves posting files for an index nothing knows about. A background build inherits that ordering
 * unchanged, so by the time there is an [IndexBuild] there is a registered index — one that covers no
 * segments yet, which every query already handles by scanning. There is no cutover and never was one.
 *
 * **Cancellation is safe because coverage is honest.** [cancel] stops the pass at the next segment
 * boundary and undoes nothing: what is left is an index defined over some segments and not others,
 * which is indistinguishable from a build that is still running, from one a crash interrupted, and
 * from one whose segment hit its term budget. `IndexCoverage` reports it, queries scan what is not
 * covered, and a later `createIndexInBackground` for the same definition finishes the job. Resumption
 * is not a feature here — it is what the per-segment sidecar design has always implied, and this is
 * the first thing able to reach it.
 *
 * **The segment in flight is finished rather than abandoned.** Abandoning writes nothing — nothing is
 * written until the observation completes — so it would throw away a scan that is nearly done and
 * leave exactly the segment a resumed build has to redo first. One segment is the granularity of the
 * whole design, so it is the granularity of stopping too.
 *
 * **Thread safety.** Safe from any thread. Every field is a snapshot taken under one lock.
 */
public class IndexBuild internal constructor(
    /**
     * The index this build was started for, or `null` for a pass over every defined index.
     *
     * `null` is what `IndexCatalog.buildIndexesInBackground` returns: covering whatever the sidecars
     * do not is a statement about the catalog rather than about one index, and inventing a handle for
     * it would name one of several arbitrarily.
     */
    public val handle: IndexHandle?,
) {
    private val lock = ReentrantLock()
    private val finished = lock.newCondition()

    private var total = 0
    private var visited = 0
    private var built = 0
    private var cancelRequested = false
    private var current = IndexBuildState.RUNNING
    private var thrown: Throwable? = null

    /** A consistent snapshot of how far this has got. */
    public val progress: IndexBuildProgress
        get() = lock.withLock { IndexBuildProgress(total, visited, built, current) }

    public val state: IndexBuildState get() = lock.withLock { current }

    /** Whether the build has stopped, for any of the three reasons it can. */
    public val isDone: Boolean get() = lock.withLock { current != IndexBuildState.RUNNING }

    /**
     * What the pass threw, or `null`.
     *
     * A build fails as a whole only for something that stops the scan. A single segment that could not
     * be covered — a term budget hit, a sidecar that would not write — is reported through
     * `IndexCatalog.problems` and leaves the build running, because one uncovered segment is a
     * coverage fact rather than a build failure.
     */
    public val failure: Throwable? get() = lock.withLock { thrown }

    /**
     * Asks the build to stop at the next segment boundary.
     *
     * Returns immediately; use [await] to wait for it. Idempotent, and safe to call on a build that
     * has already finished — a build that completed a microsecond earlier stays [IndexBuildState.COMPLETED]
     * rather than being retroactively cancelled, because what it did is what it did.
     */
    public fun cancel() {
        lock.withLock {
            if (current != IndexBuildState.RUNNING) return
            cancelRequested = true
        }
    }

    /**
     * Blocks until the build stops, then returns how it went.
     *
     * @throws Throwable whatever the pass failed with, rethrown here for the reason
     *   `Maintenance.awaitIdle` rethrows in the storage core: a failure nobody is told about is a
     *   failure nobody can act on. Read [progress] instead to inspect without throwing.
     */
    public fun await(): IndexBuildProgress {
        val snapshot = lock.withLock {
            while (current == IndexBuildState.RUNNING) finished.await()
            IndexBuildProgress(total, visited, built, current) to thrown
        }
        snapshot.second?.let { throw it }
        return snapshot.first
    }

    /**
     * Blocks for at most [timeout] [unit]s.
     *
     * `Long` and [TimeUnit] rather than a `kotlin.time.Duration`, which is what a Kotlin API written
     * today would reach for first. Two reasons, and the second is the deciding one. This wraps
     * `Condition.awaitNanos` and sits beside `ExecutorService.awaitTermination`, so it is the idiom a
     * caller of either already has in hand. And `Duration` is a value class, so it **mangles the JVM
     * method name** — `await-LRDsOJo` — which would be the only such name in this project's published
     * ABI and would not be callable from Java by any name a person would guess. Every other public
     * signature here is plainly Java-callable, and a published ABI is the wrong place to make that
     * stop being true.
     *
     * @return `true` if the build has stopped, `false` if it is still running. Does **not** rethrow —
     *   a caller polling with a timeout is asking whether it is done, and a timed wait that sometimes
     *   throws is awkward to write around. Check [failure] or call [await].
     */
    public fun await(timeout: Long, unit: TimeUnit): Boolean {
        var remaining = unit.toNanos(timeout)
        return lock.withLock {
            // `awaitNanos` returns what is left of the request, and may return early for reasons of
            // its own, so the remainder is carried round rather than the timeout being trusted to have
            // elapsed after one pass.
            while (current == IndexBuildState.RUNNING && remaining > 0) {
                remaining = finished.awaitNanos(remaining)
            }
            current != IndexBuildState.RUNNING
        }
    }

    override fun toString(): String {
        val what = handle?.let { "index #${it.id} over ${it.path}" } ?: "every defined index"
        return "IndexBuild($what, $progress)"
    }

    // --- driven by IndexCatalog's worker ---------------------------------------------------------

    internal val isCancelled: Boolean get() = lock.withLock { cancelRequested }

    /** Records the size of the job, immediately before the scan starts. */
    internal fun begin(segmentsTotal: Int) {
        lock.withLock { total = segmentsTotal }
    }

    internal fun segmentVisited() {
        lock.withLock { visited++ }
    }

    internal fun segmentBuilt() {
        lock.withLock { built++ }
    }

    /** Moves the build to a terminal state and wakes everybody waiting. The first call wins. */
    internal fun finish(state: IndexBuildState, failure: Throwable? = null) {
        require(state != IndexBuildState.RUNNING) { "a build finishes in a terminal state, not $state" }
        lock.withLock {
            if (current != IndexBuildState.RUNNING) return
            current = state
            thrown = failure
            finished.signalAll()
        }
    }
}
