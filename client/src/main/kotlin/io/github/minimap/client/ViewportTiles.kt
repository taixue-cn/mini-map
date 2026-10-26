package io.github.minimap.client

import io.github.minimap.protocol.TilePos
import kotlin.math.floor

/** Cover the complete viewport, including edge tiles and negative coordinates. */
object ViewportTiles {
    fun visible(cameraX: Double, cameraZ: Double, width: Int, height: Int, scale: Double, level: Int): List<TilePos> {
        if (!cameraX.isFinite() || !cameraZ.isFinite() || !scale.isFinite() || scale <= 0 || width <= 0 || height <= 0) return emptyList()
        require(level in 0..3)
        val span = 64 shl level
        val bound = 30_000_000.0
        val minX = floor(maxOf(-bound, cameraX - width / scale / 2) / span).toInt()
        val maxX = floor(minOf(bound, cameraX + width / scale / 2) / span).toInt()
        val minZ = floor(maxOf(-bound, cameraZ - height / scale / 2) / span).toInt()
        val maxZ = floor(minOf(bound, cameraZ + height / scale / 2) / span).toInt()
        val centerX = cameraX / span
        val centerZ = cameraZ / span
        return buildList { for (z in minZ..maxZ) for (x in minX..maxX) add(TilePos(x, z, level)) }
            .sortedBy { (it.x + 0.5 - centerX) * (it.x + 0.5 - centerX) + (it.z + 0.5 - centerZ) * (it.z + 0.5 - centerZ) }
    }
}
