package io.github.minimap.fixture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class FixtureBootstrap implements ClientModInitializer {
    @Override public void onInitializeClient(){
        if(!FabricLoader.getInstance().isModLoaded("xaeroworldmap")){new FallbackFixture().onInitializeClient();return;}
        try{((ClientModInitializer)Class.forName("io.github.minimap.fixture.ClientFixture").getDeclaredConstructor().newInstance()).onInitializeClient();}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
}
