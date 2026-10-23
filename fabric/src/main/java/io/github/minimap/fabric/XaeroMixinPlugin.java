package io.github.minimap.fabric;

import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Do not load optional Xaero classes, or apply version-sensitive mixins, on unsupported installations. */
public final class XaeroMixinPlugin implements IMixinConfigPlugin {
    public static boolean supported(){return FabricLoader.getInstance().getModContainer("xaeroworldmap").map(m->m.getMetadata().getVersion().getFriendlyString().equals("1.47.1")).orElse(false);}
    @Override public boolean shouldApplyMixin(String target,String mixin){return supported();}
    @Override public void onLoad(String pack){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public void acceptTargets(Set<String> own,Set<String> other){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
