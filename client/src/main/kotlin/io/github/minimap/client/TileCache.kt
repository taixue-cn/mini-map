package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Optional

/** Rebuildable cache with bounded reads and atomic replacement; call from the storage worker. */
class TileCache(private val root: Path, private val maxBytes: Long) {
    @JvmRecord
    data class Entry(val encoded: TileCodec.Encoded, val tile: TileData)

    private var writes = 0

    private fun path(identity: CacheIdentity, pos: TilePos): Path = root.resolve(identity.key()).resolve("${pos.x}_${pos.z}.tile")

    @Throws(IOException::class)
    fun read(identity: CacheIdentity, pos: TilePos): Optional<Entry> {
        val path = path(identity, pos)
        if (!Files.isRegularFile(path)) return Optional.empty()
        try {
            if (Files.size(path) > TileCodec.MAX_RAW + 45) throw IOException("Oversized cache entry")
            DataInputStream(Files.newInputStream(path)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unknown cache format")
                val hash = input.readNBytes(32)
                val rawLength = input.readInt()
                val compressed = input.readBoolean()
                val size = input.readInt()
                if (size !in 1..TileCodec.MAX_RAW) throw IOException("Invalid cache size")
                val body = input.readNBytes(size)
                if (body.size != size || input.read() != -1) throw IOException("Truncated cache entry")
                val encoded = TileCodec.Encoded(hash, rawLength, compressed, body)
                val tile = TileCodec.decode(encoded)
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()))
                return Optional.of(Entry(encoded, tile))
            }
        } catch (_: IOException) {
            Files.deleteIfExists(path)
        } catch (_: RuntimeException) {
            Files.deleteIfExists(path)
        }
        return Optional.empty()
    }

    @Throws(IOException::class)
    fun write(identity: CacheIdentity, pos: TilePos, encoded: TileCodec.Encoded) {
        val path = path(identity, pos)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "tile-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary)).use { output ->
                output.writeInt(MAGIC)
                output.write(encoded.hash)
                output.writeInt(encoded.rawLength)
                output.writeBoolean(encoded.compressed)
                output.writeInt(encoded.body.size)
                output.write(encoded.body)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        if (++writes % 128 == 1) prune()
    }

    @Throws(IOException::class)
    fun delete(identity: CacheIdentity, pos: TilePos) {
        Files.deleteIfExists(path(identity, pos))
    }

    @Throws(IOException::class)
    fun prune() {
        if (!Files.isDirectory(root)) return
        data class FileInfo(val path: Path, val size: Long, val time: Long)
        val files = Files.walk(root).use { walk ->
            walk.filter { it.toString().endsWith(".tile") }.map { path ->
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                FileInfo(path, attributes.size(), attributes.lastModifiedTime().toMillis())
            }.toList().sortedBy { it.time }
        }
        var bytes = files.sumOf { it.size }
        for (file in files) {
            if (bytes <= maxBytes) break
            Files.deleteIfExists(file.path)
            bytes -= file.size
        }
    }

    companion object {
        private const val MAGIC = 0x4d4d4301
    }
}
