package io.github.minimap.api

import java.io.IOException
import java.nio.file.Path
import java.util.Optional
import java.util.UUID

/** Register with Bukkit ServicesManager. World identity always comes from the connection player. */
interface MapProvider {
    @JvmRecord
    data class WorldRef(val uid: UUID, val name: String, val dimension: String, val saveFolder: Path)

    @JvmRecord
    data class MapRef(val id: String, val epoch: String) {
        init {
            require(id.isNotBlank() && id.length <= 128 && epoch.isNotBlank() && epoch.length <= 128) {
                "Invalid map identity"
            }
        }
    }

    /** Called on the server thread; use cached metadata and perform no storage I/O. */
    fun resolve(world: WorldRef): Optional<MapRef>

    /** Claim a world even while disabled, preventing another provider from bypassing its policy. */
    fun authoritative(world: WorldRef): Boolean = false

    /** Checked before requests and sends; implementations must use an in-memory policy snapshot. */
    fun available(map: MapRef): Boolean = true

    /** Override when several mounts represent one logical world. Existing installations keep their UUID. */
    fun cacheId(world: WorldRef, map: MapRef): String = world.uid.toString()

    /** Called on bounded workers. Read only committed immutable snapshots; empty means absent or deleted. */
    @Throws(IOException::class)
    fun read(map: MapRef, tileX: Int, tileZ: Int): Optional<SurfaceTile>

    /** Coarse samples are optional for providers; the fallback preserves existing integrations. */
    @Throws(IOException::class)
    fun readScaled(map: MapRef, tileX: Int, tileZ: Int, level: Int): Optional<SurfaceTile> {
        require(level in 0..3)
        if (level == 0) return read(map, tileX, tileZ)
        val step = 1 shl level
        val colors = IntArray(4096)
        val heights = IntArray(4096)
        val lights = ByteArray(4096)
        var present = false
        for (z in 0 until step) {
            for (x in 0 until step) {
                val tile = read(map, tileX * step + x, tileZ * step + z).orElse(null) ?: continue
                val sourceColors = tile.argb()
                val sourceHeights = tile.height()
                val sourceLights = tile.blockLight()
                val side = 64 / step
                for (pz in 0 until side) {
                    for (px in 0 until side) {
                        val source = pz * step * 64 + px * step
                        val target = (z * side + pz) * 64 + x * side + px
                        colors[target] = sourceColors[source]
                        heights[target] = sourceHeights[source]
                        lights[target] = sourceLights[source]
                        present = present || sourceColors[source] ushr 24 != 0
                    }
                }
            }
        }
        return if (present) Optional.of(SurfaceTile(colors, heights, lights)) else Optional.empty()
    }

    /** 64×64 block samples, indexed by z * 64 + x. Copies protect publication from caller mutation. */
    class SurfaceTile(argb: IntArray, height: IntArray, blockLight: ByteArray) {
        private val colors = argb.clone()
        private val heights = height.clone()
        private val lights = blockLight.clone()

        init {
            require(argb.size == 4096 && height.size == 4096 && blockLight.size == 4096) {
                "Expected 4096 samples"
            }
        }

        fun argb(): IntArray = colors.clone()
        fun height(): IntArray = heights.clone()
        fun blockLight(): ByteArray = lights.clone()
    }
}
