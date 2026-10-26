package io.github.minimap.protocol

import kotlin.math.abs

/** A 64×64 sample tile; each pixel covers 2^level blocks at coarse zoom levels. */
@JvmRecord
data class TilePos(val x: Int, val z: Int, val level: Int = 0) {
    init {
        require(level in 0..3) { "Unsupported map resolution" }
        require(abs(x.toLong() * (SIDE shl level)) <= 30_000_512L && abs(z.toLong() * (SIDE shl level)) <= 30_000_512L) {
            "Tile outside supported world bounds"
        }
    }
    val step: Int get() = 1 shl level
    val span: Int get() = SIDE shl level
    companion object {
        const val SIDE = 64
        const val PIXELS = SIDE * SIDE
    }
}
