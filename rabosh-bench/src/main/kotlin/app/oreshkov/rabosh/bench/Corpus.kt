package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.Key
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * The documents every benchmark measures against.
 *
 * One corpus, shared, and deterministic from a fixed seed — because a benchmark whose input varies
 * between runs measures the input. It is shaped like the JSON these engines actually see: a handful
 * of low-cardinality strings a query would filter on, numbers with a scale (which is what JSON
 * numbers mostly are, and what the shredded column had to be built around), a repeated path, a
 * nullable path, and enough incidental fields that a document is not a two-field toy.
 *
 * `sizeBytes` is what an ingest benchmark divides by: throughput in documents per second says nothing
 * without it, because the number is chosen by whoever wrote the corpus.
 */
object Corpus {

    private val teams = listOf("analytics", "platform", "search", "storage", "billing", "growth", "core")
    private val regions = listOf("eu-west", "us-east", "us-west", "ap-south")
    private val random = Random(20260727)

    /** A document as JSON text. Same index, same bytes, every run. */
    fun json(index: Int): String {
        val local = Random(index * 31L + 7)
        return buildString(220) {
            append("""{"id":$index,""")
            append(""""team":"${teams[index % teams.size]}",""")
            append(""""region":"${regions[index % regions.size]}",""")
            append(""""score":${index % 1000},""")
            append(""""price":${index % 900}.${"%02d".format(index % 100)},""")
            append(""""active":${index % 3 == 0},""")
            append(""""tags":["t${index % 11}","t${index % 7}","t${index % 5}"],""")
            append(""""user":{"name":"user-${local.nextInt(100_000)}","seat":${local.nextInt(500)}},""")
            append(""""note":${if (index % 9 == 0) "null" else "\"note ${local.nextInt(1_000_000)}\""}""")
            append("}")
        }
    }

    /** Bytes of JSON text for [count] documents, for a throughput figure that means something. */
    fun sizeBytes(count: Int): Long = (0 until count).sumOf { json(it).length.toLong() }

    fun key(index: Int): Key = Key.of("key:%09d".format(index))

    /** A directory that will be deleted with the benchmark that made it. */
    fun scratch(prefix: String): Path = Files.createTempDirectory("rabosh-bench-$prefix")

    fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        directory.toFile().deleteRecursively()
    }

    /**
     * A seeded index stream, so a lookup benchmark asks for different keys without measuring `Random`.
     *
     * **[count] must be a power of two.** Callers walk it with `and (size - 1)`, which is a modulus
     * only then — and getting it wrong does not fail, it silently biases every lookup towards a few
     * keys and turns a point-lookup figure into a measurement of one segment's first blocks.
     */
    fun probes(count: Int, bound: Int): IntArray {
        require(count > 0 && count and (count - 1) == 0) { "probe count must be a power of two, not $count" }
        return IntArray(count) { random.nextInt(bound) }
    }
}
