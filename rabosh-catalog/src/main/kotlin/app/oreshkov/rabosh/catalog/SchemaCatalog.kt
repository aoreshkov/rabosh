package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObservation
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.SegmentSummary
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The model of what is in a store, derived from its documents as they are written.
 *
 * ```kotlin
 * val catalog = SchemaCatalog(directory)
 * DocumentStore.open(directory, StoreOptions(segmentObserver = catalog)).use { store ->
 *     store.put(Key.of("user:1"), Variant.fromJson("""{"name":"ada","team":"analytics"}"""))
 *     store.flush()
 *     catalog.attach(store)
 *
 *     println(catalog.inferSchema().render())
 *     catalog.indexCandidates().forEach(::println)
 * }
 * ```
 *
 * **Nothing here scans the store.** Statistics are collected on the flush and compaction passes that
 * were going to walk every document anyway, kept in a `.cat` sidecar next to each segment, and
 * folded on demand. A compaction that merges two segments into one replaces two sketches with one as
 * a consequence of the merge, so the model is never stale and there is no invalidation step to
 * forget. [attach] covers whatever was written before the catalog existed, by reading the segments
 * that are already there — which is the whole "model later" claim, and it is why an index
 * recommendation can be asked for on a store nobody planned to model.
 *
 * **Two objects, and the order matters.** The observer has to be installed in
 * [app.oreshkov.rabosh.core.StoreOptions] before
 * the store opens, because a flush can begin the moment it does; [attach] is what loads the sidecars
 * and backfills the rest, and until it is called this catalog answers nothing. That is deliberate —
 * a model that quietly reported on half a store would be worse than one that refuses. A caller who
 * would rather not remember any of it opens a `Rabosh` from `rabosh-api`, which owns the ordering
 * and reaches this catalog through one backfill pass shared with the index catalog.
 *
 * **Thread safety.** Safe to call from any thread. The store's maintenance thread drives
 * [beginSegment], [SegmentObservation.complete] and [retain] while a reader may be inside
 * [inferSchema]; the accumulation of one segment happens on one thread and is not shared.
 */
public class SchemaCatalog @RaboshExperimental constructor(
    /** The store directory sidecars live in. The same directory the store was opened on. */
    public val directory: Path,
    /** Tuning. See [CatalogOptions]. */
    public val options: CatalogOptions = CatalogOptions.DEFAULT,
) : SegmentObserver {

    private val lock = ReentrantLock()
    private val sketches = HashMap<Long, SegmentSketch>()
    private val failures = ArrayList<Throwable>()
    private var liveSegments: Set<Long> = emptySet()
    private var attached = false

    /** Whether [attach] has been called. Nothing is answered before it has. */
    public val isAttached: Boolean get() = lock.withLock { attached }

    /**
     * Failures raised inside this catalog's own callbacks, in order.
     *
     * A sketch that could not be written or a sidecar that would not decode lands here rather than
     * propagating into the write path — derived data must not cost a document. The segments involved
     * are simply not covered, which [CatalogCoverage] reports, and [rebuild] fixes.
     */
    public val problems: List<Throwable> get() = lock.withLock { failures.toList() }

    // --- attachment ----------------------------------------------------------------------------

    /**
     * Loads what is on disk, scans whatever is not, and starts maintaining the model.
     *
     * Idempotent and cheap to repeat: a segment already covered is skipped without being read.
     * Segments that have no sidecar are read once, in key order, exactly as a compaction would read
     * them — see [DocumentStore.backfill].
     *
     * @param backfill whether to build sketches for segments that have none. Passing `false` attaches
     *   to whatever is already on disk and returns without a scan — [CatalogCoverage] then reports
     *   the model as partial, which is the honest state rather than a degraded one. Two callers want
     *   it: one with a large store who will not pay a blocking scan at open, and `rabosh-api`, which
     *   attaches this catalog and the index catalog without backfilling and then runs **one**
     *   [DocumentStore.backfill] through a composite observer instead of one scan each.
     * @throws CorruptSketchException if a sidecar will not decode and
     *   [CatalogOptions.damagedSketches] is [DamagedSketchPolicy.REPORT].
     */
    @JvmOverloads
    public fun attach(store: DocumentStore, backfill: Boolean = true) {
        // Sidecars are found by listing rather than by asking the store which segments are live: a
        // store opened *without* this observer never called `retain`, and that is exactly the case
        // attaching later has to work for.
        loadSidecars()
        if (backfill) store.backfill(this)
        // Asked for rather than remembered. What `retain` last reported may predate a flush or a
        // compaction that landed during the scan, and reclamation is about to run against this set.
        val live = store.liveSegmentNumbers
        lock.withLock {
            liveSegments = live
            attached = true
        }
        prune(live)
        sweep(live)
    }

    /**
     * Discards everything and rebuilds it from the segments.
     *
     * The escape hatch that makes the rest of the design's relaxed attitude to sidecar durability
     * defensible: a sketch is derived, so damage costs a scan rather than data.
     */
    public fun rebuild(store: DocumentStore) {
        val known = lock.withLock {
            val numbers = sketches.keys.toList()
            sketches.clear()
            failures.clear()
            attached = false
            numbers
        }
        for (number in known) runCatching { SketchFile.delete(directory, number) }
        for (number in listSidecars()) runCatching { SketchFile.delete(directory, number) }
        attach(store)
    }

    // --- the model -----------------------------------------------------------------------------

    /**
     * Folds the live segments' sketches into one model.
     *
     * @throws CatalogStateException if [attach] has not been called.
     */
    public fun inferSchema(): InferredSchema {
        val live: Set<Long>
        val model: SegmentSketch
        var covered = 0
        lock.withLock {
            if (!attached) {
                throw CatalogStateException("the catalog for $directory has not been attached to a store")
            }
            live = liveSegments
            var folded = SegmentSketch.EMPTY
            // Sorted, so the fold order is the same on every call — which matters once the path
            // budget is reached, where the merge stops being exactly associative. See [SegmentSketch].
            for (number in live.sorted()) {
                val sketch = sketches[number] ?: continue
                folded = folded.merge(sketch, options.maxPaths)
                covered++
            }
            model = folded
        }

        val fields = model.entries().map { (path, sketch) ->
            InferredField(path, sketch, model.documentCount)
        }
        return InferredSchema(
            documentCount = model.documentCount,
            fields = fields,
            coverage = CatalogCoverage(covered, live.size),
            truncatedPathEstimate = model.estimatedDroppedPaths,
            truncatedObservations = model.droppedObservations,
        )
    }

    /**
     * The paths worth an index, best first.
     *
     * A report, not an action: nothing is built and nothing is scheduled. See [IndexCandidate] and
     * [IndexCandidateOptions] for the thresholds, all of which are the caller's to change.
     */
    public fun indexCandidates(
        options: IndexCandidateOptions = IndexCandidateOptions.DEFAULT,
    ): List<IndexCandidate> = rankIndexCandidates(inferSchema(), options)

    /**
     * The sketch of one segment, or `null` if it is not covered. For tests and for diagnostics.
     *
     * Outside the stable core: a `SegmentSketch` is the `.cat` sidecar's contents, so its shape is
     * the format's. [inferSchema] is the stable reading of the same data.
     */
    @RaboshExperimental
    public fun sketchOf(segmentNumber: Long): SegmentSketch? = lock.withLock { sketches[segmentNumber] }

    override fun toString(): String =
        "SchemaCatalog($directory, ${lock.withLock { sketches.size }} segment(s)" +
            (if (isAttached) ")" else ", detached)")

    // --- SegmentObserver -----------------------------------------------------------------------

    override fun beginSegment(segmentNumber: Long): SegmentObservation? {
        lock.withLock { if (segmentNumber in sketches) return null }
        return Collecting(segmentNumber)
    }

    override fun retain(liveSegments: Set<Long>) {
        lock.withLock {
            this.liveSegments = liveSegments
            // Deletion is deferred until `attach`. This is called during `DocumentStore.open`, which
            // is before any sidecar has been read — deleting on that first call would delete exactly
            // the derived data attaching is about to load.
            if (!attached) return
        }
        prune(liveSegments)
    }

    override fun observerFailed(cause: Throwable) {
        lock.withLock { failures += cause }
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * Accumulates one segment.
     *
     * The sidecar is written *before* the sketch is published in memory, so the two never disagree:
     * a failure to write leaves the segment uncovered in both, which [CatalogCoverage] reports and
     * [rebuild] fixes. Recording it in memory anyway would make this run look complete and the next
     * one silently lose it.
     */
    private inner class Collecting(private val segmentNumber: Long) : SegmentObservation {
        private val builder = SegmentSketchBuilder(options)

        override fun observe(userKey: Key, sequence: Long, document: Variant?) {
            // A tombstone is a fact about a key, not a document, and has no paths to contribute.
            if (document != null) builder.add(document)
        }

        override fun complete(summary: SegmentSummary) {
            val sketch = builder.build()
            SketchFile.write(directory, segmentNumber, sketch)
            lock.withLock { sketches[segmentNumber] = sketch }
            // After the write, not instead of it: a partial model is still a model, and dropping it
            // would trade an understated count for no count at all. The report is the whole of the
            // change — see `TruncatedWalkException` for why this is a fact about a run and not a
            // fact about the sidecar just written.
            builder.truncation()?.let {
                observerFailed(TruncatedWalkException(segmentNumber, it.containers, it.skippedChildren, it.example))
            }
        }

        override fun abandon() {
            // Nothing to undo: nothing was written and nothing was published.
        }
    }

    private fun loadSidecars() {
        for (number in listSidecars()) {
            // `continue`, not a `return` out of the lambda: the skip is what makes a repeated attach
            // cheap, and `rabosh-api` attaches twice around its shared backfill pass on purpose.
            if (lock.withLock { number in sketches }) continue
            val sketch = try {
                SketchFile.read(directory, number)
            } catch (damaged: CatalogException) {
                when (options.damagedSketches) {
                    DamagedSketchPolicy.REPORT -> throw damaged
                    DamagedSketchPolicy.REBUILD -> {
                        observerFailed(damaged)
                        SketchFile.delete(directory, number)
                        null
                    }
                }
            }
            if (sketch != null) lock.withLock { sketches[number] = sketch }
        }
    }

    private fun listSidecars(): List<Long> {
        if (!Files.isDirectory(directory)) return emptyList()
        val numbers = ArrayList<Long>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) sketchSegmentNumber(entry.fileName.toString())?.let { numbers += it }
        }
        numbers.sort()
        return numbers
    }

    /**
     * Drops sketches and deletes sidecars for segments that have left the tree.
     *
     * Called from [retain], which runs on every change to the set of live segments — so it works
     * from the sketches already in hand and does **not** touch the filesystem beyond the files it
     * deletes. A directory listing here would put one on every flush and every compaction; that is
     * [sweep]'s job, and [attach] is the one place it is needed.
     */
    private fun prune(live: Set<Long>) {
        val horizon = deletionHorizon(live)
        val gone = lock.withLock {
            val departed = sketches.keys.filter { it !in live && it < horizon }
            departed.forEach(sketches::remove)
            departed
        }
        for (number in gone) runCatching { SketchFile.delete(directory, number) }
    }

    /**
     * Deletes sidecars for segments that are not live, including ones this catalog never loaded.
     *
     * The residue of a store that ran for a while without a catalog attached: the segments those
     * files describe are gone, so nothing will ever read them, and only a directory listing can find
     * them.
     */
    private fun sweep(live: Set<Long>) {
        val horizon = deletionHorizon(live)
        for (number in listSidecars()) {
            if (number !in live && number < horizon) runCatching { SketchFile.delete(directory, number) }
        }
    }

    /**
     * The number below which "not in the live set" may be read as "gone".
     *
     * Segment numbers are handed out by a counter that only rises, so a sidecar numbered **above**
     * everything in a live set is not a departed segment — it is one this set is simply too old to
     * mention. That happens whenever the set in hand was computed before a flush or a compaction
     * finished, which a long [DocumentStore.backfill] makes ordinary rather than exotic: the scan
     * pins a version, maintenance produces a new segment underneath it, and the sidecar for that
     * segment exists before anything reports it as live.
     *
     * Deleting it would destroy derived data for a segment that is alive and that nothing will
     * rewrite. Keeping it costs a file until the next set arrives naming it, or until [sweep] runs
     * against a set that is genuinely newer. The asymmetry is the same one the tombstone rule makes
     * in the compactor: keeping too long costs space, dropping too early costs data.
     *
     * An **empty** live set says nothing at all about how far the counter has run, so it deletes
     * nothing. That leaves the one genuine leak this rule accepts: a store whose every segment has
     * been compacted away keeps its orphaned sidecars until a segment exists again, or until
     * [rebuild] is called. A file, against the alternative of a rule that guesses.
     */
    private fun deletionHorizon(live: Set<Long>): Long = if (live.isEmpty()) 0 else live.max()
}
