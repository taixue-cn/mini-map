package io.github.minimap.fixture;

import io.github.minimap.api.MapProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Isolated loopback test only. Not included in the released plugin. */
public final class ProviderFixture extends JavaPlugin implements MapProvider {
    @Override public void onEnable(){getServer().getServicesManager().register(MapProvider.class,this,this,ServicePriority.Highest);getLogger().info("MINIMAP_FIXTURE_READY");}
    @Override public Optional<MapRef> resolve(WorldRef world){return Optional.of(new MapRef(world.uid().toString(),"1"));}
    @Override public Optional<SurfaceTile> read(MapRef map,int x,int z){
        if(x!=10||z!=10)return Optional.empty();
        int[] color=new int[4096],height=new int[4096];byte[] light=new byte[4096];
        Arrays.fill(color,0xffe04090);Arrays.fill(height,71);Arrays.fill(light,(byte)12);
        return Optional.of(new SurfaceTile(color,height,light));
    }
}
