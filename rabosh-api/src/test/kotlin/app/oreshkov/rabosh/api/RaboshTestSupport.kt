package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObservation
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.SegmentSummary
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

private val scratchCounter = AtomicLong(0)

/** A fresh subdirectory of [root]; each call returns a new one. */
internal fun scratch(root: Path, prefix: String = "api"): Path =
    root.resolve("$prefix-${scratchCounter.incrementAndGet()}")

/**
 * Store options every facade test uses.
 *
 * `backgroundMaintenance = false` for the reason `CLAUDE.md` gives: a test that reasons about which
 * segments exist cannot have a background thread rewriting them underneath it. `RaboshCloseTest` is
 * the one suite that turns it back on, because it is specifically about racing a flush.
 *
 * Small segments and blocks so a few hundred documents produce several of them, which is what makes
 * "one scan, not two" a statement about more than one segment.
 */
internal fun apiStoreOptions(backgroundMaintenance: Boolean = false): StoreOptions = StoreOptions(
    durability = Durability.BUFFERED,
    segmentMaxBytes = 8 * 1024,
    blockSize = 512,
    backgroundMaintenance = backgroundMaintenance,
)

internal fun keyFor(index: Int): Key = Key.of("key:%06d".format(index))

/**
 * The corpus, generated from an index so that what matches a predicate is arithmetic.
 *
 * That is deliberate and it is the third oracle this module has. `rabosh-query` compares a plan
 * against its own evaluator over a full scan and against a hand-written model; neither is reachable
 * from here, and re-implementing one would give this module a private definition of what a predicate
 * means — the exact thing the facade must not have. Instead the expected answer to every shape below
 * is a predicate on `index`, which cannot agree with the engine by construction.
 */
internal fun documentJson(index: Int): String = buildString(160) {
    append("""{"team":"team-${index % 7}",""")
    append(""""score":${index % 50},""")
    append(""""price":${index % 90}.${"%02d".format(index % 100)},""")
    append(""""live":${index % 3 == 0},""")
    append(""""tags":["t${index % 5}","t${index % 3}"]""")
    // Absent for a third of the corpus and null for a slice of the rest: the two states EXISTS and
    // IS NULL have to keep apart.
    if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"n$index\""}""")
    append("}")
}

internal fun documentOf(index: Int): Variant = Variant.fromJson(documentJson(index))

/** Writes `[from, from + count)` and flushes, so everything is in a segment. */
internal fun Rabosh.load(from: Int, count: Int) {
    for (index in from until from + count) put(keyFor(index), documentOf(index))
    flush()
}

// --- what is on disk ------------------------------------------------------------------------------

internal fun names(directory: Path): List<String> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.newDirectoryStream(directory).use { entries ->
        entries.map { it.fileName.toString() }.sorted()
    }
}

internal fun namesEndingIn(directory: Path, suffix: String): List<String> =
    names(directory).filter { it.endsWith(suffix) }

// --- the counting observer ------------------------------------------------------------------------

/**
 * An observer that counts what backfilling costs, optionally wrapping another.
 *
 * The instrument behind "one pass, not two". It counts calls rather than measuring time, because the
 * claim is about how many times the documents are walked and a timing assertion in its place would be
 * a test of the machine.
 *
 * The delegate matters. Wrapping a real catalog makes the count *that catalog's actual work* — a
 * segment it already covers returns `null` from `beginSegment` and is not read, so the number is what
 * happened rather than what a spy forced to happen. Standing alone (no delegate) it always opens an
 * observation, which is what a caller's own observer composed into the slot does.
 */
internal class CountingObserver(private val delegate: SegmentObserver? = null) : SegmentObserver {
    val segmentsBegun: AtomicInteger = AtomicInteger()
    val documentsObserved: AtomicInteger = AtomicInteger()

    override fun beginSegment(segmentNumber: Long): SegmentObservation? {
        val inner = if (delegate == null) null else delegate.beginSegment(segmentNumber) ?: return null
        segmentsBegun.incrementAndGet()
        return object : SegmentObservation {
            override fun observe(userKey: Key, sequence: Long, document: Variant?) {
                documentsObserved.incrementAndGet()
                inner?.observe(userKey, sequence, document)
            }

            override fun complete(summary: SegmentSummary) {
                inner?.complete(summary)
            }

            override fun abandon() {
                inner?.abandon()
            }
        }
    }

    override fun retain(liveSegments: Set<Long>) {
        delegate?.retain(liveSegments)
    }

    override fun observerFailed(cause: Throwable) {
        delegate?.observerFailed(cause)
    }
}
