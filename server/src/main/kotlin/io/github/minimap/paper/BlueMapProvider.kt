package io.github.minimap.paper

import io.github.minimap.api.MapProvider
import org.bukkit.plugin.java.JavaPlugin
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Isolate version-sensitive Core calls here; read standard samples through storage, never file paths. */
internal class BlueMapProvider(private val plugin: JavaPlugin?) : MapProvider {
    private data class Binding(val storage: Any, val width: Int, val depth: Int)
    private val bindings = ConcurrentHashMap<String, Binding>()

    override fun resolve(world: MapProvider.WorldRef): Optional<MapProvider.MapRef> {
        val plugin = plugin ?: return Optional.empty()
        val blueMap = plugin.server.pluginManager.getPlugin("BlueMap") ?: return Optional.empty()
        if (!blueMap.isEnabled) return Optional.empty()
        try {
            val apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI", true, blueMap.javaClass.classLoader)
            val api = apiClass.getMethod("getInstance").invoke(null) as Optional<*>
            if (api.isEmpty) return Optional.empty()
            val selected = plugin.config.getString("bluemap-maps.${world.name}")
            val matches = (call(api.get(), "getMaps") as Collection<*>).filterNotNull().filter { map ->
                if (selected != null) {
                    selected == call(map, "getId")
                } else {
                    val folder = call(call(map, "getWorld"), "getSaveFolder") as Path
                    folder.toAbsolutePath().normalize() == world.saveFolder.toAbsolutePath().normalize()
                }
            }
            if (matches.size != 1) return Optional.empty()
            val match = matches.single()
            val id = call(match, "getId") as String
            val core = call(match, "map")
            val size = call(call(call(core, "getLowresTileManager"), "getTileGrid"), "getGridSize")
            val width = (call(size, "getX") as Number).toInt()
            val depth = (call(size, "getY") as Number).toInt()
            if (width !in 1..2048 || depth !in 1..2048) throw IOException("Unsupported BlueMap tile dimensions")
            bindings[id] = Binding(call(core, "getStorage"), width, depth)
            return Optional.of(MapProvider.MapRef(id, "1"))
        } catch (error: ReflectiveOperationException) {
            plugin.logger.fine("BlueMap provider unavailable: $error")
        } catch (error: IOException) {
            plugin.logger.fine("BlueMap provider unavailable: $error")
        } catch (error: RuntimeException) {
            plugin.logger.fine("BlueMap provider unavailable: $error")
        }
        return Optional.empty()
    }

    override fun read(map: MapProvider.MapRef, tileX: Int, tileZ: Int): Optional<MapProvider.SurfaceTile> {
        val binding = bindings[map.id] ?: return Optional.empty()
        val colors = IntArray(4096)
        val heights = IntArray(4096)
        val lights = ByteArray(4096)
        val baseX = Math.multiplyExact(tileX, 64)
        val baseZ = Math.multiplyExact(tileZ, 64)
        var present = false
        try {
            val grid = call(binding.storage, "lowresTiles", arrayOf(Int::class.javaPrimitiveType!!), 1)
            val images = mutableMapOf<Long, BufferedImage?>()
            for (z in 0 until 64) {
                for (x in 0 until 64) {
                    val blockX = baseX + x
                    val blockZ = baseZ + z
                    val cellX = Math.floorDiv(blockX, binding.width)
                    val cellZ = Math.floorDiv(blockZ, binding.depth)
                    val key = (cellX.toLong() shl 32) or (cellZ.toLong() and 0xffffffffL)
                    if (!images.containsKey(key)) {
                        val value =
                            callNullable(
                                grid,
                                "read",
                                arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                                cellX,
                                cellZ,
                            )
                        images[key] = if (value == null) {
                            null
                        } else {
                            (value as InputStream).use {
                                (call(value, "decompress") as InputStream).use { decoded ->
                                    val image = ImageIO.read(decoded)
                                    if (image == null || image.width != binding.width + 1 || image.height != (binding.depth + 1) * 2) {
                                        throw IOException(
                                            "BlueMap storage does not expose standard PNG samples; install a custom MapProvider",
                                        )
                                    }
                                    image
                                }
                            }
                        }
                    }
                    val image = images[key] ?: continue
                    val localX = Math.floorMod(blockX, binding.width)
                    val localZ = Math.floorMod(blockZ, binding.depth)
                    val index = z * 64 + x
                    val color = image.getRGB(localX, localZ)
                    if (color ushr 24 == 0) continue
                    val meta = image.getRGB(localX, localZ + binding.depth + 1)
                    colors[index] = color
                    heights[index] = meta.toShort().toInt()
                    lights[index] = minOf(15, (meta ushr 16) and 255).toByte()
                    present = true
                }
            }
        } catch (error: ReflectiveOperationException) {
            throw IOException("Unsupported BlueMap Core API", error)
        }
        return if (present) Optional.of(MapProvider.SurfaceTile(colors, heights, lights)) else Optional.empty()
    }

    private fun call(value: Any, name: String, types: Array<Class<*>> = emptyArray(), vararg arguments: Any): Any =
        callNullable(value, name, types, *arguments) ?: throw IOException("Missing BlueMap value: $name")

    private fun callNullable(value: Any, name: String, types: Array<Class<*>>, vararg arguments: Any): Any? {
        val method = value.javaClass.getMethod(name, *types)
        if (!method.canAccess(value)) method.trySetAccessible()
        return method.invoke(value, *arguments)
    }
}
