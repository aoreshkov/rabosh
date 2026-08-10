package app.oreshkov.rabosh.testkit.crash

import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A second JVM, launched from a test, that can be killed without warning.
 *
 * Crash safety cannot be tested in-process. A store closed by an exception still runs `finally`
 * blocks, still flushes buffers, and still lets the operating system write everything back — so an
 * in-process "crash" tests the recovery code against a state the real failure never produces. The
 * only faithful test is a process that dies mid-write with no chance to tidy up, which is what
 * [killForcibly] arranges: `SIGKILL` on POSIX, `TerminateProcess` on Windows, neither catchable and
 * neither running a shutdown hook.
 *
 * What survives such a kill is exactly what the operating system already has: bytes handed over with
 * a write survive, bytes still in the process are gone. That makes this the right harness for
 * *acknowledgement ordering* — did anything get acknowledged that is not there? — while power-loss
 * behaviour, where the page cache goes too, is tested by damaging the files directly.
 *
 * The child reports progress by printing lines to standard output and flushing them, and the parent
 * reads them with [nextLine]. A line the parent has read is a fact the child had established before
 * it died, which is what makes the acknowledged prefix observable from outside.
 */
public class ChildJvm private constructor(
    private val process: Process,
    private val lines: BlockingQueue<String>,
    private val errors: StringBuilder,
) : AutoCloseable {

    @Volatile
    private var outputEnded = false

    /**
     * The next line the child printed, or `null` if it printed nothing within [timeout] or has
     * closed its output.
     */
    public fun nextLine(timeout: Duration = DEFAULT_TIMEOUT): String? =
        lines.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /** `true` once the child's standard output has ended, which usually means the child has exited. */
    public fun outputEnded(): Boolean = outputEnded && lines.isEmpty()

    /** Kills the child immediately and uninterruptibly, and waits for the operating system. */
    public fun killForcibly() {
        process.destroyForcibly()
        process.waitFor(KILL_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    /** The child's exit code, or `null` if it is still running after [timeout]. */
    public fun awaitExit(timeout: Duration = DEFAULT_TIMEOUT): Int? =
        if (process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            null
        }

    /** Everything the child has written to standard error. The first thing to look at on a failure. */
    public val standardError: String get() = synchronized(errors) { errors.toString() }

    /**
     * The child's operating-system process id.
     *
     * For the one assertion that cannot be made any other way: that a `LOCK` file names the process
     * actually holding it. A test can compare `StoreLockedException.holder?.pid` against this and
     * know the record is right rather than merely well-formed.
     */
    public val pid: Long get() = process.pid()

    override fun close() {
        if (process.isAlive) killForcibly()
    }

    public companion object {
        /** Generous by design: a loaded CI machine can take seconds to start a JVM. */
        public val DEFAULT_TIMEOUT: Duration = 30.seconds

        private val KILL_TIMEOUT: Duration = 10.seconds

        /**
         * Default flags for the child.
         *
         * The native-access flag matches the one the test JVM runs with, since the engine maps memory
         * through the FFM API. `TieredStopAtLevel=1` gives up the top compiler tier, which for a
         * child that lives for a few hundred milliseconds is a straight win: it starts sooner, and
         * starting sooner is the whole latency of a crash test.
         */
        public val DEFAULT_JVM_ARGUMENTS: List<String> = listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:TieredStopAtLevel=1",
        )

        /**
         * Launches [mainClass] — which must be on this JVM's classpath — with [arguments].
         *
         * The child inherits this process's classpath, so the class under test needs no packaging
         * step: a `main` in the test source set is directly launchable.
         */
        public fun launch(
            mainClass: String,
            arguments: List<String> = emptyList(),
            jvmArguments: List<String> = DEFAULT_JVM_ARGUMENTS,
        ): ChildJvm {
            val command = buildList {
                add(javaLauncher())
                addAll(jvmArguments)
                add("-cp")
                add(System.getProperty("java.class.path"))
                add(mainClass)
                addAll(arguments)
            }

            val process = ProcessBuilder(command).start()
            val lines = ArrayBlockingQueue<String>(LINE_CAPACITY)
            val errors = StringBuilder()
            val child = ChildJvm(process, lines, errors)

            drain(
                reader = process.inputStream.bufferedReader(),
                name = "rabosh-child-stdout",
                // Blocks rather than drops if the parent is slower than the child: a lost line would
                // look exactly like a lost acknowledgement, which is the bug being hunted.
                consume = { line -> lines.put(line) },
                onEnd = { child.outputEnded = true },
            )

            drain(
                reader = process.errorStream.bufferedReader(),
                name = "rabosh-child-stderr",
                consume = { line -> synchronized(errors) { errors.appendLine(line) } },
            )

            return child
        }

        private const val LINE_CAPACITY = 1024

        private fun drain(
            reader: BufferedReader,
            name: String,
            consume: (String) -> Unit,
            onEnd: () -> Unit = {},
        ) {
            val thread = Thread({
                reader.use { open ->
                    while (true) {
                        val line = open.readLine() ?: break
                        consume(line)
                    }
                }
                onEnd()
            }, name)
            thread.isDaemon = true
            thread.start()
        }

        /**
         * The launcher of the running JVM, so the child is the same build.
         *
         * `java.home` rather than `ProcessHandle.command()`: the latter reports whatever wrapper
         * started this process, which under a build tool is not necessarily a JVM launcher at all.
         */
        private fun javaLauncher(): String {
            val home = Path.of(System.getProperty("java.home"))
            val candidates = listOf("java", "java.exe")
            for (name in candidates) {
                val candidate = home.resolve("bin").resolve(name)
                if (Files.isExecutable(candidate)) return candidate.toString()
            }
            error("no java launcher under $home${File.separator}bin")
        }
    }
}
