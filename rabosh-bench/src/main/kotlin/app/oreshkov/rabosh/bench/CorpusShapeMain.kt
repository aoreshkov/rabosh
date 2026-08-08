package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Where a corpus keeps things, printed.
 *
 * ```
 * ./gradlew :rabosh-bench:runCorpusShape --args="<path-to.json> [discriminator] [--shapes]"
 * ```
 *
 * The discriminator defaults to `@type`. `--shapes` additionally prints every object path shape,
 * sorted — which is how two corpora get compared, by diffing two runs.
 *
 * **Comparing is a text diff rather than a mode of this program, deliberately.** Two dumps of the
 * same data can sit under different envelopes, and the normalisation that makes them comparable is a
 * fact about those two files. Baking one in would make the tool quietly wrong for the third corpus;
 * printing a sorted list and letting a diff do the work keeps the normalisation visible and in the
 * hands of whoever knows it.
 *
 * Like `ExplodeCostMain` this takes a corpus path, so CI cannot run it. `CorpusShapeTest` is what
 * holds the arithmetic in the ordinary build.
 */
object CorpusShapeMain {

    private const val DEFAULT_DISCRIMINATOR = "@type"
    private const val TOP_SPREAD = 12

    @JvmStatic
    fun main(arguments: Array<String>) {
        val path = arguments.firstOrNull()
            ?: error("usage: runCorpusShape --args=\"<path-to.json> [discriminator] [--shapes]\"")
        val flags = arguments.filter { it.startsWith("--") }.toSet()
        val positional = arguments.filterNot { it.startsWith("--") }
        val discriminator = positional.getOrNull(1) ?: DEFAULT_DISCRIMINATOR
        report(Path.of(path), discriminator, "--shapes" in flags)
    }

    private fun report(file: Path, discriminator: String, printShapes: Boolean) {
        val jsonBytes = Files.readAllBytes(file)
        val parseStart = System.nanoTime()
        val document = Variant.fromJson(jsonBytes)
        val parseMillis = (System.nanoTime() - parseStart) / 1_000_000

        val walkStart = System.nanoTime()
        val shape = CorpusShape.measure(document, discriminator)
        val walkMillis = (System.nanoTime() - walkStart) / 1_000_000

        println("corpus:        $file")
        println("discriminator: $discriminator")
        println()
        println("json bytes:      ${count(jsonBytes.size.toLong())}")
        println("variant bytes:   ${count(document.byteSize)}")
        println("parse:           $parseMillis ms")
        println("walk:            $walkMillis ms")
        println()
        println("objects:                    ${count(shape.objects)}")
        println("arrays:                     ${count(shape.arrays)}")
        println("max container path depth:   ${shape.maxPathDepth}")
        println("distinct object shapes:     ${count(shape.objectPathShapes.size.toLong())}")
        println("distinct leaf shapes:       ${count(shape.leafPathShapes.size.toLong())}   " +
            "(this is what CatalogOptions.maxPaths budgets)")
        println()
        println("discriminated elements:     ${count(shape.discriminatorHits)}")
        println("distinct types:             ${count(shape.distinctTypes.toLong())}")
        println("(type, shape) pairs:        ${count(shape.typePathPairs.toLong())}")
        println()

        if (shape.distinctTypes == 0) {
            println("no element carries '$discriminator'; nothing more to report")
            if (printShapes) printObjectShapes(shape)
            return
        }

        println("shapes per type:")
        for ((shapes, types) in shape.pathsPerType()) {
            println("  ${shapes.toString().padStart(3)} shape(s): ${count(types.toLong()).padStart(5)} types")
        }
        println()

        val single = shape.elementsOfSingleShapeTypes()
        val total = shape.discriminatorHits
        val percent = if (total == 0L) 0.0 else 100.0 * single / total
        println("elements whose type occupies exactly ONE shape: " +
            "${count(single)} of ${count(total)} (${format(percent)}%)")
        println()

        println("most scattered types:")
        for (entry in shape.spread().take(TOP_SPREAD)) {
            println(
                "  ${entry.type.padEnd(46).take(46)} ${entry.pathShapes.toString().padStart(3)} shapes " +
                    "${count(entry.elements).padStart(8)} elements",
            )
        }

        if (printShapes) printObjectShapes(shape)
    }

    private fun printObjectShapes(shape: CorpusShape) {
        println()
        println("object path shapes (sorted; diff two runs to compare corpora):")
        for ((path, objects) in shape.objectPathShapes) println("  ${count(objects).padStart(9)}  $path")
    }

    private fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
