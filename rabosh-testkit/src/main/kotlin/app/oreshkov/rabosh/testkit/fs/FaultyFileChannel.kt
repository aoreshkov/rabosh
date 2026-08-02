package app.oreshkov.rabosh.testkit.fs

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Path

/**
 * A real channel that can be told to fail, short-write, or refuse to force.
 *
 * Three things here are load-bearing.
 *
 * **The FFM overload is delegated explicitly.** `FileChannel.map(mode, offset, size, Arena)` is a
 * concrete method on `FileChannel` that throws `UnsupportedOperationException`; only the platform's
 * own implementation overrides it. A wrapper that inherited it would be unusable for this engine,
 * which maps every segment and every sidecar — so the fault harness would cover exactly the paths
 * that do not matter. Delegating hands back a segment over the real file, which is what a reader
 * needs.
 *
 * **A short write is not a failed write.** It writes some of the bytes and *then* fails, which is the
 * state a torn record recovers from and the one a caller cannot tell from a full write without
 * looking. `Fault.shortWrite` is how a test asks for it.
 *
 * **A failed `force` leaves the bytes written.** They are in the page cache, the file has them, and
 * the engine has been told they are not durable. That is the sharpest fault a storage engine can be
 * handed and it is invisible to any harness that only fails writes.
 */
internal class FaultyFileChannel(
    private val fs: FaultyFileSystem,
    private val path: Path,
    private val delegate: FileChannel,
) : FileChannel() {

    override fun read(dst: ByteBuffer): Int {
        fs.check(FaultOperation.READ, path, dst.remaining())
        return delegate.read(dst)
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        fs.check(FaultOperation.READ, path)
        return delegate.read(dsts, offset, length)
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        fs.check(FaultOperation.READ, path, dst.remaining())
        return delegate.read(dst, position)
    }

    override fun write(src: ByteBuffer): Int {
        val fault = fs.armed(FaultOperation.WRITE, path, src.remaining())
        if (fault == null) return delegate.write(src).also(fs::recordWrite)
        val partial = writePartially(src, fault.shortWrite) { delegate.write(it) }
        fs.recordWrite(partial)
        throw fault.fire()
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        fs.check(FaultOperation.WRITE, path, srcs.drop(offset).take(length).sumOf { it.remaining() })
        return delegate.write(srcs, offset, length).also { fs.recordWrite(it.toInt()) }
    }

    override fun write(src: ByteBuffer, position: Long): Int {
        val fault = fs.armed(FaultOperation.WRITE, path, src.remaining())
        if (fault == null) return delegate.write(src, position).also(fs::recordWrite)
        val partial = writePartially(src, fault.shortWrite) { delegate.write(it, position) }
        fs.recordWrite(partial)
        throw fault.fire()
    }

    /** Writes the first [limit] bytes and leaves the rest; `-1` writes nothing. */
    private fun writePartially(src: ByteBuffer, limit: Int, sink: (ByteBuffer) -> Int): Int {
        if (limit <= 0) return 0
        val slice = src.slice()
        slice.limit(minOf(limit, slice.remaining()))
        val written = sink(slice)
        src.position(src.position() + written)
        return written
    }

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): FileChannel = also { delegate.position(newPosition) }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): FileChannel = also { delegate.truncate(size) }

    override fun force(metaData: Boolean) {
        fs.check(FaultOperation.FORCE, path)
        delegate.force(metaData)
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long =
        delegate.transferTo(position, count, target)

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        fs.check(FaultOperation.WRITE, path, count.toInt())
        return delegate.transferFrom(src, position, count)
    }

    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer =
        delegate.map(mode, position, size)

    /** See the class KDoc: inheriting this would make the harness useless for a mapped engine. */
    override fun map(mode: MapMode, offset: Long, size: Long, arena: Arena): MemorySegment =
        delegate.map(mode, offset, size, arena)

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock =
        delegate.lock(position, size, shared)

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? =
        delegate.tryLock(position, size, shared)

    override fun implCloseChannel() {
        delegate.close()
    }
}
