package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * What an engine-side explode would cost on a real corpus, printed.
 *
 * Deliberately not a JMH benchmark, for the reason `AmplificationMain` is not one: this is a ratio
 * over one corpus, and a harness built to repeat an operation a million times and divide has nowhere
 * to put it. One run, measured, printed.
 *
 * ```
 * ./gradlew :rabosh-bench:runExplodeCost --args="<path-to.json> [discriminator]"
 * ```
 *
 * The discriminator defaults to `@type`, which is protobuf-JSON's. Any field name works; what makes
 * one a *discriminator* is that it marks the objects worth being documents, and that is a property
 * of the corpus rather than of this program.
 *
 * The corpus is a path rather than something generated, which makes this the one diagnostic here
 * that cannot run in CI — the question it answers is about somebody's actual data, and a generated
 * fixture would answer it about the generator. `ExplodeCostTest` is what keeps the arithmetic honest
 * in the build; this is what points it at a file.
 */
object ExplodeCostMain {

    private const val DEFAULT_DISCRIMINATOR = "@type"

    @JvmStatic
    fun main(arguments: Array<String>) {
        val path = arguments.firstOrNull()
            ?: error("usage: runExplodeCost --args=\"<path-to.json> [discriminator]\"")
        val discriminator = arguments.getOrNull(1) ?: DEFAULT_DISCRIMINATOR
        report(Path.of(path), discriminator)
    }

    private fun report(file: Path, discriminator: String) {
        val jsonBytes = Files.readAllBytes(file)
        val parseStart = System.nanoTime()
        val document = Variant.fromJson(jsonBytes)
        val parseMillis = (System.nanoTime() - parseStart) / 1_000_000

        val walkStart = System.nanoTime()
        val cost = ExplodeCost.measure(document, discriminator)
        val walkMillis = (System.nanoTime() - walkStart) / 1_000_000

        println("corpus:        $file")
        println("discriminator: $discriminator")
        println()
        println("json bytes:      ${count(jsonBytes.size.toLong())}")
        println("variant bytes:   ${count(cost.originalBytes)}   (value bytes; metadata is per-SSTable)")
        println("parse:           $parseMillis ms")
        println("walk:            $walkMillis ms")
        println()
        println("typed elements:  ${count(cost.typedElements)}")
        println("  of which nested under another: ${count(cost.nestedElements)}")
        println("max typed depth: ${cost.maxTypedDepth}")
        println()
        println("stored bytes, by model:")
        println("  original (one document) ${count(cost.originalBytes)}   1.000 x")
        println("  elided                  ${count(cost.elidedModelBytes)}   ${factor(cost.elidedFactor)} x")
        println("  whole                   ${count(cost.wholeModelBytes)}   ${factor(cost.wholeFactor)} x")
        println()
        // Reference overhead is priced here rather than folded into the elided model, because a key
        // size is the caller's choice and a number baked in against one guess would read as measured.
        for (keyBytes in intArrayOf(16, 32, 64)) {
            val overhead = cost.nestedElements * keyBytes
            val total = cost.elidedModelBytes + overhead
            val ratio = if (cost.originalBytes == 0L) 0.0 else total.toDouble() / cost.originalBytes
            println("  elided + ${keyBytes}B refs        ${count(total)}   ${factor(ratio)} x")
        }
        println()
        println("whole-model bytes by typed depth:")
        println("  depth  elements        bytes")
        for ((depth, elements) in cost.elementsByTypedDepth) {
            val bytes = cost.bytesByTypedDepth[depth] ?: 0L
            println("  ${depth.toString().padStart(5)}  ${count(elements).padStart(8)}  ${count(bytes).padStart(13)}")
        }
    }

    private fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    private fun factor(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
