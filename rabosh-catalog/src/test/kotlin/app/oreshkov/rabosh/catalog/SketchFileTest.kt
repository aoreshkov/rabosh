package app.oreshkov.rabosh.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The `.cat` sidecar.
 *
 * The truncation sweep is the instrument phases 3 and 4 used on the log and the manifest, pointed at
 * this format: damage at **every** offset must be reported rather than returning a sketch that is
 * partly right. A partly-right sketch is worse than none — nothing downstream could tell it apart
 * from a genuine model of a store that happens to hold less data.
 */
class SketchFileTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a sketch survives a roundtrip through a file`() {
        val directory = scratch(root, "roundtrip").also { Files.createDirectories(it) }
        val sketch = sketchOf(
            """{"name":"ada","age":36,"tags":["x","y"],"note":null}""",
            """{"name":"grace","age":45,"tags":["z"],"score":1.5}""",
        )

        SketchFile.write(directory, 7, sketch)
        assertEquals(sketch, SketchFile.read(directory, 7))
    }

    @Test
    fun `an absent sidecar is absent, not empty`() {
        val directory = scratch(root, "absent").also { Files.createDirectories(it) }
        // The distinction the whole coverage report rests on: a segment nobody sketched must not
        // read back as a segment that turned out to hold nothing.
        assertNull(SketchFile.read(directory, 1))
    }

    @Test
    fun `an empty sketch roundtrips`() {
        val directory = scratch(root, "empty").also { Files.createDirectories(it) }
        SketchFile.write(directory, 1, SegmentSketch.EMPTY)
        assertEquals(SegmentSketch.EMPTY, SketchFile.read(directory, 1))
    }

    @Test
    fun `a dense estimator roundtrips`() {
        val directory = scratch(root, "dense").also { Files.createDirectories(it) }
        val builder = SegmentSketchBuilder(CatalogOptions.DEFAULT)
        for (index in 0 until 5_000) builder.add(jsonDocument("""{"id":$index}"""))
        val sketch = builder.build()
        assertTrue(!sketch[catalogPathOf("id")]!!.distinctIsExact, "the estimator went dense")

        SketchFile.write(directory, 3, sketch)
        assertEquals(sketch, SketchFile.read(directory, 3))
    }

    @Test
    fun `truncation at every offset is reported`() {
        val directory = scratch(root, "truncate").also { Files.createDirectories(it) }
        val sketch = sketchOf("""{"a":1,"b":"two","c":[true,false]}""")
        SketchFile.write(directory, 5, sketch)

        val path = directory.resolve(sketchFileName(5))
        val complete = Files.readAllBytes(path)
        assertEquals(sketch, SketchFile.decode(complete, "test.cat", 5), "the undamaged file reads")

        for (limit in 0 until complete.size) {
            val damaged = complete.copyOfRange(0, limit)
            val failure = runCatching { SketchFile.decode(damaged, "test.cat", 5) }.exceptionOrNull()
            assertTrue(
                failure is CatalogException,
                "truncating to $limit byte(s) should be reported, got ${failure ?: "a successful read"}",
            )
        }
    }

    @Test
    fun `a flipped bit anywhere is reported`() {
        val directory = scratch(root, "flip").also { Files.createDirectories(it) }
        SketchFile.write(directory, 9, sketchOf("""{"a":1,"b":"two"}"""))
        val complete = Files.readAllBytes(directory.resolve(sketchFileName(9)))

        for (offset in complete.indices) {
            val damaged = complete.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 1).toByte()
            assertFailsWith<CatalogException>("a flipped bit at $offset was not reported") {
                SketchFile.decode(damaged, "test.cat", 9)
            }
        }
    }

    @Test
    fun `a newer format version is not reported as damage`() {
        val directory = scratch(root, "version").also { Files.createDirectories(it) }
        SketchFile.write(directory, 2, sketchOf("""{"a":1}"""))
        val path = directory.resolve(sketchFileName(2))
        val bytes = Files.readAllBytes(path)
        bytes[8] = (SketchFormat.VERSION + 1).toByte()

        // Deliberately distinct from corruption: the bytes are fine and this build is too old.
        // Reporting it as damage would send somebody looking for a disk fault.
        assertFailsWith<UnsupportedSketchFormatException> { SketchFile.decode(bytes, "test.cat", null) }
    }

    @Test
    fun `a sidecar filed under the wrong number is reported`() {
        val directory = scratch(root, "misfiled").also { Files.createDirectories(it) }
        SketchFile.write(directory, 4, sketchOf("""{"a":1}"""))
        Files.move(directory.resolve(sketchFileName(4)), directory.resolve(sketchFileName(6)))

        // A sidecar copied or renamed into place would otherwise be folded in as if it described a
        // segment it has never seen.
        assertFailsWith<CorruptSketchException> { SketchFile.read(directory, 6) }
    }

    @Test
    fun `a partly written temporary file is never read`() {
        val directory = scratch(root, "temporary").also { Files.createDirectories(it) }
        Files.write(directory.resolve(temporarySketchFileName(11)), ByteArray(3))
        // Written under a temporary name and moved atomically, so a file at the real name is
        // complete by construction. There is no torn-tail reading of one.
        assertNull(SketchFile.read(directory, 11))
        assertNull(sketchSegmentNumber(temporarySketchFileName(11)))
    }

    @Test
    fun `writing over an existing sidecar replaces it`() {
        val directory = scratch(root, "replace").also { Files.createDirectories(it) }
        SketchFile.write(directory, 1, sketchOf("""{"a":1}"""))
        val second = sketchOf("""{"b":2}""", """{"b":3}""")
        SketchFile.write(directory, 1, second)
        assertEquals(second, SketchFile.read(directory, 1))
    }

    private fun sketchOf(vararg documents: String): SegmentSketch {
        val builder = SegmentSketchBuilder(CatalogOptions.DEFAULT)
        for (json in documents) builder.add(jsonDocument(json))
        return builder.build()
    }
}
