package io.github.minimap.paper;

import io.github.minimap.api.MapProvider;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.java.JavaPlugin;

/** Version-isolated adapter to BlueMap Core. No assumptions about file paths or storage backend. */
final class BlueMapProvider implements MapProvider {
    private record Binding(Object storage,int width,int depth) {}
    private final JavaPlugin plugin;
    private final Map<String,Binding> bindings=new ConcurrentHashMap<>();
    BlueMapProvider(JavaPlugin plugin){this.plugin=plugin;}

    @Override public Optional<MapRef> resolve(WorldRef world) {
        var bluemap=plugin.getServer().getPluginManager().getPlugin("BlueMap");
        if(bluemap==null||!bluemap.isEnabled())return Optional.empty();
        try {
            var apiClass=Class.forName("de.bluecolored.bluemap.api.BlueMapAPI",true,bluemap.getClass().getClassLoader());
            var api=(Optional<?>)apiClass.getMethod("getInstance").invoke(null);
            if(api.isEmpty())return Optional.empty();
            String selected=plugin.getConfig().getString("bluemap-maps."+world.name());
            var maps=(Collection<?>)call(api.get(),"getMaps");
            Object match=null;
            for(Object map:maps) {
                String id=(String)call(map,"getId");
                boolean matches=selected!=null ? selected.equals(id) :
                    ((Path)call(call(map,"getWorld"),"getSaveFolder")).toAbsolutePath().normalize().equals(world.saveFolder().toAbsolutePath().normalize());
                if(matches){if(match!=null)return Optional.empty();match=map;}
            }
            if(match==null)return Optional.empty();
            String id=(String)call(match,"getId");Object core=call(match,"map");
            Object grid=call(call(core,"getLowresTileManager"),"getTileGrid");
            Object size=call(grid,"getGridSize");int w=((Number)call(size,"getX")).intValue(),d=((Number)call(size,"getY")).intValue();
            if(w<1||d<1||w>2048||d>2048)throw new IOException("Unsupported BlueMap tile dimensions");
            bindings.put(id,new Binding(call(core,"getStorage"),w,d));
            return Optional.of(new MapRef(id,"1"));
        }catch(ReflectiveOperationException|IOException|RuntimeException e) {
            plugin.getLogger().fine("BlueMap provider unavailable: "+e);return Optional.empty();
        }
    }

    @Override public Optional<SurfaceTile> read(MapRef map,int tileX,int tileZ)throws IOException {
        Binding binding=bindings.get(map.id());if(binding==null)return Optional.empty();
        int[] colors=new int[4096],heights=new int[4096];byte[] lights=new byte[4096];
        int baseX=Math.multiplyExact(tileX,64),baseZ=Math.multiplyExact(tileZ,64);boolean present=false;
        try {
            Object grid=call(binding.storage,"lowresTiles",new Class<?>[]{int.class},1);
            var images=new HashMap<Long,BufferedImage>();
            for(int z=0;z<64;z++)for(int x=0;x<64;x++) {
                int bx=baseX+x,bz=baseZ+z,cx=Math.floorDiv(bx,binding.width),cz=Math.floorDiv(bz,binding.depth);
                long key=((long)cx<<32)|(cz&0xffffffffL);
                if(!images.containsKey(key)) {
                    BufferedImage image=null;
                    Object value=call(grid,"read",new Class<?>[]{int.class,int.class},cx,cz);
                    if(value!=null)try(InputStream raw=(InputStream)value;InputStream decoded=(InputStream)call(value,"decompress")) {
                        image=ImageIO.read(decoded);
                        if(image==null||image.getWidth()!=binding.width+1||image.getHeight()!=(binding.depth+1)*2)
                            throw new IOException("BlueMap storage does not expose standard lowres PNG samples; install a custom MapProvider");
                    }
                    images.put(key,image);
                }
                BufferedImage image=images.get(key);if(image==null)continue;
                int lx=Math.floorMod(bx,binding.width),lz=Math.floorMod(bz,binding.depth),i=z*64+x;
                int argb=image.getRGB(lx,lz);if((argb>>>24)==0)continue;
                int meta=image.getRGB(lx,lz+binding.depth+1);
                colors[i]=argb;int unsigned=meta&65535;
                heights[i]=(short)unsigned;
                lights[i]=(byte)Math.min(15,(meta>>>16)&255);present=true;
            }
        }catch(ReflectiveOperationException e){throw new IOException("Unsupported BlueMap Core API",e);}
        return present?Optional.of(new SurfaceTile(colors,heights,lights)):Optional.empty();
    }
    static Object call(Object object,String name)throws ReflectiveOperationException{return call(object,name,new Class<?>[0]);}
    static Object call(Object object,String name,Class<?>[] types,Object... args)throws ReflectiveOperationException {
        Method method=object.getClass().getMethod(name,types);
        if(!method.canAccess(object))method.trySetAccessible();
        return method.invoke(object,args);
    }
}
