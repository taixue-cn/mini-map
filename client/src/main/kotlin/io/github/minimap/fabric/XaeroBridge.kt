package io.github.minimap.fabric

import io.github.minimap.fabric.mixin.GuiMapAccess
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import net.minecraft.client.Minecraft
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import xaero.map.region.MapTile
import xaero.map.region.MapTileChunk
import xaero.map.region.MapUpdateFastConfig
import kotlin.math.floor

internal class XaeroBridge : MiniMapClient.Bridge {
    private val client = Minecraft.getInstance()

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
        val halfX = client.window.width / scale / 2
        val halfZ = client.window.height / scale / 2
        val cameraX = view.`minimap$cameraX`()
        val cameraZ = view.`minimap$cameraZ`()
        val centerX = floor(cameraX / 64).toInt()
        val centerZ = floor(cameraZ / 64).toInt()
        // shortcut: v1 has no LOD; cap detailed requests to 32×32 tiles when zoomed out.
        val minX = maxOf(centerX - 16, floor((cameraX - halfX) / 64).toInt())
        val maxX = minOf(centerX + 15, floor((cameraX + halfX) / 64).toInt())
        val minZ = maxOf(centerZ - 16, floor((cameraZ - halfZ) / 64).toInt())
        val maxZ = minOf(centerZ + 15, floor((cameraZ + halfZ) / 64).toInt())
        val result = mutableListOf<TilePos>()
        for (z in minZ..maxZ) {
            for (x in minX..maxX) {
                try {
                    result += TilePos(x, z)
                } catch (_: IllegalArgumentException) {
                    // A viewport can extend beyond the supported world boundary.
                }
            }
        }
        return result.sortedBy { (it.x - centerX).toLong() * (it.x - centerX) + (it.z - centerZ).toLong() * (it.z - centerZ) }
    }

    override fun tile(pos: TilePos, data: TileData) = update(pos, data, false)

    override fun missing(pos: TilePos) = update(pos, EMPTY, true)

    private fun update(pos: TilePos, data: TileData, remove: Boolean) {
        val processor = processor() ?: return
        val level = client.level ?: return
        if (!current(processor)) return
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
                var chunk = region.getChunk(pos.x and 7, pos.z and 7)
                val changes = mutableListOf<Pair<Int, Int>>()
                for (tileZ in 0 until 4) {
                    for (tileX in 0 until 4) {
                        // Live Minecraft chunks belong to Xaero's writer, whose samples are newer than saved server terrain.
                        val tile = chunk?.getTile(tileX, tileZ)
                        val changed = needsServerUpdate(
                            tile,
                            data,
                            remove,
                            level.chunkSource.hasChunk(pos.x * 4 + tileX, pos.z * 4 + tileZ),
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
                        val tile = if (remove) {
                            chunk.getTile(tileX, tileZ) ?: continue
                        } else {
                            MapTile(null, pos.x * 4 + tileX, pos.z * 4 + tileZ).apply {
                                isLoaded = true
                                setWrittenOnce(true)
                                worldInterpretationVersion = MapTile.CURRENT_WORLD_INTERPRETATION_VERSION
                            }
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
                } finally {
                    region.popWriterPause()
                }
            }
        }
    }

    companion object {
        private val EMPTY = TileData(IntArray(4096), IntArray(4096), ByteArray(4096))
    }
}
