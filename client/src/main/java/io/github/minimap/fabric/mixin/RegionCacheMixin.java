package io.github.minimap.fabric.mixin;

import io.github.minimap.fabric.MiniMapClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.LeveledRegion;

/** Server tiles persist in the checked portable cache, avoiding native cache writes during GPU updates. */
@Mixin(value = LeveledRegion.class, remap = false)
public abstract class RegionCacheMixin {
    @Inject(method = {"setShouldCache", "setRecacheHasBeenRequested"}, at = @At("HEAD"), cancellable = true)
    private void minimap$usePortableCache(boolean requested, String reason, CallbackInfo ci) {
        if (requested && MiniMapClient.ownsServerRegion(this)) ci.cancel();
    }
}
