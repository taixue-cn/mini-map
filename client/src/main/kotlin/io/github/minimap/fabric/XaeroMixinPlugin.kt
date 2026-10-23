package io.github.minimap.fabric

import net.fabricmc.loader.api.FabricLoader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo

/** Gate version-sensitive mixins before any optional Xaero class is loaded. */
class XaeroMixinPlugin : IMixinConfigPlugin {
    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean = supported()
    override fun onLoad(mixinPackage: String) = Unit
    override fun getRefMapperConfig(): String? = null
    override fun acceptTargets(myTargets: MutableSet<String>, otherTargets: MutableSet<String>) = Unit
    override fun getMixins(): List<String>? = null
    override fun preApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit
    override fun postApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit

    companion object {
        @JvmStatic
        fun supported(): Boolean = FabricLoader.getInstance().getModContainer("xaeroworldmap")
            .map { it.metadata.version.friendlyString == "1.47.1" }.orElse(false)
    }
}
