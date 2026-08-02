package app.oreshkov.rabosh.bench

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds

/**
 * Holds JMH's lock, so that the failure the benchmark-ran assertion exists for can be arranged rather
 * than reasoned about.
 *
 * JMH takes an exclusive lock in the temp directory so two runs cannot measure over each other. When
 * it cannot take it, it reports a `RunnerException` — and kotlinx-benchmark catches that, prints it
 * and exits zero, so the build used to print `BUILD SUCCESSFUL` over a benchmark that never started.
 * That is the exact reproduction that found the hole, and this is it in a form anyone can repeat:
 *
 * ```
 * ./gradlew :rabosh-bench:holdJmhLock --args=90    # one terminal
 * ./gradlew :rabosh-bench:smokeBenchmark           # another: must FAIL, naming the missing results
 * ```
 *
 * Deliberately a real lock rather than a file left lying about: the benchmark tasks clear a `jmh.lock`
 * that nobody holds, and the whole point of that rule is that a held one is left alone. A fixture that
 * only created the file would be swept away before JMH ever saw it, and would prove nothing.
 *
 * Not a benchmark and not a test — the unit tests in `build-logic` cover the decision, and this covers
 * the wiring, which is the part no unit test can reach.
 */
object JmhLockMain {

    private const val DEFAULT_SECONDS = 60L

    @JvmStatic
    fun main(arguments: Array<String>) {
        val seconds = arguments.firstOrNull()?.toLongOrNull() ?: DEFAULT_SECONDS
        val lock = Path.of(System.getProperty("java.io.tmpdir"), "jmh.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            .use { channel ->
                val held = channel.tryLock()
                if (held == null) {
                    println("$lock is already held by something else; nothing to arrange.")
                    return
                }
                held.use {
                    println("Holding $lock for $seconds s. A benchmark started now must fail the build.")
                    Thread.sleep(seconds.seconds.inWholeMilliseconds)
                }
            }
        println("Released $lock.")
    }
}
