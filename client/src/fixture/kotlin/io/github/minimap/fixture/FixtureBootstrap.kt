package io.github.minimap.fixture

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader

/** Keep the no-Xaero test free of Xaero class references during class loading. */
class FixtureBootstrap : ClientModInitializer {
    override fun onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
            FallbackFixture().onInitializeClient()
            return
        }
        val name = if (System.getenv("MINIMAP_TEST_MODE") == "lifecycle") "LifecycleFixture" else "ClientFixture"
        val fixture = Class.forName("io.github.minimap.fixture.$name").getDeclaredConstructor().newInstance()
        (fixture as ClientModInitializer).onInitializeClient()
    }
}
