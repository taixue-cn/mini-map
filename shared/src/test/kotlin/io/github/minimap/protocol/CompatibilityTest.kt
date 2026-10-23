package io.github.minimap.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.HexFormat
import java.util.UUID

/** Golden bytes were produced by the released Java v1 codec, independently of this Kotlin implementation. */
class CompatibilityTest {
    @Test
    fun allMessagesKeepTheirReleasedWireRepresentation() {
        val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val pos = TilePos(-2, 3)
        val messages = listOf(
            Wire.Hello(1),
            Wire.Offer(id, "server", "world", "epoch", "minecraft:overworld"),
            Wire.Open(id, true),
            Wire.Request(id, 7, pos, byteArrayOf(1, 2)),
            Wire.Status(id, 7, pos, Wire.Result.MISSING),
            Wire.Part(id, 7, pos, ByteArray(32), 200, true, 2, 0, 1, byteArrayOf(42, 43)),
            Wire.Ack(id, 7),
            Wire.Revoke(id),
        )
        val expected = listOf(
            "4d4d00010000000001",
            "4d4d0001010123456789abcdef0123456789abcdef0000000673657276657200000005776f726c640000000565" +
                "706f6368000000136d696e6563726166743a6f766572776f726c64",
            "4d4d0001020123456789abcdef0123456789abcdef01",
            "4d4d0001030123456789abcdef0123456789abcdef00000007fffffffe00000003000000020102",
            "4d4d0001040123456789abcdef0123456789abcdef00000007fffffffe0000000301",
            "4d4d0001050123456789abcdef0123456789abcdef00000007fffffffe00000003000000200000000000000000" +
                "000000000000000000000000000000000000000000000000000000c80100000002000000000000000100000002" +
                "2a2b",
            "4d4d0001060123456789abcdef0123456789abcdef00000007",
            "4d4d0001070123456789abcdef0123456789abcdef",
        )
        messages.zip(expected).forEach { (message, hex) ->
            assertEquals(hex, HexFormat.of().formatHex(Wire.encode(message)))
            assertEquals(hex, HexFormat.of().formatHex(Wire.encode(Wire.decode(HexFormat.of().parseHex(hex)))))
        }
    }

    @Test
    fun canonicalSampleHashKeepsExistingDiskCachesValid() {
        val tile = TileData(
            IntArray(4096) { 0xff123456.toInt() },
            IntArray(4096) { it % 64 - 32 },
            ByteArray(4096) { (it % 16).toByte() },
        )
        assertEquals(
            "15c104097a76ddd232eac22d6d630c923f87ccdbfcab3bf2aa03d5975b997bb8",
            HexFormat.of().formatHex(TileCodec.encode(tile).hash),
        )
    }
}
