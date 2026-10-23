package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID

class CacheTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun cacheReusesIdentityAndRejectsCorruption() {
        val cache = TileCache(root, 100000)
        val identity = CacheIdentity("../server", "a/b", "1")
        val pos = TilePos(-1, 3)
        val encoded = TileCodec.encode(TileData(IntArray(4096) { 0xff33ee11.toInt() }, IntArray(4096), ByteArray(4096)))
        cache.write(identity, pos, encoded)
        assertArrayEquals(encoded.hash, cache.read(CacheIdentity("../server", "a/b", "1"), pos).orElseThrow().encoded.hash)
        assertTrue(cache.read(CacheIdentity("../server", "a/b", "2"), pos).isEmpty)
        Files.walk(root).use { files ->
            val file = files.filter { it.toString().endsWith(".tile") }.findFirst().orElseThrow()
            Files.write(file, byteArrayOf(0, 1))
        }
        assertTrue(cache.read(identity, pos).isEmpty)
        assertNotEquals(CacheIdentity("a", "bc", "d").key(), CacheIdentity("ab", "c", "d").key())
    }

    @Test
    fun cacheEvictsOldestEntries() {
        val cache = TileCache(root, 1)
        val identity = CacheIdentity("s", "w", "e")
        cache.write(identity, TilePos(0, 0), TileCodec.encode(TileData(IntArray(4096), IntArray(4096), ByteArray(4096))))
        assertTrue(cache.read(identity, TilePos(0, 0)).isEmpty)
    }

    @Test
    fun assemblerAcceptsReorderingAndRejectsMixedMetadata() {
        val session = UUID.randomUUID()
        val pos = TilePos(0, 0)
        val body = ByteArray(20000).also { Random(5).nextBytes(it) }
        val last = Wire.Part(session, 1, pos, ByteArray(32), 30000, true, 20000, 1, 2, body.copyOfRange(16384, 20000))
        val first = last.copy(index = 0, bytes = body.copyOfRange(0, 16384))
        val assembler = TileAssembly()
        assertTrue(assembler.add(last).isEmpty)
        assertTrue(assembler.add(last).isEmpty)
        assertArrayEquals(body, assembler.add(first).orElseThrow().body)
        val mixed = TileAssembly()
        mixed.add(last)
        assertThrows(IOException::class.java) { mixed.add(first.copy(rawLength = 30001)) }
        assertThrows(IOException::class.java) { TileAssembly().add(first.copy(totalLength = Int.MAX_VALUE)) }
    }
}
