package io.github.minimap.fabric.mixin;

import io.github.minimap.fabric.MiniMapClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.BranchLeveledRegion;

/** Xaero must not rebuild committed overview textures from empty local child regions. */
@Mixin(value = BranchLeveledRegion.class, remap = false)
public abstract class BranchRegionMixin {
    @Inject(method = "checkForUpdates", at = @At("HEAD"), cancellable = true)
    private void minimap$keepCommittedOverview(CallbackInfo ci) {
        if (MiniMapClient.ownsCoarseRegion((BranchLeveledRegion) (Object) this)) ci.cancel();
    }
}
