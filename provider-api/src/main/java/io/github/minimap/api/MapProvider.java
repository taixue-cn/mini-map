package io.github.minimap.api;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Path;

/** Register an implementation with Bukkit ServicesManager. Never accept client-supplied world identity. */
public interface MapProvider {
    record WorldRef(UUID uid,String name,String dimension,Path saveFolder) {}
    record MapRef(String id,String epoch) {
        public MapRef {
            if(id==null||id.isBlank()||id.length()>128||epoch==null||epoch.isBlank()||epoch.length()>128)
                throw new IllegalArgumentException("Invalid map identity");
        }
    }
    /** Called on the server thread; must be fast and must not perform storage I/O. */
    Optional<MapRef> resolve(WorldRef world);
    /** Called on a bounded worker pool. Return a committed, immutable snapshot, or empty for a deleted/absent tile. */
    Optional<SurfaceTile> read(MapRef map,int tileX,int tileZ)throws IOException;
    /** A tile covers 64x64 blocks; samples use index z * 64 + x. */
    record SurfaceTile(int[] argb,int[] height,byte[] blockLight) {
        public SurfaceTile {
            if(argb.length!=4096||height.length!=4096||blockLight.length!=4096)throw new IllegalArgumentException("Expected 4096 samples");
            argb=argb.clone();height=height.clone();blockLight=blockLight.clone();
        }
        @Override public int[] argb(){return argb.clone();}
        @Override public int[] height(){return height.clone();}
        @Override public byte[] blockLight(){return blockLight.clone();}
    }
}
