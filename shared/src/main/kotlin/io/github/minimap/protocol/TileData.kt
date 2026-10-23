package io.github.minimap.protocol

/** Immutable row-major samples. Transparent pixels have canonical zero height and light. */
class TileData(colors: IntArray, heights: IntArray, lights: ByteArray) {
    private val colors = colors.clone()
    private val heights = heights.clone()
    private val lights = lights.clone()

    init {
        require(colors.size == TilePos.PIXELS && heights.size == TilePos.PIXELS && lights.size == TilePos.PIXELS) {
            "Expected a 64 by 64 tile"
        }
        for (i in colors.indices) {
            if (colors[i] ushr 24 == 0) {
                this.colors[i] = 0
                this.heights[i] = 0
                this.lights[i] = 0
            }
            require(this.lights[i] in 0..15 && this.heights[i] in Short.MIN_VALUE..Short.MAX_VALUE) {
                "Invalid surface sample"
            }
        }
    }

    fun color(index: Int): Int = colors[index]
    fun height(index: Int): Int = heights[index]
    fun light(index: Int): Byte = lights[index]
    fun empty(): Boolean = colors.none { it ushr 24 != 0 }
}
