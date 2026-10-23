package io.github.minimap.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.GuiMap;

@Mixin(value=GuiMap.class,remap=false)
public interface GuiMapAccess {
    @Accessor("cameraX") double minimap$cameraX();
    @Accessor("cameraZ") double minimap$cameraZ();
    @Accessor("scale") double minimap$scale();
    @Accessor("screenScale") double minimap$screenScale();
}
