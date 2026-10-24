package io.github.minimap.fabric

import io.github.minimap.protocol.TileData
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
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
    private val owner = MiniMapClient.identity()
    var serverOwned = true
        private set

    init {
        val block = if (source.color(index) ushr 24 == 0) Blocks.AIR else Blocks.STONE
        super.write(block.defaultBlockState(), source.height(index), source.height(index), Biomes.PLAINS, source.light(index), false, false)
    }

    override fun write(
        state: BlockState,
        height: Int,
        topHeight: Int,
        biome: ResourceKey<Biome>?,
        light: Byte,
        glowing: Boolean,
        cave: Boolean,
    ) {
        // Keep the server snapshot authoritative while its optional handshake is active.
        if (serverOwned && owner != null && owner == MiniMapClient.identity()) return
        super.write(state, height, topHeight, biome, light, glowing, cave)
        // Without a server session, Xaero resumes ordinary local exploration.
        serverOwned = false
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
        if (!serverOwned) {
            super.getPixelColour(
                result, textures, level, dimension, blocks, chunk, north, east, other, tile, x, z,
                caveStart, caveDepth, position, biomes, dimensions, brightness, sky, ambient, tints,
                processor, overlays, globalX, globalZ, shapes, config,
            )
            return
        }
        val color = source.color(index)
        result[0] = (color ushr 16) and 255
        result[1] = (color ushr 8) and 255
        result[2] = color and 255
        result[3] = color ushr 24
    }
}
