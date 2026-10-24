package io.github.minimap.fabric

import io.github.minimap.protocol.TileData
import xaero.map.region.MapTile
import xaero.map.region.MapTileChunk

internal fun MapTile.hasServerSamples(): Boolean = (0 until 256).any {
    (getBlock(it and 15, it shr 4) as? ServerMapBlock)?.serverOwned == true
}

internal fun hasServerSamples(chunk: MapTileChunk): Boolean = (0 until 16).any {
    chunk.getTile(it and 3, it shr 2)?.hasServerSamples() == true
}

/** A committed server snapshot supersedes local exploration; absent server tiles keep native fallback. */
internal fun needsServerUpdate(tile: MapTile?, data: TileData, remove: Boolean): Boolean = (0 until 256).any { index ->
    val block = tile?.getBlock(index and 15, index shr 4)
    if (remove) {
        block is ServerMapBlock && block.serverOwned && block.source !== data
    } else {
        block !is ServerMapBlock || !block.serverOwned || block.source !== data
    }
}

internal fun MapTile.importServerSamples(data: TileData, offsetX: Int, offsetZ: Int, remove: Boolean) {
    for (z in 0 until 16) {
        for (x in 0 until 16) {
            val previous = getBlock(x, z)
            if (remove && (previous !is ServerMapBlock || !previous.serverOwned)) continue
            setBlock(x, z, ServerMapBlock(data, (offsetZ + z) * 64 + offsetX + x))
        }
    }
}
