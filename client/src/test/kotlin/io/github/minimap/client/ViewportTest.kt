package io.github.minimap.client

import io.github.minimap.protocol.TilePos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ViewportTest {
    @Test
    fun zoomedOutMapIncludesAllFourCornersAndMoreThanTheOldCenterSquare() {
        for (level in 0..3) {
            val tiles = ViewportTiles.visible(-152.0, -1780.0, 1920, 1080, 0.195, level)
            val span = 64 shl level
            for (x in listOf(-152.0 - 1920 / 0.195 / 2, -152.0 + 1920 / 0.195 / 2)) {
                for (z in listOf(-1780.0 - 1080 / 0.195 / 2, -1780.0 + 1080 / 0.195 / 2)) {
                    assertTrue(TilePos(kotlin.math.floor(x / span).toInt(), kotlin.math.floor(z / span).toInt(), level) in tiles)
                }
            }
            assertEquals(tiles.size, tiles.toSet().size)
            if (level == 0) assertTrue(tiles.size > 1024)
        }
    }
    @Test
    fun coarseZoomReducesSamplesWithoutDiscardingViewportArea() {
        val full = ViewportTiles.visible(0.0, 0.0, 1920, 1080, 0.195, 0)
        val coarse = ViewportTiles.visible(0.0, 0.0, 1920, 1080, 0.195, 2)
        assertTrue(full.size > coarse.size * 14)
        assertTrue(ViewportTiles.visible(Double.NaN, 0.0, 1920, 1080, 0.195, 2).isEmpty())
    }
}
