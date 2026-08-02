package app.oreshkov.rabosh.core

import java.nio.file.Path

/**
 * A writer that expects to be killed. Launched as a separate JVM by [CrashRecoveryTest].
 *
 * The contract with the parent is one line per fact:
 *
 * ```
 * READY            the store is open
 * ACK <index>      the commit of document <index> has returned
 * DONE             every requested commit was made and the store closed cleanly
 * ```
 *
 * Every line is flushed before the next commit begins, so a line the parent has read is a commit
 * that had *already returned* in the child. That is what makes the acknowledged prefix observable
 * from another process, and it is the only way to test the guarantee honestly: an in-process test
 * would have the store's own state to consult, and the state is precisely what is in doubt.
 *
 * The optional fourth argument rotates the memtable every *n* commits. With background maintenance
 * on and segments kept small, that puts a flush and a compaction in flight while the commits keep
 * coming — so the kill lands inside one of them rather than only inside a log append. Recovery has
 * more to get right in that state: a segment written but not recorded, a manifest record half
 * appended, logs deleted for a flush that did complete.
 *
 * @see CrashRecoveryTest
 */
internal object CrashWriterMain {

    @JvmStatic
    fun main(arguments: Array<String>) {
        val directory = Path.of(arguments[0])
        val durability = Durability.valueOf(arguments[1])
        val commits = arguments[2].toInt()
        val rotateEvery = arguments.getOrNull(3)?.toInt() ?: 0

        val options = if (rotateEvery > 0) {
            StoreOptions(
                durability = durability,
                segmentMaxBytes = 4 * 1024,
                blockSize = 256,
                l0CompactionTrigger = 2,
                baseLevelBytes = 8 * 1024,
            )
        } else {
            StoreOptions(durability = durability)
        }

        val store = DocumentStore.open(directory, options)
        report("READY")
        for (index in 0 until commits) {
            store.put(keyFor(index), documentFor(index))
            report("ACK $index")
            if (rotateEvery > 0 && (index + 1) % rotateEvery == 0) store.rotate()
        }
        store.close()
        report("DONE")
    }

    private fun report(line: String) {
        // Explicit flush: standard output to a pipe is buffered, and an acknowledgement the parent
        // has not been told about is indistinguishable from one that never happened.
        println(line)
        System.out.flush()
    }
}
