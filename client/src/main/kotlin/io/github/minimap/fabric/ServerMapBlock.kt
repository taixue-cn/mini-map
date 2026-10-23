package io.github.minimap.fabric

import io.github.minimap.protocol.TileData
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.dimension.DimensionType
import xaero.lib.client.level.block.BlockTextureColorUtils
import xaero.map.MapProcessor
import xaero.map.biome.BlockTintProvider
import xaero.map.cache.BlockStateShortShapeCache
import xaero.map.region.MapBlock
import xaero.map.region.MapTile
import xaero.map.region.MapTileChunk
import xaero.map.region.MapUpdateFastConfig
import xaero.map.region.OverlayManager
import xaero.map.world.MapDimension

/** Render the supplied color directly; vanilla block colors cannot reproduce the server's resource pack. */
internal class ServerMapBlock(val source: TileData, private val index: Int) : MapBlock() {
    init {
        val block = if (source.color(index) ushr 24 == 0) Blocks.AIR else Blocks.STONE
        write(block.defaultBlockState(), source.height(index), source.light(index).toInt(), Biomes.PLAINS, 0, false, false)
    }

    override fun getPixelColour(
        result: IntArray,
        textures: BlockTextureColorUtils,
        level: ClientLevel,
        dimension: MapDimension,
        blocks: Registry<Block>,
        chunk: MapTileChunk,
        north: MapTileChunk?,
        east: MapTileChunk?,
        other: MapTileChunk?,
        tile: MapTile,
        x: Int,
        z: Int,
        caveStart: Int,
        caveDepth: Int,
        position: BlockPos.MutableBlockPos,
        biomes: Registry<Biome>,
        dimensions: Registry<DimensionType>,
        brightness: Float,
        sky: Float,
        ambient: Float,
        tints: BlockTintProvider,
        processor: MapProcessor,
        overlays: OverlayManager,
        globalX: Int,
        globalZ: Int,
        shapes: BlockStateShortShapeCache,
        config: MapUpdateFastConfig,
    ) {
        val color = source.color(index)
        result[0] = (color ushr 16) and 255
        result[1] = (color ushr 8) and 255
        result[2] = color and 255
        result[3] = color ushr 24
    }
}
