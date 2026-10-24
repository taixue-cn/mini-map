package io.github.minimap.fixture.mixin;

import io.github.minimap.fabric.MiniMapClient;
import io.github.minimap.fixture.FixturePackets;
import io.github.minimap.protocol.Wire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the actual protocol without replacing networking; excluded from the distributed mod. */
@Mixin(value = MiniMapClient.class, remap = false)
public abstract class PacketProbeMixin {
    @Inject(method = "send", at = @At("HEAD"), remap = false)
    private void observeSend(Wire.Message message, CallbackInfo callback) {
        FixturePackets.capture(message, false);
    }

    @Inject(method = "receive", at = @At("HEAD"), remap = false)
    private void observeReceive(Wire.Message message, CallbackInfo callback) {
        FixturePackets.capture(message, true);
    }
}
