package io.github.minimap.fabric.mixin;

import io.github.minimap.fabric.ServerTileImportKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.region.MapTileChunk;

/** Cache display textures, while keeping imported placeholder blocks out of native terrain saves. */
@Mixin(value = MapTileChunk.class, remap = false)
abstract class MapTileChunkMixin {
    @Inject(method = "includeInSave", at = @At("RETURN"), cancellable = true)
    private void minimap$saveNativeOnly(CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && ServerTileImportKt.hasServerSamples((MapTileChunk) (Object) this)) {
            callback.setReturnValue(false);
        }
    }
}
