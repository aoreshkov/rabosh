package app.oreshkov.rabosh.testkit.fs

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What a filesystem does instead of what it was asked to do.
 *
 * The operations a storage engine's correctness rests on, each named separately, because the engine
 * treats them differently and a harness that lumped them together could not tell the difference. A
 * failed [FORCE] with a successful [WRITE] is the sharpest of them: the bytes are in the page cache
 * and the engine has been told they are on the platter. Nothing that only knows "the write failed"
 * can produce that state.
 */
public enum class FaultOperation {
    /** Opening a channel or a stream, for reading or for writing. */
    OPEN,

    /** Writing bytes — wholly, or partly when [Fault.shortWrite] says so. */
    WRITE,

    /** `FileChannel.force`: the durability barrier. */
    FORCE,

    /** Renaming, including the `ATOMIC_MOVE` that publishes a manifest or a registry. */
    MOVE,

    /** Deleting. */
    DELETE,

    /** Creating a directory. */
    CREATE_DIRECTORY,

    /** Reading bytes. The one that produces a failed read rather than a lost write. */
    READ,
}

/**
 * One rule: what to break, where, and when.
 *
 * **The counting is the part that matters.** A fault that fires on the *first* matching call tests
 * very little — the engine fails on its first write and refuses to carry on, which is the easy half.
 * The failures worth arranging are partway through a sequence: the fourth block of a segment, the
 * `force` after the log has already been written, the rename after the temporary file is complete
 * and correct. [after] is what expresses those, and [fireCount] is how a test proves its fault
 * actually happened rather than passing because nothing did.
 */
public class Fault private constructor(
    internal val operation: FaultOperation,
    internal val matches: (Path) -> Boolean,
    /** Matching calls to let through before this fires. */
    internal val after: Int,
    /** How many times it fires once it starts. */
    internal val times: Int,
    /** Bytes to accept before failing a write, or `-1` to fail without writing any. */
    internal val shortWrite: Int,
    /** Bytes the whole filesystem has left, or `-1` when this is not an out-of-space rule. */
    private val space: AtomicLong,
    internal val message: String,
) {
    private val seen = AtomicInteger(0)
    private val fired = AtomicInteger(0)

    /** How many times this rule has fired. Assert on it: a fault that never fired proves nothing. */
    public val fireCount: Int get() = fired.get()

    /** Whether this rule applies to this call, counting the call whether or not it does. */
    internal fun armedFor(operation: FaultOperation, path: Path, bytes: Int): Boolean {
        if (operation != this.operation || !matches(path)) return false
        val budget = space.get()
        if (budget >= 0) return space.addAndGet(-bytes.toLong()) < 0
        if (seen.getAndIncrement() < after) return false
        return fired.get() < times
    }

    internal fun fire(): IOException {
        fired.incrementAndGet()
        return IOException(message)
    }

    override fun toString(): String =
        "Fault($operation, after=$after, times=$times, shortWrite=$shortWrite, fired=$fireCount)"

    public companion object {
        /**
         * A fault on [operation] against files whose name ends with [suffix].
         *
         * The suffix, rather than the whole path, because a store's filenames are the axis that
         * means something: `.log` is durability, `.seg` is a flush or a compaction, `MANIFEST` and
         * `CURRENT` are publication, and `.pst` is derived data whose loss must cost a rescan and
         * nothing more.
         */
        public fun onSuffix(
            operation: FaultOperation,
            suffix: String,
            after: Int = 0,
            times: Int = 1,
            shortWrite: Int = -1,
            message: String = "injected $operation failure on *$suffix",
        ): Fault = Fault(
            operation,
            { path -> path.fileName?.toString()?.endsWith(suffix) == true },
            after,
            times,
            shortWrite,
            AtomicLong(-1),
            message,
        )

        /** A fault on [operation] against files whose name contains [text]. */
        public fun onName(
            operation: FaultOperation,
            text: String,
            after: Int = 0,
            times: Int = 1,
            shortWrite: Int = -1,
            message: String = "injected $operation failure on *$text*",
        ): Fault = Fault(
            operation,
            { path -> path.fileName?.toString()?.contains(text) == true },
            after,
            times,
            shortWrite,
            AtomicLong(-1),
            message,
        )

        /** A fault on [operation] against every path. */
        public fun on(
            operation: FaultOperation,
            after: Int = 0,
            times: Int = 1,
            shortWrite: Int = -1,
            message: String = "injected $operation failure",
        ): Fault = Fault(operation, { true }, after, times, shortWrite, AtomicLong(-1), message)

        /**
         * The disk filling up: writes succeed until [remainingBytes] have gone by, then every write
         * fails, whatever the file.
         *
         * A condition of the whole filesystem rather than of one file, which is what makes it
         * different in kind from a write fault: it arrives in the middle of whatever the engine
         * happened to be doing, and it does not go away.
         */
        public fun outOfSpace(remainingBytes: Long): Fault = Fault(
            FaultOperation.WRITE,
            { true },
            after = 0,
            times = Int.MAX_VALUE,
            shortWrite = -1,
            space = AtomicLong(remainingBytes),
            message = "No space left on device (injected)",
        )
    }
}
