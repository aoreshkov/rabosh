package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private val scratchCounter = AtomicLong(0)

/** A fresh subdirectory of [root]; each call returns a new one. */
internal fun scratch(root: Path, prefix: String = "index"): Path =
    root.resolve("$prefix-${scratchCounter.incrementAndGet()}")

/**
 * Store options every index test uses.
 *
 * `backgroundMaintenance = false` for the reason `.claude/rules/testing.md` gives: a test that
 * reasons about which segments exist cannot have a background thread rewriting them underneath it.
 * The two tests that are specifically *about* racing maintenance turn it back on and say so.
 *
 * Small segments and blocks so a few hundred documents produce a tree with more than one level, which
 * is what makes compaction, ordinal renumbering and sidecar replacement reachable in a unit test.
 */
internal fun indexStoreOptions(
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

// --- what is on disk -----------------------------------------------------------------------------

/** Segment file numbers in [directory]. */
internal fun segmentNumbers(directory: Path): Set<Long> = names(directory)
    .mapNotNull { if (it.endsWith(".seg")) it.removeSuffix(".seg").toLongOrNull() else null }
    .toSet()

/** Base sidecars in [directory], by segment number. */
internal fun baseSidecarNumbers(directory: Path): Set<Long> =
    names(directory).filterNot { it.endsWith(".tmp") }.mapNotNull(::baseSegmentNumber).toSet()

/** Posting files in [directory], as `(segment, index)` pairs. */
internal fun postingFiles(directory: Path): Set<Pair<Long, Int>> =
    names(directory).filterNot { it.endsWith(".tmp") }.mapNotNull(::postingNumbers).toSet()

/** Column files in [directory], as `(segment, index)` pairs. */
internal fun columnFiles(directory: Path): Set<Pair<Long, Int>> =
    names(directory).filterNot { it.endsWith(".tmp") }.mapNotNull(::columnNumbers).toSet()

/** Every index sidecar name in [directory], sorted. Both kinds, so nothing leaks past a lifecycle test. */
internal fun sidecarNames(directory: Path): List<String> = names(directory)
    .filter { baseSegmentNumber(it) != null || postingNumbers(it) != null || columnNumbers(it) != null }
    .sorted()

/** The bytes of every index sidecar in [directory], by name. What byte-identity is compared over. */
internal fun sidecarBytes(directory: Path): Map<String, ByteArray> =
    sidecarNames(directory).associateWith { Files.readAllBytes(directory.resolve(it)) }

private fun names(directory: Path): List<String> {
    if (!Files.isDirectory(directory)) return emptyList()
    val out = ArrayList<String>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) out.add(entry.fileName.toString())
    }
    return out
}

// --- an independent evaluator --------------------------------------------------------------------

/**
 * The terms a document carries at [path], worked out from the testkit's JSON model.
 *
 * A **second implementation**, written against a different representation from the one
 * [TermExtractor] walks, so that agreement between the two is evidence rather than a tautology. The
 * catalog suite uses the same instrument for the same reason.
 *
 * Returns `null` when the document has no value at the path at all, which is what distinguishes
 * "absent" from "present but carrying nothing indexable" — the distinction a JSON null forces.
 */
internal fun expectedTerms(document: JsonValue, path: CatalogPath): Set<IndexTerm>? {
    var present = false
    val terms = LinkedHashSet<IndexTerm>()

    fun walk(value: JsonValue, depth: Int) {
        if (depth == path.steps.size) {
            when (value) {
                is JsonValue.Obj, is JsonValue.Arr -> Unit
                else -> {
                    present = true
                    termOf(value)?.let(terms::add)
                }
            }
            return
        }
        when (val step = path.steps[depth]) {
            is CatalogStep.Field -> {
                if (value !is JsonValue.Obj) return
                // Last wins, matching what the encoder does with a duplicate field name.
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
    return if (present) terms else null
}

private fun termOf(value: JsonValue): IndexTerm? = when (value) {
    is JsonValue.Str -> IndexTerm.ofString(value.value)
    is JsonValue.Bool -> IndexTerm.ofBoolean(value.value)
    is JsonValue.Num -> IndexTerm.of(Variant.fromJson(value.literal))
    JsonValue.Null -> null
    else -> null
}
