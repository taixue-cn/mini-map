package io.github.minimap.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.github.minimap.fabric.MiniMapClient;
import xaero.map.world.MapWorld;

@Mixin(value=MapWorld.class,remap=false)
abstract class MapWorldMixin {
    @Inject(method="getMainId",at=@At("HEAD"),cancellable=true)
    private void minimap$identity(CallbackInfoReturnable<String> callback){
        var identity=MiniMapClient.identity();if(identity!=null)callback.setReturnValue(identity.serverFolder());
    }
}
