package app.oreshkov.rabosh.testkit.fs

import java.io.IOException
import java.net.URI
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real filesystem that can be told to fail.
 *
 * Every operation is delegated to the platform's own filesystem — the bytes are real, the durability
 * is real, and a store opened through this is the same store — until a [Fault] says otherwise. That
 * is deliberate and it is the difference between this and an in-memory filesystem: what is being
 * tested is how the engine behaves when a *real* write fails partway through, and an emulated
 * filesystem would also be emulating the thing under test.
 *
 * ```kotlin
 * val fault = Fault.onSuffix(FaultOperation.FORCE, ".log")
 * FaultyFileSystem.wrapping(realDirectory).use { fs ->
 *     fs.arm(fault)
 *     DocumentStore.open(fs.path(realDirectory)).use { store -> … }
 *     assertEquals(1, fault.fireCount)
 * }
 * ```
 *
 * **Faults are armed and disarmed while the store is open**, which is what makes a failure land in
 * the middle of a sequence rather than at the start of one. [heal] is how a test gets from "the write
 * failed" to "and now the store is reopened on a working disk", which is the state an operator is
 * actually in afterwards.
 *
 * Not registered as a URI scheme and not installed globally: it is created, used and closed by one
 * test, and it never becomes the default filesystem for anything else.
 */
public class FaultyFileSystem private constructor(
    internal val delegate: FileSystem,
) : FileSystem() {

    private val faults = CopyOnWriteArrayList<Fault>()
    private val faultProvider = FaultyFileSystemProvider(this)

    @Volatile
    private var open = true

    /** Every write that has actually reached the platform, for the amplification benchmarks. */
    private val written = java.util.concurrent.atomic.AtomicLong(0)

    /** Bytes written through this filesystem since it was created. */
    public val bytesWritten: Long get() = written.get()

    /** Arms [fault]. Returns it, so a test can hold it and assert its [Fault.fireCount]. */
    public fun arm(fault: Fault): Fault {
        faults.add(fault)
        return fault
    }

    /** Disarms everything: the disk works again. */
    public fun heal() {
        faults.clear()
    }

    /** A path in this filesystem for a path in the real one. */
    public fun path(real: Path): Path = FaultyPath(this, real)

    override fun provider(): FileSystemProvider = faultProvider

    override fun close() {
        open = false
        faults.clear()
    }

    override fun isOpen(): Boolean = open

    override fun isReadOnly(): Boolean = delegate.isReadOnly

    override fun getSeparator(): String = delegate.separator

    override fun getRootDirectories(): Iterable<Path> = delegate.rootDirectories.map { FaultyPath(this, it) }

    override fun getFileStores(): Iterable<FileStore> = delegate.fileStores

    override fun supportedFileAttributeViews(): Set<String> = delegate.supportedFileAttributeViews()

    override fun getPath(first: String, vararg more: String): Path =
        FaultyPath(this, delegate.getPath(first, *more))

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        val matcher = delegate.getPathMatcher(syntaxAndPattern)
        return PathMatcher { path -> matcher.matches(unwrap(path)) }
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        delegate.userPrincipalLookupService

    override fun newWatchService(): WatchService = delegate.newWatchService()

    override fun toString(): String = "FaultyFileSystem(${faults.size} fault(s))"

    // --- the fault decision, in one place --------------------------------------------------------

    /**
     * Throws if a fault covers this call.
     *
     * [bytes] is the size of a write, which only an out-of-space rule looks at. Every rule sees every
     * matching call whether or not it fires, because "after the third one" is counted in calls the
     * engine made rather than in calls that failed.
     */
    internal fun check(operation: FaultOperation, path: Path, bytes: Int = 0) {
        armed(operation, path, bytes)?.let { throw it.fire() }
    }

    /** The fault covering this call, or `null`. */
    internal fun armed(operation: FaultOperation, path: Path, bytes: Int = 0): Fault? =
        faults.firstOrNull { it.armedFor(operation, path, bytes) }

    internal fun recordWrite(bytes: Int) {
        written.addAndGet(bytes.toLong())
    }

    internal fun unwrap(path: Path): Path = when (path) {
        is FaultyPath -> path.delegate
        else -> path
    }

    internal fun wrap(path: Path): Path = when (path) {
        is FaultyPath -> path
        else -> FaultyPath(this, path)
    }

    public companion object {
        /**
         * A faulty view of the filesystem [anchor] lives on.
         *
         * The directory itself is untouched: this is a lens over the real one, so a store written
         * through it can be reopened through the real filesystem afterwards — which several tests do,
         * to show that what survived a fault is what an operator would find.
         */
        public fun wrapping(anchor: Path): FaultyFileSystem = FaultyFileSystem(anchor.fileSystem)
    }
}

/** A path that belongs to a [FaultyFileSystem], so every operation on it is intercepted. */
internal class FaultyPath(
    private val fs: FaultyFileSystem,
    val delegate: Path,
) : Path {

    override fun getFileSystem(): FileSystem = fs

    override fun isAbsolute(): Boolean = delegate.isAbsolute

    override fun getRoot(): Path? = delegate.root?.let { FaultyPath(fs, it) }

    override fun getFileName(): Path? = delegate.fileName?.let { FaultyPath(fs, it) }

    override fun getParent(): Path? = delegate.parent?.let { FaultyPath(fs, it) }

    override fun getNameCount(): Int = delegate.nameCount

    override fun getName(index: Int): Path = FaultyPath(fs, delegate.getName(index))

    override fun subpath(beginIndex: Int, endIndex: Int): Path =
        FaultyPath(fs, delegate.subpath(beginIndex, endIndex))

    override fun startsWith(other: Path): Boolean = delegate.startsWith(fs.unwrap(other))

    override fun endsWith(other: Path): Boolean = delegate.endsWith(fs.unwrap(other))

    override fun normalize(): Path = FaultyPath(fs, delegate.normalize())

    override fun resolve(other: Path): Path = FaultyPath(fs, delegate.resolve(fs.unwrap(other)))

    override fun relativize(other: Path): Path = FaultyPath(fs, delegate.relativize(fs.unwrap(other)))

    override fun toUri(): URI = delegate.toUri()

    override fun toAbsolutePath(): Path = FaultyPath(fs, delegate.toAbsolutePath())

    override fun toRealPath(vararg options: java.nio.file.LinkOption): Path =
        FaultyPath(fs, delegate.toRealPath(*options))

    override fun register(
        watcher: WatchService,
        events: Array<out java.nio.file.WatchEvent.Kind<*>>,
        vararg modifiers: java.nio.file.WatchEvent.Modifier,
    ): java.nio.file.WatchKey = delegate.register(watcher, events, *modifiers)

    override fun compareTo(other: Path): Int = delegate.compareTo(fs.unwrap(other))

    override fun equals(other: Any?): Boolean =
        other is FaultyPath && delegate == other.delegate && fs === other.fs

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = delegate.toString()
}

/** Throws [IOException] when a fault covers the call; otherwise it is the platform's own answer. */
private class FaultyFileSystemProvider(private val fs: FaultyFileSystem) : FileSystemProvider() {

    private val delegate: FileSystemProvider = fs.delegate.provider()

    override fun getScheme(): String = "faulty"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException("a FaultyFileSystem is created by wrapping, not by URI")

    override fun getFileSystem(uri: URI): FileSystem = fs

    override fun getPath(uri: URI): Path = fs.wrap(delegate.getPath(uri))

    /**
     * The one that makes this work at all.
     *
     * `FileChannel.open` routes here, and the engine maps segments off the channel it gets back. A
     * channel that could not be mapped would make this harness unusable for exactly the code paths
     * worth testing, which is why [FaultyFileChannel] delegates the FFM overload rather than
     * inheriting `FileChannel`'s default, which throws.
     */
    override fun newFileChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: java.nio.file.attribute.FileAttribute<*>,
    ): java.nio.channels.FileChannel {
        val real = fs.unwrap(path)
        fs.check(FaultOperation.OPEN, real)
        return FaultyFileChannel(fs, real, delegate.newFileChannel(real, options, *attrs))
    }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: java.nio.file.attribute.FileAttribute<*>,
    ): java.nio.channels.SeekableByteChannel {
        val real = fs.unwrap(path)
        fs.check(FaultOperation.OPEN, real)
        return FaultyFileChannel(fs, real, delegate.newFileChannel(real, options, *attrs))
    }

    override fun newDirectoryStream(dir: Path, filter: java.nio.file.DirectoryStream.Filter<in Path>):
        java.nio.file.DirectoryStream<Path> {
        val real = fs.unwrap(dir)
        val stream = delegate.newDirectoryStream(real) { true }
        return object : java.nio.file.DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val underlying = stream.iterator()
                return object : MutableIterator<Path> {
                    private var next: Path? = advance()

                    private fun advance(): Path? {
                        while (underlying.hasNext()) {
                            val candidate = fs.wrap(underlying.next())
                            if (filter.accept(candidate)) return candidate
                        }
                        return null
                    }

                    override fun hasNext(): Boolean = next != null

                    override fun next(): Path {
                        val current = next ?: throw NoSuchElementException()
                        next = advance()
                        return current
                    }

                    override fun remove(): Unit = throw UnsupportedOperationException()
                }
            }

            override fun close(): Unit = stream.close()
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: java.nio.file.attribute.FileAttribute<*>) {
        val real = fs.unwrap(dir)
        fs.check(FaultOperation.CREATE_DIRECTORY, real)
        delegate.createDirectory(real, *attrs)
    }

    override fun delete(path: Path) {
        val real = fs.unwrap(path)
        fs.check(FaultOperation.DELETE, real)
        delegate.delete(real)
    }

    override fun copy(source: Path, target: Path, vararg options: java.nio.file.CopyOption) {
        delegate.copy(fs.unwrap(source), fs.unwrap(target), *options)
    }

    override fun move(source: Path, target: Path, vararg options: java.nio.file.CopyOption) {
        val real = fs.unwrap(source)
        fs.check(FaultOperation.MOVE, real)
        fs.check(FaultOperation.MOVE, fs.unwrap(target))
        delegate.move(real, fs.unwrap(target), *options)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean =
        delegate.isSameFile(fs.unwrap(path), fs.unwrap(path2))

    override fun isHidden(path: Path): Boolean = delegate.isHidden(fs.unwrap(path))

    override fun getFileStore(path: Path): FileStore = delegate.getFileStore(fs.unwrap(path))

    override fun checkAccess(path: Path, vararg modes: java.nio.file.AccessMode) {
        delegate.checkAccess(fs.unwrap(path), *modes)
    }

    override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: java.nio.file.LinkOption,
    ): V? = delegate.getFileAttributeView(fs.unwrap(path), type, *options)

    override fun <A : java.nio.file.attribute.BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: java.nio.file.LinkOption,
    ): A = delegate.readAttributes(fs.unwrap(path), type, *options)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: java.nio.file.LinkOption,
    ): MutableMap<String, Any> = delegate.readAttributes(fs.unwrap(path), attributes, *options)

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any?,
        vararg options: java.nio.file.LinkOption,
    ) {
        delegate.setAttribute(fs.unwrap(path), attribute, value, *options)
    }
}

private typealias OpenOption = java.nio.file.OpenOption
