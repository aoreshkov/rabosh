package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path

/**
 * Defines indexes until it is killed. Launched as a separate JVM by [IndexCrashTest].
 *
 * The contract with the parent is one line per fact:
 *
 * ```
 * READY              the store is open, loaded and flushed
 * INDEX <id> <path>  the index is defined — createIndex returned, or a background build handed
 *                    back its handle, which happens only after the registry write
 * DROP <id>          dropIndex returned for that index
 * ```
 *
 * Every line is flushed before the next operation begins, so a line the parent has read is an
 * operation that had **already returned** in the child. That is the whole instrument: an index the
 * parent was told about must survive a kill, because the definition is made durable before anything
 * is built — and an index the parent was told was dropped must stay dropped.
 *
 * **The second argument chooses where the kill lands.** With `BLOCKING` the child is inside
 * `createIndex`; with `BACKGROUND` it is inside `createIndexInBackground`, so the kill arrives while a
 * build is running on a thread of the catalog's own and the parent has *already* been told the index
 * exists. That is the sharper of the two: the definition is durable and the sidecars provably are not,
 * which is exactly the half-built state the phase claims is safe. Neither run may weaken an assertion.
 *
 * An in-process test would prove none of this. It would run `finally` blocks and flush buffers, and
 * what is in doubt is precisely the state a process that dies mid-write leaves behind.
 *
 * @see IndexCrashTest
 */
internal object CrashIndexerMain {

    @JvmStatic
    fun main(arguments: Array<String>) {
        val directory = Path.of(arguments[0])
        val documents = arguments[1].toInt()
        val background = arguments.getOrNull(2) == "BACKGROUND"

        val options = StoreOptions(
            durability = Durability.SYNC,
            segmentMaxBytes = 16 * 1024,
            blockSize = 512,
            backgroundMaintenance = true,
            segmentObserver = IndexCatalog(directory).also { catalog = it },
        )

        DocumentStore.open(directory, options).use { store ->
            val indexes = checkNotNull(catalog)
            indexes.attach(store)
            for (index in 0 until documents) store.put(keyFor(index), documentFor(index))
            store.flush()
            report("READY")

            // Round after round of define-and-drop, with writes in between so flushes and compactions
            // are in flight when the kill lands. Whatever the child is in the middle of, what it has
            // already reported has to be true afterwards.
            var round = 0
            while (true) {
                // Alternating kinds, so a kill lands among columns as often as among posting lists
                // and the orphan assertions cover both.
                val definition = if (round % 2 == 0) {
                    IndexDefinition.inverted("$.f${round % 4}")
                } else {
                    IndexDefinition.column("$.f${round % 4}")
                }
                // In background mode nothing is awaited: the point is for the kill to land while the
                // build is still going, with the parent already told the index exists. The handle
                // comes back only after the registry write, so the line remains a fact either way.
                val handle = if (background) {
                    checkNotNull(indexes.createIndexInBackground(store, definition).handle)
                } else {
                    indexes.createIndex(store, definition)
                }
                report("INDEX ${handle.id} ${handle.path}")

                for (index in 0 until 200) store.put(keyFor(round * 200 + index + documents), documentFor(index))
                store.flush()

                if (round % 3 == 2) {
                    indexes.dropIndex(handle)
                    report("DROP ${handle.id}")
                }
                round++
            }
        }
    }

    private var catalog: IndexCatalog? = null

    private fun keyFor(index: Int): Key = Key.of("key:%08d".format(index))

    private fun documentFor(index: Int): Variant = Variant.fromJson(
        """{"f0":"a${index % 13}","f1":${index % 29},"f2":"c${index % 7}","f3":${index % 3 == 0}}""",
    )

    private fun report(line: String) {
        // Explicit flush: standard output to a pipe is buffered, and an operation the parent has not
        // been told about is indistinguishable from one that never happened.
        println(line)
        System.out.flush()
    }
}
