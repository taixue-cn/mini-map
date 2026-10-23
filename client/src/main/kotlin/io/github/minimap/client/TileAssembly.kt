package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Optional

/** An assembly belongs to an authorized request; never allocate from unchecked peer lengths. */
class TileAssembly {
    private var first: Wire.Part? = null
    private var parts = emptyArray<ByteArray?>()
    private var received = 0

    @Throws(IOException::class)
    fun add(part: Wire.Part): Optional<TileCodec.Encoded> {
        if (part.hash.size != 32 || part.rawLength !in 1..TileCodec.MAX_RAW || part.totalLength !in 1..TileCodec.MAX_RAW) {
            throw IOException("Invalid fragment")
        }
        val count = (part.totalLength + Wire.FRAGMENT - 1) / Wire.FRAGMENT
        if (part.count != count || count > Wire.MAX_PARTS || part.index !in 0 until count ||
            part.bytes.size != minOf(Wire.FRAGMENT, part.totalLength - part.index * Wire.FRAGMENT)
        ) {
            throw IOException("Invalid fragment")
        }
        val start = first ?: part.also {
            first = it
            parts = arrayOfNulls(count)
        }
        if (part.session != start.session || part.requestId != start.requestId || part.pos != start.pos ||
            !part.hash.contentEquals(start.hash) || part.rawLength != start.rawLength || part.compressed != start.compressed ||
            part.totalLength != start.totalLength || part.count != start.count
        ) {
            throw IOException("Mixed fragments")
        }
        val previous = parts[part.index]
        if (previous != null) {
            if (!previous.contentEquals(part.bytes)) throw IOException("Conflicting fragment")
            return Optional.empty()
        }
        parts[part.index] = part.bytes.clone()
        received++
        if (received != count) return Optional.empty()
        val buffer = ByteArrayOutputStream(start.totalLength)
        parts.forEach { buffer.write(requireNotNull(it)) }
        return Optional.of(TileCodec.Encoded(start.hash, start.rawLength, start.compressed, buffer.toByteArray()))
    }
}
