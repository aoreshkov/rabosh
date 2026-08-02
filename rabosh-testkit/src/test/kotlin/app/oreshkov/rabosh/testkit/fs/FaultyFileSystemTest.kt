package app.oreshkov.rabosh.testkit.fs

import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The harness, tested before anything is tested with it.
 *
 * A fault injector that does not faithfully delegate is worse than none: every test written against
 * it would be describing a filesystem that does not exist. So this asserts both halves — that
 * *without* a fault it is indistinguishable from the real thing, including the FFM mapping the whole
 * engine depends on, and that *with* one the failure has the exact shape it claims.
 */
class FaultyFileSystemTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `without a fault it is the real filesystem`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(root).resolve("plain")
            Files.createDirectories(directory)
            val file = directory.resolve("data.bin")

            Files.write(file, byteArrayOf(1, 2, 3, 4))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(file))
            assertTrue(Files.exists(file))
            assertEquals(4L, Files.size(file))

            // And the real filesystem sees exactly the same bytes, because they are the same bytes.
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(root.resolve("plain/data.bin")))

            val listed = Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() } }
            assertEquals(listOf("data.bin"), listed)

            Files.move(file, directory.resolve("moved.bin"), StandardCopyOption.ATOMIC_MOVE)
            assertTrue(Files.exists(directory.resolve("moved.bin")))
            Files.delete(directory.resolve("moved.bin"))
            assertTrue(!Files.exists(directory.resolve("moved.bin")))
        }
    }

    /**
     * The one that decides whether this harness is usable at all.
     *
     * `FileChannel.map(mode, offset, size, Arena)` is concrete on `FileChannel` and throws; a wrapper
     * that inherited it would fail every mapped read in the engine, which is all of them.
     */
    @Test
    fun `a channel through the harness still maps through the FFM API`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("mapped.bin")
            Files.write(file, ByteArray(64) { it.toByte() })

            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                Arena.ofConfined().use { arena ->
                    val segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, 64, arena)
                    assertEquals(64L, segment.byteSize())
                    assertEquals(7, segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 7))
                }
            }
        }
    }

    @Test
    fun `a write fault fails the write and leaves the file short`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("failed.bin")
            val fault = fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".bin"))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(ByteArray(16))) }
            }
            assertEquals(1, fault.fireCount)
            assertEquals(0L, Files.size(root.resolve("failed.bin")), "nothing should have been written")
        }
    }

    /** A short write is the interesting one: some bytes land, and then it fails. */
    @Test
    fun `a short write writes what it says and then fails`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("short.bin")
            fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".bin", shortWrite = 6))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(ByteArray(20) { 9 })) }
            }
            assertEquals(6L, Files.size(root.resolve("short.bin")), "six bytes should have survived")
        }
    }

    /** A failed force leaves the bytes in the file: written, and not durable. */
    @Test
    fun `a force fault leaves the write in place`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("forced.bin")
            val fault = fs.arm(Fault.onSuffix(FaultOperation.FORCE, ".bin"))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                assertFailsWith<java.io.IOException> { channel.force(true) }
            }
            assertEquals(1, fault.fireCount)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(root.resolve("forced.bin")))
        }
    }

    /** `after` counts the calls the caller made, not the ones that failed. */
    @Test
    fun `a fault can be told to fire partway through a sequence`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("counted.bin")
            val fault = fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".bin", after = 3))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                repeat(3) { channel.write(ByteBuffer.wrap(byteArrayOf(it.toByte()))) }
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(byteArrayOf(3))) }
                // `times` defaults to one, so the disk works again afterwards.
                channel.write(ByteBuffer.wrap(byteArrayOf(4)))
            }
            assertEquals(1, fault.fireCount)
            assertContentEquals(byteArrayOf(0, 1, 2, 4), Files.readAllBytes(root.resolve("counted.bin")))
        }
    }

    @Test
    fun `out of space fires once the budget is gone and does not recover`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("full.bin")
            val fault = fs.arm(Fault.outOfSpace(remainingBytes = 10))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(ByteArray(8)))
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(ByteArray(8))) }
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(ByteArray(1))) }
            }
            assertTrue(fault.fireCount >= 2, "an out-of-space disk stays full")
        }
    }

    @Test
    fun `move, delete and directory creation can each fail on their own`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(root)
            val file = directory.resolve("subject.bin")
            Files.write(file, byteArrayOf(1))

            val move = fs.arm(Fault.onSuffix(FaultOperation.MOVE, ".bin"))
            assertFailsWith<java.io.IOException> {
                Files.move(file, directory.resolve("target.bin"), StandardCopyOption.ATOMIC_MOVE)
            }
            assertEquals(1, move.fireCount)
            assertTrue(Files.exists(root.resolve("subject.bin")), "a failed move leaves the source")

            val delete = fs.arm(Fault.onSuffix(FaultOperation.DELETE, ".bin"))
            assertFailsWith<java.io.IOException> { Files.delete(file) }
            assertEquals(1, delete.fireCount)
            assertTrue(Files.exists(root.resolve("subject.bin")))

            val create = fs.arm(Fault.onName(FaultOperation.CREATE_DIRECTORY, "nested"))
            assertFailsWith<java.io.IOException> { Files.createDirectory(directory.resolve("nested")) }
            assertEquals(1, create.fireCount)
        }
    }

    /** `heal` is how a test gets to "and then the disk was working again", which is the state after. */
    @Test
    fun `healing puts the disk back`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val file = fs.path(root).resolve("healed.bin")
            fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".bin", times = Int.MAX_VALUE))

            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                assertFailsWith<java.io.IOException> { channel.write(ByteBuffer.wrap(byteArrayOf(1))) }
                fs.heal()
                channel.write(ByteBuffer.wrap(byteArrayOf(1, 2)))
            }
            assertContentEquals(byteArrayOf(1, 2), Files.readAllBytes(root.resolve("healed.bin")))
        }
    }
}
