package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

private val scratchCounter = AtomicLong(0)

/** A fresh subdirectory of [root]; each call returns a new one. */
internal fun scratch(root: Path, prefix: String = "catalog"): Path =
    root.resolve("$prefix-${scratchCounter.incrementAndGet()}")

/**
 * Store options every catalog test uses.
 *
 * `backgroundMaintenance = false` for the reason `.claude/rules/testing.md` gives: a test that
 * reasons about which segments exist cannot have a background thread rewriting them underneath it.
 * Small segments and blocks so that a few hundred documents produce a tree with more than one level
 * in it.
 */
internal fun catalogStoreOptions(catalog: SchemaCatalog?): StoreOptions = StoreOptions(
    durability = Durability.BUFFERED,
    segmentMaxBytes = 8 * 1024,
    blockSize = 512,
    backgroundMaintenance = false,
    segmentObserver = catalog,
)

internal fun keyFor(index: Int): Key = Key.of("key:%06d".format(index))

/** The `.cat` sidecars in [directory], by segment number. */
internal fun sidecarNumbers(directory: Path): Set<Long> {
    val numbers = HashSet<Long>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) sketchSegmentNumber(entry.fileName.toString())?.let { numbers += it }
    }
    return numbers
}

/** Segment file numbers in [directory]. */
internal fun segmentNumbers(directory: Path): Set<Long> {
    val numbers = HashSet<Long>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) {
            val name = entry.fileName.toString()
            if (name.endsWith(".seg")) name.removeSuffix(".seg").toLongOrNull()?.let { numbers += it }
        }
    }
    return numbers
}

/** Writes [documents] under sequential keys and flushes, so everything is in a segment. */
internal fun DocumentStore.load(documents: List<Variant>, from: Int = 0) {
    documents.forEachIndexed { index, document -> put(keyFor(from + index), document) }
    flush()
}

internal fun jsonDocument(json: String): Variant = Variant.fromJson(json)

internal fun jsonDocument(value: JsonValue): Variant = Variant.fromJson(value.toJsonString())

/**
 * The paths a document contains, worked out independently of [SegmentSketchBuilder].
 *
 * A second implementation, written against the testkit's own JSON model rather than against the
 * Variant encoding, so that agreement between the two is evidence rather than a tautology. It is
 * the same instrument the codec phase used: differential testing against a reference.
 *
 * Duplicate field names resolve last-wins, because that is what the encoder does — the Variant
 * specification forbids duplicates outright, so the encoder has to choose, and it chose the rule
 * every JSON reader uses.
 */
internal fun expectedObservations(document: JsonValue): List<Pair<CatalogPath, JsonValue>> {
    val out = ArrayList<Pair<CatalogPath, JsonValue>>()

    fun walk(steps: List<CatalogStep>, value: JsonValue) {
        out += CatalogPath(steps) to value
        when (value) {
            is JsonValue.Obj -> {
                val resolved = LinkedHashMap<String, JsonValue>()
                for ((name, fieldValue) in value.fields) resolved[name] = fieldValue
                for ((name, fieldValue) in resolved) walk(steps + CatalogStep.Field(name), fieldValue)
            }

            is JsonValue.Arr -> {
                if (value.elements.isEmpty()) return
                val nested = steps + CatalogStep.AnyElement
                for (element in value.elements) walk(nested, element)
            }

            else -> Unit
        }
    }

    walk(emptyList(), document)
    return out
}

/** Observation counts per path across a whole corpus, from [expectedObservations]. */
internal fun expectedCounts(documents: List<JsonValue>): Map<CatalogPath, Long> {
    val counts = HashMap<CatalogPath, Long>()
    for (document in documents) {
        for ((path, _) in expectedObservations(document)) {
            counts[path] = (counts[path] ?: 0) + 1
        }
    }
    return counts
}
