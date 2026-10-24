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
import xaero.map.region.MapRegion
import xaero.map.region.MapTile
import xaero.map.region.MapTileChunk
import xaero.map.region.MapUpdateFastConfig

internal class XaeroBridge : MiniMapClient.Bridge {
    private val client = Minecraft.getInstance()
    private var owner: CacheIdentity? = null
    private val recentRegions = linkedMapOf<Pair<Int, Int>, MapRegion>()

    override fun reset() {
        recentRegions.clear()
        owner = null
    }

    private fun maintainRecentRegions() {
        val identity = MiniMapClient.identity()
        if (owner != identity) {
            reset()
            owner = identity
        }
        recentRegions.values.forEach { it.registerVisit() }
    }

    private fun processor(): MapProcessor? = WorldMapSession.getCurrentSession()?.mapProcessor

    private fun current(processor: MapProcessor?): Boolean {
        val identity = MiniMapClient.identity() ?: return false
        return processor != null && processor.isMapWorldUsable && processor.currentCaveLayer == Int.MAX_VALUE &&
            identity.serverFolder() == processor.currentWorldId && identity.worldFolder() == processor.currentMWId &&
            MiniMapClient.isCurrentDimension(processor.mapWorld.currentDimensionId)
    }

    override fun open(): Boolean {
        maintainRecentRegions()
        val screen = client.gui.screen() as? GuiMap ?: return false
        return current(screen.mapProcessor) && screen.dimensionOnInit === screen.mapProcessor.mapWorld.currentDimension
    }

    override fun visible(): List<TilePos> {
        val screen = client.gui.screen() as? GuiMap ?: return emptyList()
        if (!open()) return emptyList()
        val view = screen as GuiMapAccess
        val scale = view.`minimap$scale`()
        if (!scale.isFinite() || scale <= 0) return emptyList()
        val processor = screen.mapProcessor
        val result = ViewportTiles.visible(
            view.`minimap$cameraX`(),
            view.`minimap$cameraZ`(),
            client.window.width,
            client.window.height,
            scale,
            processor.mapSaveLoad.mainTextureLevel.coerceIn(0, 3),
        )
        result.map { ((it.x shl it.level) shr 3) to ((it.z shl it.level) shr 3) }.distinct().forEach { (x, z) ->
            processor.getLeafMapRegion(Int.MAX_VALUE, x, z, false)?.registerVisit()
        }
        return result
    }

    override fun tile(pos: TilePos, data: TileData) = update(pos, data, false)

    override fun missing(pos: TilePos) = update(pos, EMPTY, true)

    private fun update(pos: TilePos, data: TileData, remove: Boolean) {
        val processor = processor() ?: return
        if (!current(processor)) return
        if (pos.level > 0) {
            coarse(processor, pos, data, remove)
            return
        }
        synchronized(processor) {
            val region = processor.getLeafMapRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, !remove) ?: return
            if (region.loadState.toInt() == 1) return
            synchronized(region.writerThreadPauseSync) {
                if (region.isWritingPaused || region.loadState.toInt() == 3) return
                if (region.loadState.toInt() == 0) {
                    if (region.saveExists == true) {
                        processor.mapSaveLoad.requestLoad(region, "MiniMap surface")
                        return
                    }
                    if (remove) return
                    region.loadState = 2
                    region.confirmMetaLoaded()
                }
                region.registerVisit()
                if (!remove) {
                    maintainRecentRegions()
                    val key = (pos.x shr 3) to (pos.z shr 3)
                    recentRegions.remove(key)
                    recentRegions[key] = region
                    // Two full detailed views stay resident; older views still have native and portable disk caches.
                    while (recentRegions.size > 32) recentRegions.remove(recentRegions.keys.first())
                }
                var chunk = region.getChunk(pos.x and 7, pos.z and 7)
                val changes = mutableListOf<Pair<Int, Int>>()
                for (tileZ in 0 until 4) {
                    for (tileX in 0 until 4) {
                        val tile = chunk?.getTile(tileX, tileZ)
                        val changed = needsServerUpdate(
                            tile,
                            data,
                            remove,
                        )
                        if (changed) changes += tileX to tileZ
                    }
                }
                if (changes.isEmpty()) return
                if (region.pixelResultBuffer == null) region.restoreBufferUpdateObjects()
                region.loadState = 2
                if (chunk == null) {
                    chunk = MapTileChunk(region, pos.x, pos.z)
                    region.setChunk(pos.x and 7, pos.z and 7, chunk)
                }
                region.pushWriterPause()
                try {
                    for ((tileX, tileZ) in changes) {
                        val tile = chunk.getTile(tileX, tileZ) ?: if (!remove) {
                            MapTile(null, pos.x * 4 + tileX, pos.z * 4 + tileZ).apply {
                                isLoaded = true
                                setWrittenOnce(true)
                                worldInterpretationVersion = MapTile.CURRENT_WORLD_INTERPRETATION_VERSION
                            }
                        } else {
                            continue
                        }
                        tile.importServerSamples(data, tileX * 16, tileZ * 16, remove)
                        chunk.setTile(tileX, tileZ, tile, processor.blockStateShortShapeCache, processor)
                    }
                    chunk.setLoadState(2)
                    chunk.setHasHadTerrain()
                    chunk.setChanged(true)
                    chunk.toUpdateBuffers = true
                    // Original samples live in the portable cache, never as fake blocks in Xaero's region files.
                    chunk.unincludeInSave()
                    region.setHasHadTerrain()
                    region.isAllCachePrepared = false
                    region.setShouldCache(true, "MiniMap update")
                    region.setRecacheHasBeenRequested(true, "MiniMap update")
                    chunk.updateBuffers(
                        processor,
                        processor.worldBlockTintProvider,
                        processor.overlayManager,
                        false,
                        processor.blockStateShortShapeCache,
                        MapUpdateFastConfig(processor),
                    )
                    if (!processor.isProcessed(region)) processor.addToProcess(region)
                } finally {
                    region.popWriterPause()
                }
            }
        }
    }

    private fun coarse(processor: MapProcessor, pos: TilePos, data: TileData, remove: Boolean) {
        synchronized(processor) {
            processor.getLeafMapRegion(Int.MAX_VALUE, (pos.x shr 3) shl pos.level, (pos.z shr 3) shl pos.level, !remove) ?: return
            val region = processor.getLeveledRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, pos.level) as? BranchLeveledRegion ?: return
            val old = region.getTexture(pos.x and 7, pos.z and 7)
            if (remove && old !is ServerBranchTexture) return
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

    companion object {
        private val EMPTY = TileData(IntArray(4096), IntArray(4096), ByteArray(4096))
    }
}
