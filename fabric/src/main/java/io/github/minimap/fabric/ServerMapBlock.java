package io.github.minimap.fabric;

import io.github.minimap.protocol.TileData;
import xaero.lib.client.level.block.BlockTextureColorUtils;
import xaero.map.*;
import xaero.map.biome.BlockTintProvider;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.*;
import xaero.map.world.MapDimension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.dimension.DimensionType;

/** The server supplies rendered color, not an invented approximation using vanilla block colors. */
final class ServerMapBlock extends MapBlock {
    final TileData source;
    private final int index;
    ServerMapBlock(TileData source,int index){
        this.source=source;this.index=index;
        write(((source.color(index)>>>24)==0?Blocks.AIR:Blocks.STONE).defaultBlockState(),source.height(index),source.light(index),Biomes.PLAINS,(byte)0,false,false);
    }
    @Override public void getPixelColour(int[] result,BlockTextureColorUtils textures,ClientLevel level,MapDimension dimension,
        Registry<Block> blocks,MapTileChunk chunk,MapTileChunk north,MapTileChunk east,MapTileChunk other,MapTile tile,
        int x,int z,int caveStart,int caveDepth,BlockPos.MutableBlockPos position,Registry<Biome> biomes,Registry<DimensionType> dimensions,
        float brightness,float sky,float ambient,BlockTintProvider tints,MapProcessor processor,OverlayManager overlays,int globalX,int globalZ,
        BlockStateShortShapeCache shapes,MapUpdateFastConfig config){
        int color=source.color(index);result[0]=(color>>>16)&255;result[1]=(color>>>8)&255;result[2]=color&255;result[3]=color>>>24;
    }
}
