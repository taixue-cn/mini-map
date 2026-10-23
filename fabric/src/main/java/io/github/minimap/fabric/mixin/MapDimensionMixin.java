package io.github.minimap.fabric.mixin;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.github.minimap.fabric.MiniMapClient;
import xaero.map.world.MapDimension;

@Mixin(value=MapDimension.class,remap=false)
abstract class MapDimensionMixin {
    @Inject(method="getFutureMultiworldUnsynced",at=@At("HEAD"),cancellable=true)
    private void minimap$world(CallbackInfoReturnable<String> callback){
        var identity=MiniMapClient.identity();
        if(identity!=null&&MiniMapClient.isCurrentDimension(((MapDimension)(Object)this).getDimId()))callback.setReturnValue(identity.worldFolder());
    }
}
