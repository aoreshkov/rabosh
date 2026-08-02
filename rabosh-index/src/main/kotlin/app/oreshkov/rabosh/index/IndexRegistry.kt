package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import java.io.IOException
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** What the registry file holds: the defined indexes, and the counter that names the next one. */
internal class RegistryContents(
    /** The id the next [IndexHandle] will be given. Persisted so a dropped id is never reused. */
    val nextIndexId: Int,
    /** The defined indexes, ascending by id. */
    val indexes: List<IndexHandle>,
) {
    fun with(handle: IndexHandle): RegistryContents =
        RegistryContents(maxOf(nextIndexId, handle.id + 1), (indexes + handle).sortedBy { it.id })

    fun without(id: Int): RegistryContents =
        RegistryContents(nextIndexId, indexes.filterNot { it.id == id })

    companion object {
        val EMPTY: RegistryContents = RegistryContents(nextIndexId = 1, indexes = emptyList())
    }
}

/**
 * The `INDEXES` file: which indexes this store has, and what the next one will be called.
 *
 * **This is the one thing in the indexing layer that is not derived data, and its durability rule is
 * the opposite of everything around it.** A `.idx` or a `.pst` lost to a power cut costs a rescan,
 * which is why neither is forced before the manifest names its segment — the relaxation the catalog's
 * sidecars already take, on the stated grounds that a sketch is derived and a document is not. An
 * index *definition* is neither. It is an instruction somebody gave, recorded nowhere else, and
 * losing it means the store silently stops having the index an operator created and nothing ever says
 * so. So this file gets the treatment `CURRENT` gets: written whole under a temporary name, forced,
 * moved into place atomically, and the directory forced after it.
 *
 * The cost of being wrong in the other direction is bounded, which is what makes the asymmetry safe
 * to state so strongly: each `.pst` repeats the definition it was built for, so a registry that were
 * somehow lost entirely could be reconstructed by listing the directory. The registry is the fast
 * path and the authority, not the only copy.
 */
internal object IndexRegistry {
    /** The registry, or `null` if this store has never defined an index. */
    fun read(directory: Path): RegistryContents? {
        val path = directory.resolve(registryFileName())
        val bytes = try {
            Files.readAllBytes(path)
        } catch (missing: NoSuchFileException) {
            return null
        }
        return decode(bytes, REGISTRY_FILE_NAME)
    }

    /**
     * Replaces the registry.
     *
     * The temporary file is forced before the move, so the name only ever appears over bytes that are
     * already on the medium; the directory is forced after it, so the name itself is durable. A crash
     * at any point leaves either the previous registry or the new one, never a mixture.
     */
    fun write(directory: Path, contents: RegistryContents) {
        val bytes = encode(contents)
        val temporary = directory.resolve(temporaryRegistryFileName())
        val target = directory.resolve(registryFileName())
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("index registry write made no progress")
            }
            channel.force(true)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
    }

    fun delete(directory: Path) {
        Files.deleteIfExists(directory.resolve(registryFileName()))
        Files.deleteIfExists(directory.resolve(temporaryRegistryFileName()))
    }

    fun encode(contents: RegistryContents): ByteArray {
        val payload = IndexWriter(256)
        payload.writeU32(contents.nextIndexId)
        payload.writeU32(contents.indexes.size)
        for (handle in contents.indexes) {
            payload.writeU32(handle.id)
            payload.writeByte(IndexFormat.indexKindId(handle.kind))
            payload.pad(3)
            payload.writeString(handle.path.toString())
            payload.writeLong(handle.createdAtSequence)
        }
        val body = payload.toByteArray()

        val file = IndexWriter(IndexFormat.REGISTRY_HEADER_BYTES + body.size)
        file.write(IndexFormat.REGISTRY_MAGIC)
        file.writeU32(IndexFormat.REGISTRY_VERSION)
        file.writeU32(body.size)
        // The checksum covers the version and the length as well as the payload: the field that says
        // how much to read must not be the one left unprotected.
        file.writeU32(IndexFormat.checksum(file.toByteArray(), IndexFormat.MAGIC_BYTES, 8, body))
        file.write(body)
        return file.toByteArray()
    }

    fun decode(bytes: ByteArray, file: String): RegistryContents {
        if (bytes.size < IndexFormat.REGISTRY_HEADER_BYTES) {
            throw CorruptIndexException(
                "the index registry is ${bytes.size} bytes, too short for a ${IndexFormat.REGISTRY_HEADER_BYTES}-byte header",
                file,
            )
        }
        for (index in 0 until IndexFormat.MAGIC_BYTES) {
            if (bytes[index] != IndexFormat.REGISTRY_MAGIC[index]) {
                throw CorruptIndexException("the index registry does not begin with JKDB-IXR", file, index.toLong())
            }
        }

        val reader = IndexBytes(
            MemorySegment.ofArray(bytes),
            0,
            bytes.size,
            file,
            ::CorruptIndexException,
        )
        val version = reader.u32(8, "registry version", Int.MAX_VALUE)
        if (version != IndexFormat.REGISTRY_VERSION) {
            throw UnsupportedIndexFormatException(
                "the index registry in $file is version $version; this build reads ${IndexFormat.REGISTRY_VERSION}",
            )
        }
        val length = reader.u32(12, "registry payload length", IndexFormat.MAX_REGISTRY_BYTES)
        if (IndexFormat.REGISTRY_HEADER_BYTES + length != bytes.size) {
            reader.corrupt(
                "the index registry claims $length payload byte(s) but the file holds " +
                    "${bytes.size - IndexFormat.REGISTRY_HEADER_BYTES}",
                12,
            )
        }
        val expected = reader.i32(16, "registry checksum")
        val actual = IndexFormat.checksum(
            bytes,
            IndexFormat.MAGIC_BYTES,
            8,
            IndexFormat.REGISTRY_HEADER_BYTES,
            length,
        )
        if (expected != actual) reader.corrupt("the index registry's checksum does not match its contents", 16)

        var at = IndexFormat.REGISTRY_HEADER_BYTES
        val nextIndexId = reader.u32(at, "nextIndexId", Int.MAX_VALUE)
        at += 4
        val count = reader.u32(at, "index count", IndexFormat.MAX_INDEXES)
        at += 4

        val indexes = ArrayList<IndexHandle>(count)
        val seen = HashSet<Int>(count)
        repeat(count) {
            val id = reader.u32(at, "index id", Int.MAX_VALUE)
            at += 4
            val kindId = reader.u8(at, "index kind")
            at += 4 // one kind byte plus three reserved
            val kind = IndexFormat.indexKindOfId(kindId)
                ?: throw UnsupportedIndexFormatException(
                    "the index registry in $file names index kind $kindId, which this build does not know",
                )
            val pathLength = reader.u32(at, "index path length", bytes.size - at - 4)
            at += 4
            val path = try {
                reader.bytes(at, pathLength, "index path").decodeToString(throwOnInvalidSequence = true)
            } catch (failure: java.nio.charset.CharacterCodingException) {
                reader.corrupt("an index path is not valid UTF-8", at, failure)
            }
            at += pathLength
            val createdAt = reader.i64(at, "index creation sequence")
            at += 8

            if (id >= nextIndexId) {
                reader.corrupt("index #$id is at or above the next id $nextIndexId, so an id could be reused")
            }
            if (!seen.add(id)) reader.corrupt("the index registry names index #$id twice")
            val parsed = try {
                CatalogPath.parse(path)
            } catch (failure: IllegalArgumentException) {
                reader.corrupt("index #$id has an unreadable path '$path'", cause = failure)
            }
            indexes += IndexHandle(id, IndexDefinition(parsed, kind), createdAt)
        }
        if (at != bytes.size) reader.corrupt("the index registry has trailing bytes after its last index", at)
        return RegistryContents(nextIndexId, indexes.sortedBy { it.id })
    }
}

/**
 * Forces [directory]'s own entries to stable storage.
 *
 * Creating a file and forcing its contents does not make the *name* durable. Windows cannot open a
 * directory as a channel and does not need to, so the failure is expected there and ignored — but
 * **only** there, because elsewhere a directory `fsync` that fails is a durability failure, and
 * swallowing it everywhere would quietly remove the guarantee it exists to provide. The same shape,
 * and the same reasoning, as the core's own.
 */
private fun syncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (failure: IOException) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) throw failure
    }
}
