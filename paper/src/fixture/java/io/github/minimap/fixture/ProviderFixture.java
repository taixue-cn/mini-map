package io.github.minimap.fixture;

import io.github.minimap.api.MapProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Isolated loopback test only. Not included in the released plugin. */
public final class ProviderFixture extends JavaPlugin implements MapProvider {
    private final java.util.concurrent.atomic.AtomicLong firstRead=new java.util.concurrent.atomic.AtomicLong();
    @Override public void onEnable(){getServer().getServicesManager().register(MapProvider.class,this,this,ServicePriority.Highest);getLogger().info("MINIMAP_FIXTURE_READY");}
    @Override public Optional<MapRef> resolve(WorldRef world){return Optional.of(new MapRef(world.uid().toString(),"1"));}
    @Override public Optional<SurfaceTile> read(MapRef map,int x,int z){
        if(x!=10||z!=10)return Optional.empty();
        long now=System.currentTimeMillis();firstRead.compareAndSet(0,now);long age=now-firstRead.get();
        if(Boolean.getBoolean("minimap.fixture.updates")&&age>=18000)return Optional.empty();
        boolean changed=Boolean.getBoolean("minimap.fixture.updates")&&age>=8000;
        int[] color=new int[4096],height=new int[4096];byte[] light=new byte[4096];
        Arrays.fill(color,changed?0xff30b050:0xffe04090);Arrays.fill(height,changed?72:71);Arrays.fill(light,changed?(byte)10:(byte)12);
        return Optional.of(new SurfaceTile(color,height,light));
    }
}
