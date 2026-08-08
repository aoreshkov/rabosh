package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.variant.Variant
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

private val scratchCounter = AtomicLong(0)

/** A fresh subdirectory of [root]; each call returns a new one. */
internal fun scratch(root: Path, prefix: String = "query"): Path =
    root.resolve("$prefix-${scratchCounter.incrementAndGet()}")

/**
 * Store options every query test uses.
 *
 * `backgroundMaintenance = false` for the reason `.claude/rules/testing.md` gives: a test that
 * reasons about which segments an index covers cannot have a background thread rewriting them
 * underneath it. The suite that is specifically *about* racing maintenance turns it back on and says
 * so.
 */
internal fun queryStoreOptions(
    observer: SegmentObserver?,
    backgroundMaintenance: Boolean = false,
): StoreOptions = StoreOptions(
    durability = Durability.BUFFERED,
    segmentMaxBytes = 8 * 1024,
    blockSize = 512,
    backgroundMaintenance = backgroundMaintenance,
    segmentObserver = observer,
)

internal fun keyFor(index: Int): Key = Key.of("key:%06d".format(index))

internal fun jsonDocument(json: String): Variant = Variant.fromJson(json)

internal fun jsonDocument(value: JsonValue): Variant = Variant.fromJson(value.toJsonString())

/** Writes [documents] under sequential keys and flushes, so everything is in a segment. */
internal fun DocumentStore.load(documents: List<Variant>, from: Int = 0) {
    documents.forEachIndexed { index, document -> put(keyFor(from + index), document) }
    flush()
}

// --- the two oracles ------------------------------------------------------------------------------

/**
 * The keys a predicate matches, by scanning every document and matching it with the engine's own
 * evaluator.
 *
 * This is the **plan** oracle: it uses `DocumentMatcher`, so what it tests is that a plan finds what
 * the predicate means — not what the predicate means. Comparing a plan against it isolates planning
 * from semantics, which is the difference that matters when one of the two is wrong.
 */
internal fun scanKeys(store: DocumentStore, snapshot: Snapshot, predicate: Predicate): List<Key> {
    val matcher = DocumentMatcher(predicate.normalise().lower(IndexOptions.DEFAULT), IndexOptions.DEFAULT)
    val keys = sortedSetOf<Key>()
    store.scan(snapshot = snapshot).use { cursor ->
        while (cursor.next()) if (matcher.matches(cursor.document)) keys.add(cursor.key)
    }
    return keys.toList()
}

/**
 * The keys a predicate matches, worked out from the testkit's JSON model.
 *
 * A **second implementation**, written against a different representation from the one the engine
 * walks and with the type-bracketing rule spelled out by hand, so that agreement between the two is
 * evidence rather than a tautology. `expectedTerms` in the index suite is the same instrument for the
 * same reason.
 *
 * It is a test oracle and not a second definition of what the engine does: where the two disagree,
 * this one is a claim about what the engine *should* do, to be argued about rather than deferred to.
 */
internal fun referenceKeys(corpus: Map<Key, JsonValue>, predicate: Predicate): List<Key> =
    corpus.filter { (_, document) -> holds(predicate, document) }.keys.sorted()

private fun holds(predicate: Predicate, document: JsonValue): Boolean = when (predicate) {
    Predicate.True -> true
    Predicate.False -> false
    is Predicate.And -> predicate.operands.all { holds(it, document) }
    is Predicate.Or -> predicate.operands.any { holds(it, document) }
    is Predicate.Not -> !holds(predicate.operand, document)
    // Written out by hand, as everything in this oracle is: *some* element satisfies the whole
    // operand, with the operand read against that element as if it were the document. The negation
    // is the caller's `Not` around this node, so "no element satisfies it" falls out rather than
    // being a second rule.
    is Predicate.ElemMatch -> elementsAt(document, predicate.path).any { holds(predicate.operand, it) }

    is Predicate.Exists -> valuesAt(document, predicate.path).isNotEmpty()
    is Predicate.IsNull -> valuesAt(document, predicate.path).any { it == JsonValue.Null }
    is Predicate.Compare -> valuesAt(document, predicate.path).any {
        compares(it, predicate.operator, predicate.value)
    }

    is Predicate.AnyOf -> valuesAt(document, predicate.path).any { value ->
        predicate.values.any { compares(value, Comparison.EQ, it) }
    }
}

/**
 * Type bracketing, written out.
 *
 * A numeric comparison sees numbers only, a text comparison strings only, and a value of any other
 * kind is *not a match and not an error*. Equality against `null` is the JSON null and nothing else.
 */
private fun compares(value: JsonValue, operator: Comparison, literal: QueryValue): Boolean {
    fun order(comparison: Int): Boolean = when (operator) {
        Comparison.EQ -> comparison == 0
        Comparison.LT -> comparison < 0
        Comparison.LE -> comparison <= 0
        Comparison.GT -> comparison > 0
        Comparison.GE -> comparison >= 0
    }
    return when (literal) {
        is QueryValue.Numeric ->
            value is JsonValue.Num && order(BigDecimal(value.literal).compareTo(literal.value))

        is QueryValue.Text -> value is JsonValue.Str && order(compareUtf8(value.value, literal.value))
        is QueryValue.Bool -> operator == Comparison.EQ && value is JsonValue.Bool && value.value == literal.value
        QueryValue.Null -> operator == Comparison.EQ && value == JsonValue.Null
    }
}

/** UTF-8 byte order, which is what the engine compares strings in and is not Kotlin's above U+FFFF. */
private fun compareUtf8(left: String, right: String): Int =
    java.util.Arrays.compareUnsigned(left.encodeToByteArray(), right.encodeToByteArray())

/**
 * Every scalar at [path], the walk written independently of `TermExtractor`.
 *
 * A path with `[*]` reports one value per element; a duplicate field name resolves last-wins, which
 * is what the encoder does; a container at the end of a path contributes nothing, because a path
 * names scalars.
 */
internal fun valuesAt(document: JsonValue, path: CatalogPath): List<JsonValue> {
    val found = ArrayList<JsonValue>()

    fun walk(value: JsonValue, depth: Int) {
        if (depth == path.steps.size) {
            when (value) {
                is JsonValue.Obj, is JsonValue.Arr -> Unit
                else -> found.add(value)
            }
            return
        }
        when (val step = path.steps[depth]) {
            is CatalogStep.Field -> {
                if (value !is JsonValue.Obj) return
                val resolved = LinkedHashMap<String, JsonValue>()
                for ((name, fieldValue) in value.fields) resolved[name] = fieldValue
                resolved[step.name]?.let { walk(it, depth + 1) }
            }

            CatalogStep.AnyElement -> {
                if (value !is JsonValue.Arr) return
                for (element in value.elements) walk(element, depth + 1)
            }
        }
    }

    walk(document, 0)
    return found
}

// --- assertions -----------------------------------------------------------------------------------

/**
 * Asserts that the plan agrees with a full scan, and hands back what it cost.
 *
 * Every assertion about *work* in this suite is made on the statistics this returns, in the same test
 * as this equality — `documentsRead == 0` passes trivially for a query that returned nothing.
 */
internal fun assertMatchesScan(
    engine: QueryEngine,
    store: DocumentStore,
    snapshot: Snapshot,
    query: Query,
    note: String,
): QueryStats {
    val expected = scanKeys(store, snapshot, query.predicate)
    val cursor = engine.execute(query.project(Projection.KEY), snapshot)
    val actual = ArrayList<Key>()
    val stats = cursor.use {
        while (it.next()) actual.add(it.key)
        it.stats
    }
    assertEquals(expected, actual, "$note: the plan changed the answer")
    assertEquals(actual.sorted(), actual, "$note: rows must come back in key order")
    return stats
}

/**
 * The values a catalog path stands for, **containers included** — what an `elemMatch` walks.
 *
 * `valuesAt` deliberately drops containers, because a leaf compares scalars; this one deliberately
 * keeps whatever the path arrives at, because an element is ordinarily an object. Two functions
 * rather than a flag, so neither can be used for the other's question by accident.
 */
internal fun elementsAt(document: JsonValue, path: CatalogPath): List<JsonValue> {
    val found = ArrayList<JsonValue>()

    fun walk(value: JsonValue, depth: Int) {
        if (depth == path.steps.size) {
            found.add(value)
            return
        }
        when (val step = path.steps[depth]) {
            is CatalogStep.Field -> {
                if (value !is JsonValue.Obj) return
                val resolved = LinkedHashMap<String, JsonValue>()
                for ((name, fieldValue) in value.fields) resolved[name] = fieldValue
                resolved[step.name]?.let { walk(it, depth + 1) }
            }

            CatalogStep.AnyElement -> {
                if (value !is JsonValue.Arr) return
                for (element in value.elements) walk(element, depth + 1)
            }
        }
    }

    walk(document, 0)
    return found
}
