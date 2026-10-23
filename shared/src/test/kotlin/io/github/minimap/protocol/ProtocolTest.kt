package io.github.minimap.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Random
import java.util.UUID

class ProtocolTest {
    @Test
    fun samplesSurviveCompressionIncludingNegativeHeights() {
        val source = TileData(IntArray(4096) { 0xffa0b0c0.toInt() }, IntArray(4096) { it - 2048 }, ByteArray(4096) { (it % 16).toByte() })
        val encoded = TileCodec.encode(source)
        val decoded = TileCodec.decode(encoded)
        assertTrue(encoded.compressed)
        assertTrue(encoded.body.size < encoded.rawLength / 2)
        repeat(4096) {
            assertEquals(source.color(it), decoded.color(it))
            assertEquals(source.height(it), decoded.height(it))
            assertEquals(source.light(it), decoded.light(it))
        }
        encoded.body[3] = (encoded.body[3].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { TileCodec.decode(encoded) }
    }

    @Test
    fun randomSamplesFitWireAndRejectTrailingBytes() {
        val random = Random(1)
        val colors = IntArray(4096) { random.nextInt() or 0xff000000.toInt() }
        val heights = IntArray(4096) { random.nextInt(65536) - 32768 }
        val light = ByteArray(4096) { random.nextInt(16).toByte() }
        val encoded = TileCodec.encode(TileData(colors, heights, light))
        assertTrue(encoded.body.size <= TileCodec.MAX_RAW)
        val decoded = TileCodec.decode(encoded)
        repeat(4096) { assertEquals(heights[it], decoded.height(it)) }
        assertThrows(IOException::class.java) {
            TileCodec.decode(encoded.copy(rawLength = Int.MAX_VALUE, compressed = true, body = byteArrayOf()))
        }
        assertThrows(IOException::class.java) { TileCodec.decode(encoded.copy(body = encoded.body.copyOf(encoded.body.size + 1))) }
    }

    @Test
    fun transparentSamplesHaveCanonicalHash() {
        val first = TileCodec.encode(TileData(IntArray(4096) { 0x112233 }, IntArray(4096) { 40000 }, ByteArray(4096)))
        val second = TileCodec.encode(TileData(IntArray(4096), IntArray(4096), ByteArray(4096)))
        assertArrayEquals(first.hash, second.hash)
    }

    @Test
    fun messagesRejectTruncationTrailingFieldsAndInvalidPositions() {
        val message = Wire.Offer(UUID.randomUUID(), "server", "world", "2", "minecraft:overworld")
        val encoded = Wire.encode(message)
        assertEquals(message, Wire.decode(encoded))
        for (size in encoded.indices) {
            assertThrows(IOException::class.java) { Wire.decode(encoded.copyOf(size)) }
        }
        assertThrows(IOException::class.java) { Wire.decode(encoded.copyOf(encoded.size + 1)) }
        assertThrows(IOException::class.java) { Wire.decode(ByteArray(Wire.MAX_FRAME + 1)) }
        assertThrows(IllegalArgumentException::class.java) { TilePos(Int.MAX_VALUE, 0) }
    }
}
