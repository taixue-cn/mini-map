package io.github.minimap.fabric;

import io.github.minimap.protocol.Wire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MapFrame(byte[] bytes) implements CustomPacketPayload {
    public static final Type<MapFrame> TYPE=new Type<>(Identifier.parse(Wire.CHANNEL));
    public static final StreamCodec<FriendlyByteBuf,MapFrame> CODEC=StreamCodec.of(
        (buffer,frame)->{if(frame.bytes.length>Wire.MAX_FRAME)throw new IllegalArgumentException("Frame too large");buffer.writeBytes(frame.bytes);},
        buffer->{int size=buffer.readableBytes();if(size>Wire.MAX_FRAME)throw new IllegalArgumentException("Frame too large");byte[] bytes=new byte[size];buffer.readBytes(bytes);return new MapFrame(bytes);});
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
