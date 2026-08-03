package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.IndexCandidate
import app.oreshkov.rabosh.catalog.IndexCandidateOptions
import app.oreshkov.rabosh.catalog.InferredSchema
import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentCursor
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreStats
import app.oreshkov.rabosh.core.WriteBatch
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexBuild
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.query.Explain
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryCursor
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A store, its model and its indexes as one object with one lifecycle.
 *
 * ```kotlin
 * Rabosh.open(Path.of("data")).use { db ->
 *     db.put(Key.of("user:1"), """{"name":"ada","team":"analytics"}""")
 *     db.flush()
 *
 *     db.createIndex(IndexDefinition.inverted("$.team"))
 *     db.query(Query.where(path("$.team") eq "analytics")).use { rows ->
 *         while (rows.next()) println(rows.key)
 *     }
 *
 *     println(db.schema().render())
 *     db.indexCandidates().forEach(::println)
 * }
 * ```
 *
 * **What this is for.** Assembling the engine by hand means knowing four things in the right order,
 * three of which no signature discloses: the observer has to be installed in the store's options
 * *before* the store opens, because a flush can begin the moment it does; each layer then has to be
 * attached, and two attachments scan every uncovered segment twice; and the index catalog has to be
 * closed or its mappings stay live, which on Windows means files that can never be deleted.
 * `open` and [close] are those four things, done once, in an order that is written down.
 *
 * **It is faster than the manual wiring in exactly one place**, and that place is [attach]: the
 * layers are attached without backfilling and then fed by a **single** [DocumentStore.backfill]
 * through one `CompositeSegmentObserver`, so a segment neither layer covers is read once rather than
 * once per layer. Everything else here is ergonomics.
 *
 * **What it is not.** Not a server, not a connection pool, not a transaction manager beyond what
 * [WriteBatch] already is, and not a second copy of the layers' API. The surface is deliberately
 * narrow — writes, reads, queries, index management, the model — and everything else is reached
 * through [store], [indexCatalog] and [catalog], which stay public and unwrapped. A facade that
 * re-exported five modules would drift from them, and a query answered here must be the same query
 * answered there: this class holds no planner, no matcher and no definition of what a predicate
 * means. It delegates to [QueryEngine], which is the only one.
 *
 * **Concurrency.** The same contract the store makes: one writing thread, any number of reading
 * threads. The cached planner statistics behind [query] are guarded, so concurrent queries are safe;
 * the state of a query lives in its [QueryCursor], as it does through the engine directly.
 */
public class Rabosh private constructor(
    /** The directory this database owns. */
    public val directory: Path,
    /** The options it was opened with. */
    public val options: RaboshOptions,
    /**
     * The store underneath, unwrapped.
     *
     * The escape hatch, and it is a supported one rather than an admission: everything the facade
     * does not cover — `scanSegments`, `liveSegmentNumbers`, `backfill` with an observer of your own
     * — is here, at full width, unchanged. Do not close it; [close] does, in order.
     */
    public val store: DocumentStore,
    /**
     * The schema catalog, or `null` when [RaboshOptions.schema] is `false`.
     *
     * Attached and maintained by this object. `null` rather than an empty catalog, because a model
     * that was never collected and a model of nothing are different answers and the type should say
     * which one this is.
     */
    public val catalog: SchemaCatalog?,
    /**
     * The index catalog, or `null` when [RaboshOptions.indexes] is `false`.
     *
     * Attached and maintained by this object, and **closed by [close]** — which is the wiring mistake
     * this class exists to make impossible. Reach through it for `read`, `readColumn` and the rest of
     * the index surface; do not close it yourself.
     */
    public val indexCatalog: IndexCatalog?,
    private val observer: SegmentObserver?,
) : AutoCloseable {

    private val lock = ReentrantLock()

    /**
     * The engine, and the segment set the statistics inside it were folded from.
     *
     * [QueryEngine] takes the index catalog as a live object and the schema as a **value**, so the
     * indexes need no refresh — an index created a moment ago is used by the next query — and only
     * the statistics can go stale. They go stale exactly when the set of live segments changes,
     * which is what a flush and a compaction do and nothing else does, so that set is the whole
     * cache key. Folding per query instead would re-read every sketch to answer a question whose
     * answer has not moved.
     */
    private var engine: QueryEngine? = null
    private var statisticsBasis: Set<Long>? = null

    private var closed = false

    // --- writes -------------------------------------------------------------------------------

    /** Commits [document] under [key], replacing any current version. */
    public fun put(key: Key, document: Variant): Unit = store.put(key, document)

    /**
     * Parses [json] and commits it under [key].
     *
     * The overload that makes the first line of a program short. Identical to parsing with
     * [Variant.fromJson] and calling the other [put]; malformed input is rejected with a byte
     * offset, line and column before anything is written.
     */
    public fun put(key: Key, json: String): Unit = store.put(key, Variant.fromJson(json))

    /** Commits a deletion of [key]. Deleting an absent key is legal and writes a tombstone. */
    public fun delete(key: Key): Unit = store.delete(key)

    /**
     * Commits [batch] as one record, atomically and as one view.
     *
     * The unit of atomicity the engine offers, and the one that makes durable writing fast: one
     * commit is one append and one force however many documents it carries.
     */
    public fun write(batch: WriteBatch): Unit = store.write(batch)

    // --- reads --------------------------------------------------------------------------------

    /** The current version of [key], or `null` if it is absent or deleted. */
    public fun get(key: Key): Variant? = store.get(key)

    /** The version of [key] that [snapshot] sees, or `null` if it was absent or deleted then. */
    public fun get(key: Key, snapshot: Snapshot): Variant? = store.get(key, snapshot)

    /**
     * A fixed view of the database as it is now. Close it when done.
     *
     * An open snapshot holds back the versions compaction would otherwise drop, which is what makes
     * it a view that does not move and also what makes it cost disk space.
     */
    public fun snapshot(): Snapshot = store.snapshot()

    /**
     * An ordered walk over the documents in `[from, to]`, both bounds inclusive and both optional.
     *
     * The unfiltered read path. Where [query] answers "which documents match", this answers "what is
     * in this key range", and it needs no index catalog to do it.
     */
    @JvmOverloads
    public fun scan(from: Key? = null, to: Key? = null, snapshot: Snapshot? = null): DocumentCursor =
        store.scan(from, to, snapshot)

    // --- queries ------------------------------------------------------------------------------

    /**
     * Runs [query] and returns its rows, in key order.
     *
     * Delegates to [QueryEngine] with statistics this object keeps current. Without a [snapshot] the
     * cursor takes one and closes it with itself.
     *
     * **An index changes how fast this runs, never what it returns**, and going through the facade
     * does not change that either: the plan, the recheck and the scan of what no index covers are
     * all the engine's, unaltered.
     *
     * **A query narrows to documents; a path expands within one.** `Query.where` answers *which
     * documents* match — it cannot say which `$.items[N]` inside one of them did, because an index
     * maps a value to a document and stops there. The second half is a walk of the one document the
     * caller now has, and `CatalogPath.forEachNodeIn` is that walk:
     *
     * ```kotlin
     * val query = Query.where(path("$.items[*].sku") eq "ABC-123").project(Projection.DOCUMENT)
     * val items = CatalogPath.parse("$.items[*]")
     *
     * db.query(query).use { rows ->
     *     while (rows.next()) items.forEachNodeIn(rows.row.document()) { node ->
     *         if (node.value.field("sku")?.stringValue() == "ABC-123") println(node.toJsonSummaryString())
     *     }
     * }
     * ```
     *
     * Note the projection. `Query.where` projects `Projection.KEY`, which is what makes
     * `documentsRead == 0` reachable at all — and it also makes `Row.document()` throw, which is a
     * surprise worth spending two words to avoid. Ask for `Projection.DOCUMENT` when the second half
     * is going to happen.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    @JvmOverloads
    public fun query(query: Query, snapshot: Snapshot? = null): QueryCursor =
        engine().execute(query, snapshot)

    /**
     * The keys [query] matches, materialised. Convenient where the result is known to be small.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    @JvmOverloads
    public fun keys(query: Query, snapshot: Snapshot? = null): List<Key> = engine().keys(query, snapshot)

    /**
     * How [query] would be answered, and why.
     *
     * The cardinalities it reports are measured — it reads the sources it would use — so it is a
     * statement about the plan rather than an estimate of one.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    @JvmOverloads
    public fun explain(query: Query, snapshot: Snapshot? = null): Explain = engine().explain(query, snapshot)

    // --- indexes ------------------------------------------------------------------------------

    /**
     * Defines an index and builds it over everything already written.
     *
     * No document is rewritten: an index is a set of sidecar files beside the segments that are
     * already on disk. Runs on the calling thread, and documents still in a memtable are not indexed
     * — call [flush] first if complete coverage is wanted immediately. Returns the existing handle if
     * an index over the same path and kind is already defined.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    public fun createIndex(definition: IndexDefinition): IndexHandle =
        requireIndexes().createIndex(store, definition)

    /**
     * Defines an index and builds it on a thread of the database's own, returning at once.
     *
     * ```kotlin
     * val build = db.createIndexInBackground(IndexDefinition.inverted("$.team"))
     * db.query(Query.where(path("$.team") eq "analytics")).use { rows -> … }   // works already
     * build.await()
     * ```
     *
     * The query above is answered correctly while the build is still running: the index covers the
     * segments it has reached and every other segment is scanned, which is what an index built out of
     * per-segment sidecars has always allowed. Calling this again with the same definition resumes a
     * build that was cancelled or that a crash cut short.
     *
     * [close] stops any build in flight, so a database that is closed mid-build is left with an index
     * that is defined and partly covered — a state the engine already lives in.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    public fun createIndexInBackground(definition: IndexDefinition): IndexBuild =
        requireIndexes().createIndexInBackground(store, definition)

    /**
     * Covers whatever the sidecars do not, on that same thread, returning at once.
     *
     * The partner of [RaboshOptions.backfill] being `false`: open without paying for a scan, then
     * converge without holding anybody up.
     *
     * ```kotlin
     * Rabosh.open(directory, RaboshOptions(backfill = false)).use { db ->
     *     val build = db.buildIndexesInBackground()
     *     …
     *     build.await()
     * }
     * ```
     *
     * Only the indexes. The schema catalog has no background pass, so a store opened with
     * `backfill = false` still has an incomplete *model* until [attach] runs — which is why this is a
     * method rather than a third value of that option, where it would have promised something one of
     * the two layers cannot do.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    public fun buildIndexesInBackground(): IndexBuild = requireIndexes().buildIndexesInBackground(store)

    /**
     * Removes an index and deletes its posting files.
     *
     * The base sidecars survive — they belong to the segment rather than to any index. A file still
     * being read is deleted when its last reader closes, so this returns without waiting.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    public fun dropIndex(handle: IndexHandle): Unit = requireIndexes().dropIndex(handle)

    /**
     * The defined indexes, ascending by id.
     *
     * @throws IllegalStateException if [RaboshOptions.indexes] is `false`.
     */
    public fun indexes(): List<IndexHandle> = requireIndexes().indexes()

    // --- the model ----------------------------------------------------------------------------

    /**
     * The model of what is in the database: which paths exist, how often, with what types.
     *
     * Folded fresh on every call rather than served from the cache behind [query]. The cache exists
     * to keep *planning* cheap; a caller asking for the model is asking a question about the data
     * now, and answering it from a fold taken at some earlier flush would be a different answer
     * wearing the same name. [InferredSchema.coverage] reports how much of the store it speaks for.
     *
     * @throws IllegalStateException if [RaboshOptions.schema] is `false`.
     */
    public fun schema(): InferredSchema = requireSchema().inferSchema()

    /**
     * The paths worth an index, best first.
     *
     * A report, not an action: nothing is built and nothing is scheduled. Feed one to
     * [IndexDefinition.of] to act on it.
     *
     * @throws IllegalStateException if [RaboshOptions.schema] is `false`.
     */
    @JvmOverloads
    public fun indexCandidates(
        options: IndexCandidateOptions = IndexCandidateOptions.DEFAULT,
    ): List<IndexCandidate> = requireSchema().indexCandidates(options)

    // --- maintenance and lifecycle ---------------------------------------------------------------

    /**
     * Builds derived data for segments that have none, in **one** pass over them.
     *
     * Run by [open] unless [RaboshOptions.backfill] is `false`, and the call a caller who set it to
     * `false` uses to finish the job later. Idempotent and cheap to repeat: a segment already covered
     * by every layer opens no observation and is not read.
     *
     * The shape is what makes it one pass rather than two. Each layer is attached **without**
     * backfilling — which loads its sidecars and reclaims what it can — then a single
     * [DocumentStore.backfill] feeds the composite, and then each layer is attached again. That last
     * step is not redundant: both layers deliberately re-read the live segment set *after* their own
     * scan, because a compaction landing during a long backfill makes the pre-scan set wrong for
     * reclamation, and the facade preserves that rather than shortcutting it.
     */
    public fun attach() {
        checkOpen()
        attachLayers()
        val fanout = observer
        if (fanout != null) {
            store.backfill(fanout)
            attachLayers()
        }
        invalidateStatistics()
    }

    /**
     * Seals the active memtable and writes every sealed one out as a segment.
     *
     * A real barrier: it returns when the segments are on the platter and the manifest names them,
     * whether or not maintenance runs in the background. It does not compact.
     */
    public fun flush() {
        store.flush()
        invalidateStatistics()
    }

    /** Flushes, then compacts until no level is over its budget. Returns when the tree is in shape. */
    public fun compact() {
        store.compact()
        invalidateStatistics()
    }

    /** Seals the active memtable and starts a new log. */
    public fun rotate() {
        store.rotate()
        invalidateStatistics()
    }

    /**
     * Forces every commit so far to stable storage.
     *
     * A no-op under the default durability, where each commit is already forced. Under
     * `Durability.BUFFERED` this is the barrier at which a bulk load may be reported as complete.
     */
    public fun sync(): Unit = store.sync()

    /** A snapshot of the store's current sizes. */
    public val stats: StoreStats get() = store.stats

    /**
     * Closes the store, then the index catalog. Idempotent.
     *
     * **The order is store first, and it is not arbitrary.** `DocumentStore.close` stops maintenance
     * before anything else, and stopping it *joins* the worker rather than cancelling it — so a flush
     * that was in flight completes and reports its documents to the observer. Closing the index
     * catalog first would make that flush's `beginSegment` return `null`, and the segment it wrote
     * would silently have no sidecar: correct, since a missing sidecar reads as uncovered, but a
     * scan's worth of work thrown away for nothing.
     *
     * Nothing is deleted by either close — an index catalog *releases* its mappings rather than
     * retiring them, because shutting down is not departing — so the rule that a mapped file cannot
     * be deleted on Windows does not decide the order here. What it does decide is that both must
     * release before the directory can be removed, which is why this method exists at all and why
     * the test for it deletes the directory rather than measuring anything.
     *
     * Both steps run even if the first throws; a second failure is attached to the first as a
     * suppressed exception rather than replacing it.
     */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            engine = null
            statisticsBasis = null
        }
        var failure: Throwable? = null
        // Before either close, and outside the ordering argument above. A background index build is
        // *scanning the store*, so leaving it running into `store.close()` would fail it with a
        // "store is closed" it did not deserve — and, worse, leave a thread writing sidecars into a
        // directory the caller may be about to delete. Bounded by one segment; see
        // `IndexCatalog.stopBackgroundBuilds`.
        try {
            indexCatalog?.stopBackgroundBuilds()
        } catch (thrown: Throwable) {
            failure = thrown
        }
        try {
            store.close()
        } catch (thrown: Throwable) {
            if (failure == null) failure = thrown else failure.addSuppressed(thrown)
        }
        try {
            indexCatalog?.close()
        } catch (thrown: Throwable) {
            if (failure == null) failure = thrown else failure.addSuppressed(thrown)
        }
        failure?.let { throw it }
    }

    override fun toString(): String =
        "Rabosh($directory, schema=${catalog != null}, indexes=${indexCatalog != null}" +
            (if (lock.withLock { closed }) ", closed)" else ")")

    // --- internals ----------------------------------------------------------------------------

    /**
     * The engine, with statistics folded from the segments that are live now.
     *
     * The comparison is against the live set rather than a counter because that is the thing the
     * statistics are a function of: a sketch belongs to a segment, so the fold cannot have changed
     * unless the set of segments did.
     */
    private fun engine(): QueryEngine {
        val indexes = requireIndexes()
        val live = store.liveSegmentNumbers
        return lock.withLock {
            val current = engine
            if (current != null && statisticsBasis == live) {
                current
            } else {
                QueryEngine(store, indexes, catalog?.inferSchema()).also {
                    engine = it
                    statisticsBasis = live
                }
            }
        }
    }

    /**
     * Drops the cached statistics after an operation known to have moved the segments.
     *
     * Belt as well as braces: [engine] already re-folds when the live set differs, and this makes
     * the common cases — a flush, a compaction, a backfill — not have to wait to be noticed.
     */
    private fun invalidateStatistics() {
        lock.withLock {
            engine = null
            statisticsBasis = null
        }
    }

    private fun attachLayers() {
        catalog?.attach(store, backfill = false)
        indexCatalog?.attach(store, backfill = false)
    }

    private fun requireIndexes(): IndexCatalog = indexCatalog ?: throw IllegalStateException(
        "this Rabosh was opened with RaboshOptions(indexes = false), so it has no index catalog; " +
            "open it with indexes = true, or use scan() for an unfiltered read",
    )

    private fun requireSchema(): SchemaCatalog = catalog ?: throw IllegalStateException(
        "this Rabosh was opened with RaboshOptions(schema = false), so nothing has been modelled; " +
            "open it with schema = true",
    )

    private fun checkOpen() {
        lock.withLock { if (closed) throw IllegalStateException("the database at $directory is closed") }
    }

    public companion object {
        /**
         * Opens the database in [directory], wiring the layers [options] asks for.
         *
         * The order is the point, and it is the order a caller assembling this by hand has to know:
         * the catalogs are constructed first, composed into the store's observer slot second, and the
         * store is opened third — because a flush can begin the moment it does, and an observer
         * installed afterwards silently misses whatever was written in between. Only then is anything
         * attached.
         *
         * If [RaboshOptions.backfill] is `true`, this scans every segment neither layer covers before
         * returning. That is one pass, not one per layer; see [attach].
         *
         * Anything thrown here closes whatever had already been opened, so a failed `open` leaks
         * neither a directory lock nor a mapping.
         *
         * @throws app.oreshkov.rabosh.core.StoreLockedException if another process holds the directory.
         * @throws java.nio.file.NoSuchFileException if the directory is absent and
         *   `StoreOptions.createIfMissing` is `false`.
         */
        @JvmStatic
        @JvmOverloads
        public fun open(directory: Path, options: RaboshOptions = RaboshOptions.DEFAULT): Rabosh {
            val catalog = if (options.schema) SchemaCatalog(directory, options.catalog) else null
            val indexCatalog = if (options.indexes) IndexCatalog(directory, options.index) else null
            // Ordered so a caller's own observer sees a segment after the layers that maintain the
            // engine's own derived data, and composed even when it is the only one — a single-element
            // composite keeps the failure isolation that a bare observer in the slot would not have.
            val observers = listOfNotNull(catalog, indexCatalog, options.segmentObserver)
            val observer = if (observers.isEmpty()) null else CompositeSegmentObserver(observers)

            val store = DocumentStore.open(directory, options.store.withSegmentObserver(observer))
            val database = Rabosh(directory, options, store, catalog, indexCatalog, observer)
            try {
                if (options.backfill) database.attach() else database.attachLayers()
            } catch (failure: Throwable) {
                try {
                    database.close()
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
            return database
        }
    }
}
