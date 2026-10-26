package io.github.minimap.fabric

import io.github.minimap.client.CacheIdentity
import io.github.minimap.client.ViewportTiles
import io.github.minimap.fabric.mixin.GuiMapAccess
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import net.minecraft.client.Minecraft
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import xaero.map.region.BranchLeveledRegion
import xaero.map.region.MapTile
import xaero.map.region.MapTileChunk
import xaero.map.region.MapUpdateFastConfig

internal class XaeroBridge : MiniMapClient.Bridge {
    private val client = Minecraft.getInstance()
    private var owner: CacheIdentity? = null
    private val imported = mutableSetOf<TilePos>()

    private fun processor(): MapProcessor? = WorldMapSession.getCurrentSession()?.mapProcessor

    private fun current(processor: MapProcessor?): Boolean {
        val identity = MiniMapClient.identity() ?: return false
        return processor != null && processor.isMapWorldUsable && processor.currentCaveLayer == Int.MAX_VALUE &&
            identity.serverFolder() == processor.currentWorldId && identity.worldFolder() == processor.currentMWId &&
            MiniMapClient.isCurrentDimension(processor.mapWorld.currentDimensionId)
    }

    override fun open(): Boolean {
        val screen = client.gui.screen() as? GuiMap ?: return false
        return current(screen.mapProcessor) && screen.dimensionOnInit === screen.mapProcessor.mapWorld.currentDimension
    }

    override fun visible(): List<TilePos> {
        val screen = client.gui.screen() as? GuiMap ?: return emptyList()
        if (!open()) return emptyList()
        val view = screen as GuiMapAccess
        val scale = view.`minimap$scale`()
        if (!scale.isFinite() || scale <= 0) return emptyList()
        val processor = processor() ?: return emptyList()
        return ViewportTiles.visible(
            view.`minimap$cameraX`(),
            view.`minimap$cameraZ`(),
            client.window.width,
            client.window.height,
            scale,
            processor.mapSaveLoad.mainTextureLevel.coerceIn(0, 3),
        )
    }

    override fun tile(pos: TilePos, data: TileData) {
        val processor = processor() ?: return
        if (!current(processor)) return
        if (pos.level > 0) {
            coarse(processor, pos, data)
            return
        }
        if (owner != MiniMapClient.identity()) {
            owner = MiniMapClient.identity()
            imported.clear()
        }
        synchronized(processor) {
            val region = processor.getLeafMapRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, true) ?: return
            if (region.loadState.toInt() == 1) return
            synchronized(region.writerThreadPauseSync) {
                if (region.isWritingPaused || region.loadState.toInt() == 3) return
                if (region.loadState.toInt() == 0) {
                    if (region.saveExists == true) {
                        processor.mapSaveLoad.requestLoad(region, "MiniMap surface")
                        return
                    }
                    region.loadState = 2
                    region.confirmMetaLoaded()
                }
                if (region.pixelResultBuffer == null) region.restoreBufferUpdateObjects()
                region.loadState = 2
                region.registerVisit()
                var chunk = region.getChunk(pos.x and 7, pos.z and 7)
                val first = chunk?.getTile(0, 0)?.getBlock(0, 0)
                if (first is ServerMapBlock && first.source === data) return
                if (chunk == null) {
                    chunk = MapTileChunk(region, pos.x, pos.z)
                    region.setChunk(pos.x and 7, pos.z and 7, chunk)
                }
                region.pushWriterPause()
                try {
                    for (tileZ in 0 until 4) {
                        for (tileX in 0 until 4) {
                            val tile = MapTile(null, pos.x * 4 + tileX, pos.z * 4 + tileZ)
                            tile.isLoaded = true
                            tile.setWrittenOnce(true)
                            tile.worldInterpretationVersion = MapTile.CURRENT_WORLD_INTERPRETATION_VERSION
                            for (z in 0 until 16) {
                                for (x in 0 until 16) {
                                    tile.setBlock(x, z, ServerMapBlock(data, (tileZ * 16 + z) * 64 + tileX * 16 + x))
                                }
                            }
                            chunk.setTile(tileX, tileZ, tile, processor.blockStateShortShapeCache, processor)
                        }
                    }
                    chunk.setLoadState(2)
                    chunk.setHasHadTerrain()
                    chunk.setChanged(true)
                    chunk.toUpdateBuffers = true
                    // Original samples live in the portable cache, never as fake blocks in Xaero's region files.
                    chunk.unincludeInSave()
                    region.setHasHadTerrain()
                    region.isAllCachePrepared = false
                    region.setShouldCache(false, "MiniMap update")
                    chunk.updateBuffers(
                        processor,
                        processor.worldBlockTintProvider,
                        processor.overlayManager,
                        false,
                        processor.blockStateShortShapeCache,
                        MapUpdateFastConfig(processor),
                    )
                    if (!processor.isProcessed(region)) processor.addToProcess(region)
                    imported += pos
                } finally {
                    region.popWriterPause()
                }
            }
        }
    }

    private fun coarse(processor: MapProcessor, pos: TilePos, data: TileData) {
        synchronized(processor) {
            processor.getLeafMapRegion(Int.MAX_VALUE, (pos.x shl pos.level) shr 3, (pos.z shl pos.level) shr 3, true) ?: return
            val region = processor.getLeveledRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, pos.level) as? BranchLeveledRegion ?: return
            val old = region.getTexture(pos.x and 7, pos.z and 7)
            if (old is ServerBranchTexture && old.source === data) return
            val texture = if (old is ServerBranchTexture) {
                old
            } else {
                ServerBranchTexture(region).also {
                    old?.deleteTexturesAndBuffers()
                    region.putTexture(pos.x and 7, pos.z and 7, it)
                }
            }
            texture.update(data)
            region.setLoaded(true)
            region.confirmMetaLoaded()
            region.setShouldCache(false, "MiniMap coarse update")
            region.isAllCachePrepared = false
            if (!processor.isProcessed(region)) processor.addToProcess(region)
        }
    }

    override fun missing(pos: TilePos) {
        if (pos.level > 0) {
            val processor = processor() ?: return
            if (!current(processor)) return
            val region = processor.getLeveledRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, pos.level) as? BranchLeveledRegion ?: return
            if (region.getTexture(pos.x and 7, pos.z and 7) is ServerBranchTexture) {
                coarse(processor, pos, TileData(IntArray(4096), IntArray(4096), ByteArray(4096)))
            }
            return
        }
        val processor = processor() ?: return
        if (!current(processor)) return
        val region = processor.getLeafMapRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, false) ?: return
        val clear = synchronized(region.writerThreadPauseSync) {
            val chunk = region.getChunk(pos.x and 7, pos.z and 7) ?: return
            val first = chunk.getTile(0, 0)?.getBlock(0, 0)
            (owner == MiniMapClient.identity() && pos in imported) || first is ServerMapBlock
        }
        if (clear) tile(pos, TileData(IntArray(4096), IntArray(4096), ByteArray(4096)))
    }
}
