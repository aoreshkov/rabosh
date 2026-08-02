package app.oreshkov.rabosh.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single background worker that flushes memtables and compacts levels.
 *
 * **A platform thread, not a virtual one.** It spends its life blocked on file IO in long stretches
 * and doing real work between them, which is the shape a platform thread is for; a virtual thread
 * buys nothing when there is exactly one of them and it is never waiting on many things at once.
 *
 * **A daemon**, so a caller that forgets to close a store cannot keep the JVM alive. Everything it
 * does is restartable — a flush that never finished leaves an unreferenced file and a memtable
 * whose log is still on disk — so being killed at any point costs work, never data.
 *
 * [awaitIdle] exists for tests and for [StoreOptions.backgroundMaintenance] being off, and it
 * rethrows whatever the worker failed with. A failure that nobody waits for still stops the store
 * from writing, through the same path an IO failure on the write path takes.
 */
internal class Maintenance(
    name: String,
    private val body: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var pending = false
    private var running = false
    private var stopped = false
    private var failure: Throwable? = null

    private val thread = Thread.ofPlatform().daemon().name(name).unstarted(::loop).also { it.start() }

    fun schedule() {
        lock.withLock {
            if (stopped) return
            pending = true
            condition.signalAll()
        }
    }

    /** Blocks until no work is pending or running, then rethrows any failure the worker had. */
    fun awaitIdle() {
        lock.withLock {
            while (!stopped && (pending || running)) condition.await()
        }
        lock.withLock { failure }?.let { throw it }
    }

    private fun loop() {
        while (true) {
            lock.withLock {
                while (!stopped && !pending) condition.await()
                if (stopped) return
                pending = false
                running = true
            }
            try {
                body()
            } catch (thrown: Throwable) {
                lock.withLock { if (failure == null) failure = thrown }
                onFailure(thrown)
            } finally {
                lock.withLock {
                    running = false
                    condition.signalAll()
                }
            }
        }
    }

    override fun close() {
        lock.withLock {
            stopped = true
            condition.signalAll()
        }
        thread.join(CLOSE_TIMEOUT_MILLIS)
    }

    private companion object {
        /**
         * How long `close` waits for the worker to notice.
         *
         * It is a bound, not a deadline the worker is expected to reach: a compaction in flight
         * writes to a file no version names, so abandoning it loses nothing. Waiting forever would
         * make closing a store depend on how much data it happened to be merging.
         */
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}
