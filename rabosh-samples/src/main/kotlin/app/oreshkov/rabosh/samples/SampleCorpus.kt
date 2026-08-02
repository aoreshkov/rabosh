package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.core.Key

/**
 * The documents both samples write: service events, as JSON nobody declared a schema for.
 *
 * **The shape is the argument.** A sample corpus of identical two-field documents would make
 * `schema().render()` a list of two paths at 100% presence and `indexCandidates()` a formality — it
 * would demonstrate the API and hide the point, which is that the engine works out a structure the
 * writer never stated. So this corpus is *deliberately ragged*, in the four ways real JSON is:
 *
 *  - `$.region` is present in about 70% of documents and absent from the rest, so presence is a
 *    number rather than a certainty.
 *  - `$.note` is present in about 60% and is explicitly `null` in some of those — a different fact
 *    from being absent, and the model reports them separately.
 *  - `$.latencyMs` is a number in all but roughly one document in fifty, where it arrives as a
 *    string. That is what an upstream producer changing its mind looks like, and it is why type
 *    stability is reported and why a numeric predicate matches numeric values *only*.
 *  - `$.tags[*]` is a repeated path, so its presence legitimately exceeds 100%.
 *
 * Deterministic: the same index yields the same bytes on every run, so a sample's output can be
 * asserted and two runs can be compared.
 */
internal object SampleCorpus {

    private val services = listOf("search", "checkout", "billing", "inventory", "auth")
    private val levels = listOf("debug", "info", "warn", "error")
    private val regions = listOf("eu-west", "us-east", "us-west", "ap-south")

    /** The key of the [index]-th event. Zero-padded so key order is index order. */
    fun key(index: Int): Key = Key.of("event:%08d".format(index))

    /** The [index]-th event, as the JSON text a producer would have sent. */
    fun json(index: Int): String = buildString(200) {
        append("""{"id":$index,""")
        append(""""service":"${services[index % services.size]}",""")
        append(""""level":"${levels[index % levels.size]}",""")
        // One document in fifty reports its latency as a string. See the class comment.
        append(
            if (index % 50 == 17) {
                """"latencyMs":"${index % 400}","""
            } else {
                """"latencyMs":${index % 400},"""
            },
        )
        append(""""tags":["t${index % 7}","t${index % 3}"]""")
        if (index % 10 < 7) append(""","region":"${regions[index % regions.size]}"""")
        if (index % 5 < 3) append(""","note":${if (index % 15 == 4) "null" else "\"note $index\""}""")
        append("}")
    }

    /** How many events carry [service] among the first [count]. The answer a query must agree with. */
    fun countOf(service: String, count: Int): Int =
        (0 until count).count { services[it % services.size] == service }
}
