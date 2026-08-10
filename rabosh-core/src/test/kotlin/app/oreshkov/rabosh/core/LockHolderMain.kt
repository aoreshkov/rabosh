package app.oreshkov.rabosh.core

import java.nio.file.Path

/**
 * A process that opens a store and then just holds it. Launched as a separate JVM by [StoreLockTest].
 *
 * The contract with the parent is one line:
 *
 * ```
 * HELD             the store is open and the directory lock is taken
 * ```
 *
 * It then blocks until the parent kills it. There is no clean exit and no second line, because the
 * only thing the parent needs is the window in which the lock is genuinely held by *another
 * process* — which is the state `StoreLockedException` exists to report and the one state a
 * single-JVM test cannot reach. `OverlappingFileLockException`, the same-process case, is a
 * different code path with a different message, and testing one against the other is testing
 * neither.
 */
internal object LockHolderMain {

    @JvmStatic
    fun main(arguments: Array<String>) {
        val directory = Path.of(arguments[0])
        DocumentStore.open(directory).use {
            println("HELD")
            System.out.flush()
            // Held until killed. `Thread.sleep` rather than a latch: there is nothing to wait for,
            // and a child that exited on its own would release the lock in the middle of the
            // parent's assertion.
            Thread.sleep(HOLD_MILLIS)
        }
    }

    /** Far longer than the parent needs, and bounded so a leaked child cannot outlive the build. */
    private const val HOLD_MILLIS = 120_000L
}
