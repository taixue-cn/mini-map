package io.github.minimap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Hash canonical samples, so transport compression never changes cache identity. */
object TileCodec {
    const val MAX_RAW = 65536

    @JvmRecord
    data class Encoded(val hash: ByteArray, val rawLength: Int, val compressed: Boolean, val body: ByteArray)

    @JvmStatic
    @Throws(IOException::class)
    fun encode(tile: TileData): Encoded {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeByte(1)
        repeat(TilePos.PIXELS) { output.writeInt(tile.color(it)) }
        var previous = 0
        repeat(TilePos.PIXELS) { index ->
            val delta = tile.height(index) - previous
            previous = tile.height(index)
            var value = (delta shl 1) xor (delta shr 31)
            do {
                val part = value and 127
                value = value ushr 7
                output.writeByte(part or if (value == 0) 0 else 128)
            } while (value != 0)
        }
        for (i in 0 until TilePos.PIXELS step 2) {
            output.writeByte(tile.light(i).toInt() or (tile.light(i + 1).toInt() shl 4))
        }
        val raw = bytes.toByteArray()
        val packed = ByteArrayOutputStream()
        val compressor = Deflater(3)
        try {
            DeflaterOutputStream(packed, compressor).use { it.write(raw) }
        } finally {
            compressor.end()
        }
        val compressed = packed.size() + 32 < raw.size
        return Encoded(hash(raw), raw.size, compressed, if (compressed) packed.toByteArray() else raw)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(encoded: Encoded): TileData {
        if (encoded.hash.size != 32 || encoded.rawLength !in 1..MAX_RAW || encoded.body.size > MAX_RAW) {
            throw IOException("Invalid tile size")
        }
        val raw = if (encoded.compressed) inflate(encoded) else encoded.body
        if (raw.size != encoded.rawLength || !MessageDigest.isEqual(hash(raw), encoded.hash)) {
            throw IOException("Tile hash mismatch")
        }
        val input = DataInputStream(ByteArrayInputStream(raw))
        if (input.readUnsignedByte() != 1) throw IOException("Unknown tile format")
        val colors = IntArray(TilePos.PIXELS) { input.readInt() }
        var previous = 0
        val heights = IntArray(TilePos.PIXELS) {
            var value = 0
            var shift = 0
            var part: Int
            do {
                if (shift >= 21) throw IOException("Height delta too large")
                part = input.readUnsignedByte()
                value = value or ((part and 127) shl shift)
                shift += 7
            } while (part and 128 != 0)
            previous += (value ushr 1) xor -(value and 1)
            if (previous !in Short.MIN_VALUE..Short.MAX_VALUE) throw IOException("Invalid height")
            previous
        }
        val lights = ByteArray(TilePos.PIXELS)
        for (i in lights.indices step 2) {
            val value = input.readUnsignedByte()
            lights[i] = (value and 15).toByte()
            lights[i + 1] = (value ushr 4).toByte()
        }
        if (input.available() != 0) throw IOException("Trailing tile bytes")
        try {
            return TileData(colors, heights, lights)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid samples", error)
        }
    }

    private fun inflate(encoded: Encoded): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(encoded.body)
            val raw = ByteArray(encoded.rawLength)
            var size = 0
            while (size < raw.size && !inflater.finished()) {
                val read = inflater.inflate(raw, size, raw.size - size)
                if (read == 0) break
                size += read
            }
            if (size != raw.size || !inflater.finished() || inflater.remaining != 0) {
                throw IOException("Invalid compressed tile")
            }
            return raw
        } catch (error: DataFormatException) {
            throw IOException("Corrupt tile", error)
        } finally {
            inflater.end()
        }
    }

    @JvmStatic
    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
