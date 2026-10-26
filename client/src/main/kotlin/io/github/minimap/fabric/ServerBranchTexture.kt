package io.github.minimap.fabric

import io.github.minimap.protocol.TileData
import xaero.map.region.BranchLeveledRegion
import xaero.map.region.LeveledRegion
import xaero.map.region.texture.BranchRegionTexture
import xaero.map.region.texture.RegionTexture

/** Native Xaero coarse textures avoid expanding one overview pixel into thousands of fake blocks. */
internal class ServerBranchTexture(region: BranchLeveledRegion) : BranchRegionTexture(region) {
    var source: TileData? = null
        private set

    fun update(data: TileData) {
        source = data
        prepareBuffer()
        val buffer = directColorBuffer
        buffer.clear()
        for (index in 0 until 4096) {
            val color = data.color(index)
            buffer.put((color ushr 16).toByte()).put((color ushr 8).toByte()).put(color.toByte()).put((color ushr 24).toByte())
            putHeight(index and 63, index shr 6, data.height(index))
            putTopHeight(index and 63, index shr 6, data.height(index))
        }
        buffer.flip()
        colorBufferFormat = DEFAULT_INTERNAL_FORMAT
        bufferHasLight = false
        textureHasLight = false
        bufferedTextureVersion++
        textureVersion = bufferedTextureVersion
        isCachePrepared = true
        setToUpload(true)
    }

    // The committed server snapshot owns these pixels until the next checked update.
    override fun checkForUpdates(
        a: RegionTexture<*>?,
        b: RegionTexture<*>?,
        c: RegionTexture<*>?,
        d: RegionTexture<*>?,
        child: LeveledRegion<*>?,
    ): Boolean = false
    override fun hasSourceData(): Boolean = source != null
    override fun shouldIncludeInCache(): Boolean = false
}
