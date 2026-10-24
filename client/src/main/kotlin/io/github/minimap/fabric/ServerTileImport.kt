package io.github.minimap.fabric

import io.github.minimap.protocol.TileData
import xaero.map.region.MapTile

/** Native exploration owns loaded chunks; an absent server tile can remove only server-owned samples. */
internal fun needsServerUpdate(tile: MapTile?, data: TileData, remove: Boolean, nativeLoaded: Boolean): Boolean {
    if (nativeLoaded) return false
    return (0 until 256).any { index ->
        val block = tile?.getBlock(index and 15, index shr 4) as? ServerMapBlock
        if (remove) {
            block?.serverOwned == true && block.source !== data
        } else {
            block == null || !block.serverOwned || block.source !== data
        }
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
