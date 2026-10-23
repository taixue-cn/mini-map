package io.github.minimap.protocol

import kotlin.math.abs

@JvmRecord
data class TilePos(val x: Int, val z: Int) {
    init {
        require(abs(x.toLong() * SIDE) <= 30_000_064L && abs(z.toLong() * SIDE) <= 30_000_064L) {
            "Tile outside supported world bounds"
        }
    }

    companion object {
        const val SIDE = 64
        const val PIXELS = SIDE * SIDE
    }
}
