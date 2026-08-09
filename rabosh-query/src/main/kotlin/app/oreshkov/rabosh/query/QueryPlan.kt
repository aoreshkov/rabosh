package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.InferredSchema
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.core.DocumentStore

/**
 * How one query will be answered at one snapshot.
 *
 * **One plan shape, not two.** A full scan is the degenerate case — no expression, nothing evaluated,
 * every segment scanned — rather than a separate branch, which is why the executor has one path and
 * why the correctness argument is one statement instead of two.
 *
 * The partition is over [universe], the segments the *snapshot* pinned, and never over the store's
 * live set. The two diverge under a compaction, and taking the partition over the live set would
 * scan segments this view cannot see while skipping the ones it can — silently missing documents.
 */
internal class QueryPlan(
    /** The segments the snapshot pinned. Everything is a partition of this. */
    val universe: Set<Long>,
    /** `null` when no leaf found a source, which is the full-scan plan. */
    val expression: OrdinalExpression?,
    /** Segments answered from sidecars, with the restriction each of them supports. */
    val evaluated: Map<Long, Restriction>,
    /** `universe` less [evaluated]: read by [DocumentStore.scanSegments]. */
    val scanned: Set<Long>,
    /** Whether the snapshot can see documents that are in no segment. */
    val hasUnflushedDocuments: Boolean,
    val matcher: DocumentMatcher,
    /** The readers to close when the query is done. */
    val readers: List<LeafReader>,
    /**
     * Columns covering **every** projected field, or `null` when even one has none.
     *
     * Only consulted where the plan has already decided a key outright and holds no document: that is
     * the one place a projection was paying for a read the filter had avoided.
     */
    val projection: ProjectionColumns?,
    /**
     * A uniqueness test valid over the whole universe, or `null` when no reader covers it.
     *
     * The precondition for skipping a recheck: it has to be able to say that no *other* segment of
     * this snapshot holds the key. A reader whose usable segments do not include all of [universe]
     * cannot say that, so it does not get asked.
     */
    val uniqueness: ((Key, Long) -> Boolean)?,
) {
    override fun toString(): String =
        "QueryPlan(universe=${universe.size}, evaluated=${evaluated.size}, scanned=${scanned.size}, " +
            "${expression?.render() ?: "full scan"})"
}

/**
 * Turns a predicate into a plan.
 *
 * Order matters here. The predicate is normalised and lowered first, so index selection sees leaves
 * rather than operators; readers are opened next, because opening one pins sidecars and that cost is
 * paid before any bitmap exists; and only then is the universe partitioned, because which segments
 * can be evaluated depends on what the readers turned out to cover.
 */
internal object QueryPlanner {

    fun plan(
        store: DocumentStore,
        indexes: IndexCatalog,
        schema: InferredSchema?,
        query: Query,
        snapshot: Snapshot,
        options: IndexOptions,
    ): QueryPlan {
        val normal = query.predicate.normalise().lower(options)
        val matcher = DocumentMatcher(normal, options)
        val universe = snapshot.segmentNumbers
        val readers = ArrayList<LeafReader>()
        val available = indexes.indexes()

        // One cache for both roles. A column opened to answer a predicate and the same column opened
        // to fill a row in are the same sidecars, and pinning them twice would be two mappings to
        // release rather than one.
        val opened = HashMap<Int, LeafReader>()
        fun open(handle: IndexHandle): LeafReader = opened.getOrPut(handle.id) {
            val reader = when (handle.kind) {
                // A composite index's sidecar is a posting file, so it is read by the same reader.
                // The difference between the two is entirely in how a term is spelled, which happened
                // before this point.
                app.oreshkov.rabosh.catalog.IndexKind.INVERTED,
                app.oreshkov.rabosh.catalog.IndexKind.COMPOSITE_TERM,
                -> LeafReader.Inverted(indexes.read(store, handle, snapshot))

                app.oreshkov.rabosh.catalog.IndexKind.SHREDDED_COLUMN ->
                    LeafReader.Column(indexes.readColumn(store, handle, snapshot))
            }
            readers.add(reader)
            reader
        }

        val expression: OrdinalExpression?
        val projection: ProjectionColumns?
        try {
            expression = if (available.isEmpty()) null else build(schema, normal, available, options, ::open)
            projection = ProjectionColumns.bind(query.projection, available, ::open)
        } catch (failure: Throwable) {
            readers.forEach { runCatching { it.close() } }
            throw failure
        }

        val evaluated = LinkedHashMap<Long, Restriction>()
        if (expression != null) {
            for (segment in universe.sorted()) {
                expression.restrictTo(segment)?.let { evaluated[segment] = it }
            }
        }

        return QueryPlan(
            universe = universe,
            expression = expression,
            evaluated = evaluated,
            scanned = universe - evaluated.keys,
            hasUnflushedDocuments = snapshot.hasUnflushedDocuments,
            matcher = matcher,
            readers = readers,
            projection = projection,
            uniqueness = readers.firstOrNull { it.usableSegments.containsAll(universe) }?.let { reader ->
                { key: Key, segment: Long -> reader.isUniqueKey(key, segment) }
            },
        )
    }

    /**
     * Builds the ordinal expression, opening one reader per index used.
     *
     * A leaf with no source is a **residual**: it disappears from the expression and is answered by
     * the recheck, exactly as every leaf was before there were indexes. In a conjunction that is a
     * wider candidate set; in a disjunction it makes the whole node unevaluable, because a branch
     * nobody looks for is a missing answer.
     */
    private fun build(
        schema: InferredSchema?,
        normal: Normal,
        available: List<IndexHandle>,
        options: IndexOptions,
        open: (IndexHandle) -> LeafReader,
    ): OrdinalExpression? {
        fun visit(node: Normal): OrdinalExpression? = when (node) {
            // A constant carries no ordinals. `True` in a conjunction is a dropped conjunct, which is
            // legal; anywhere else it makes the node unevaluable and the recheck decides.
            Normal.AlwaysTrue, Normal.AlwaysFalse -> null

            is Normal.Leaf -> chooseIndex(node, available)?.let { handle ->
                OrdinalExpression.Source(LeafSource.of(node, open(handle)))
            }

            // An `elemMatch` has two chances, in this order and for this reason.
            //
            // A composite index spells the declared tuple, and where the tuple accounts for the whole
            // operand it *decides* the node — the fast path, and the only one that reaches zero
            // documents read. Failing that, the node is decomposed into ordinary leaves over
            // concatenated paths, so the indexes a caller already has narrow it before the element
            // walk runs. The second is what makes a range inside an element, a subset of a tuple's
            // fields and a disjunction cost an index lookup instead of ~400 ns per element; it is
            // worth strictly less than the first and is strictly better than nothing.
            is Normal.Element -> chooseComposite(node, available, options)?.let { choice ->
                val tuple = OrdinalExpression.Source(LeafSource.composite(node, choice.terms, open(choice.handle)))
                if (choice.exact) {
                    tuple
                } else {
                    // The tuple fixed every declared field and the conjunction asked for more, so
                    // this narrows *correlatedly* without deciding. The decomposition is intersected
                    // on top rather than passed over: a caller who also has ordinary indexes was
                    // getting those before the tuple could be used here at all, and taking the tuple
                    // instead of them would trade one narrowing for another with nothing to say which
                    // is better. Both is strictly better than either.
                    val narrowed = decomposeElement(node)?.let { (rewritten, _) -> visit(rewritten) }
                    OrdinalExpression.All(listOfNotNull(tuple, narrowed), complete = false)
                }
            } ?: decomposeElement(node)?.let { (rewritten, exact) ->
                visit(rewritten)?.let { narrowed ->
                    // An inexact decomposition narrows without deciding, and saying so is the whole
                    // of its soundness: two conjuncts satisfied by two different elements survive it
                    // and fail the recheck. `complete = false` is the mechanism a dropped conjunct
                    // already uses, which is why this needs no new one.
                    if (exact) narrowed else OrdinalExpression.All(listOf(narrowed), complete = false)
                }
            }

            is Normal.Conjunction -> {
                // Sidecars are pinned by opening a reader, so a conjunct a sibling has already made
                // redundant is dropped *before* it is visited rather than after.
                val chosen = Selectivity.chooseConjuncts(node.operands, schema)
                val kept = chosen.mapNotNull(::visit)
                if (kept.isEmpty()) {
                    null
                } else {
                    // Any conjunct without a source is a residual, and a plan holding one may narrow
                    // nothing on its own account: the recheck is what evaluates it.
                    OrdinalExpression.All(kept, complete = kept.size == node.operands.size)
                }
            }

            is Normal.Disjunction -> {
                val kept = node.operands.map(::visit)
                if (kept.any { it == null }) null else OrdinalExpression.Any(kept.filterNotNull())
            }
        }

        return visit(normal)
    }
}
