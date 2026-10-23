package io.github.minimap.server

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TransportTest {
    @Test
    fun fragmentationStaysBelowPaperLimit() {
        val data = TileCodec.Encoded(ByteArray(32), 65536, false, ByteArray(65536))
        val parts = TileParts.split(UUID.randomUUID(), 1, TilePos(-1, 5), data)
        assertEquals(4, parts.size)
        for (part in parts) {
            assertTrue(Wire.encode(part).size < Wire.MAX_FRAME)
            assertInstanceOf(Wire.Part::class.java, Wire.decode(Wire.encode(part)))
        }
    }

    @Test
    fun bandwidthIsBoundedAndRefillsByElapsedTime() {
        val bucket = TokenBucket(100.0, 100.0, 0)
        assertTrue(bucket.take(100, 0))
        assertFalse(bucket.take(1, 0))
        assertTrue(bucket.take(50, 500_000_000))
        assertFalse(bucket.take(1, 500_000_000))
        assertTrue(bucket.take(100, 10_000_000_000))
        assertFalse(bucket.take(-1, 10_000_000_000))
        assertThrows(IllegalArgumentException::class.java) { TokenBucket(Double.NaN, 100.0, 0) }
    }
}
