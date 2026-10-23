package io.github.minimap.fabric

import io.github.minimap.protocol.Wire
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

@JvmRecord
data class MapFrame(val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<MapFrame>(Identifier.parse(Wire.CHANNEL))

        @JvmField
        val CODEC: StreamCodec<FriendlyByteBuf, MapFrame> = StreamCodec.of(
            { buffer, frame ->
                require(frame.bytes.size <= Wire.MAX_FRAME) { "Frame too large" }
                buffer.writeBytes(frame.bytes)
            },
            { buffer ->
                val size = buffer.readableBytes()
                require(size <= Wire.MAX_FRAME) { "Frame too large" }
                MapFrame(ByteArray(size).also { buffer.readBytes(it) })
            },
        )
    }
}
