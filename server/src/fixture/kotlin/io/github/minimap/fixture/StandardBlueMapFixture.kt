package io.github.minimap.fixture

import io.github.minimap.api.MapProvider
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the shipped adapter against an actual upstream render, without registering a substitute provider. */
internal class StandardBlueMapFixture(private val plugin: JavaPlugin) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean()
    private var scheduled = false

    fun start() {
        val root = directory()
        check(root.fileName.toString().startsWith("minimap-bluemap-"))
        val marker = requireNotNull(System.getenv("MINIMAP_TEST_MARKER"))
        UUID.fromString(marker)
        check(Files.readString(root.resolve(".minimap-bluemap-fixture")).trim() == marker)
        check(plugin.server.servicesManager.getRegistrations(MapProvider::class.java).isEmpty())
        val world = requireNotNull(plugin.server.getWorld("world"))
        check(world.worldFolder.toPath().toAbsolutePath().normalize().startsWith(root))
        // Keep the requested tile inside upstream render cells rather than on the mask boundary.
        for (x in 608..719) for (z in 608..719) world.getBlockAt(x, 70, z).setType(Material.SEA_LANTERN, false)
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                world.save()
                plugin.server.dispatchCommand(plugin.server.consoleSender, "save-all flush")
                plugin.logger.info("MINIMAP_FIXTURE_READY")
                plugin.server.scheduler.runTaskTimer(
                    plugin,
                    Runnable tick@{
                        if (Files.exists(root.resolve("expected.json")) || reading.get()) return@tick
                        try {
                            val blueMap = requireNotNull(plugin.server.pluginManager.getPlugin("BlueMap"))
                            val apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI", true, blueMap.javaClass.classLoader)
                            val api = (apiClass.getMethod("getInstance").invoke(null) as Optional<*>).orElse(null) ?: return@tick
                            val map = (call(api, "getMap", arrayOf(String::class.java), "native_standard") as Optional<*>).orElse(null)
                                ?: return@tick
                            if (!scheduled) {
                                val mapClass = Class.forName("de.bluecolored.bluemap.api.BlueMapMap", true, blueMap.javaClass.classLoader)
                                scheduled = call(
                                    call(api, "getRenderManager"),
                                    "scheduleMapUpdateTask",
                                    arrayOf(mapClass, Boolean::class.javaPrimitiveType!!),
                                    map,
                                    true,
                                ) as Boolean
                            }
                            val server = requireNotNull(plugin.server.pluginManager.getPlugin("MiniMapServer"))
                            val adapterField = server.javaClass.getDeclaredField("blueMap").apply { isAccessible = true }
                            val adapter = adapterField.get(server) as MapProvider
                            val reference = MapProvider.WorldRef(world.uid, world.name, world.key.toString(), world.worldFolder.toPath())
                            val resolved = adapter.resolve(reference).orElse(null)
                            val apiWorld = call(map, "getWorld")
                            val binding = listOf(
                                "bukkit=${reference.saveFolder}",
                                "key=${reference.dimension}",
                                "bluemap=${call(apiWorld, "getSaveFolder")}",
                                "resolved=$resolved",
                            )
                            Files.writeString(root.resolve("binding.txt"), binding.joinToString("\n"))
                            if (!scheduled || resolved == null || !reading.compareAndSet(false, true)) return@tick
                            worker.execute {
                                try {
                                    val tile = adapter.read(resolved, 10, 10).orElse(null) ?: return@execute
                                    val colors = tile.argb()
                                    if (colors.any { it ushr 24 == 0 } || tile.height().any { it != 70 } ||
                                        tile.blockLight().any { it != 15.toByte() }
                                    ) {
                                        return@execute
                                    }
                                    val color = Integer.toUnsignedString(colors[0], 16)
                                    Files.writeString(root.resolve("expected.json"), """{"argb":"$color","height":70,"light":15}""")
                                } catch (failure: Throwable) {
                                    Files.writeString(root.resolve("read-error.txt"), failure.stackTraceToString())
                                } finally {
                                    reading.set(false)
                                }
                            }
                        } catch (failure: Throwable) {
                            Files.writeString(root.resolve("fixture-error.txt"), failure.stackTraceToString())
                        }
                    },
                    20,
                    20,
                )
            },
            40,
        )
    }

    override fun close() {
        worker.shutdownNow()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun directory(): Path = Path.of(requireNotNull(System.getenv("MINIMAP_TEST_WORKSPACE"))).toAbsolutePath().normalize()

    private fun call(value: Any, name: String, types: Array<Class<*>> = emptyArray(), vararg arguments: Any): Any =
        value.javaClass.getMethod(name, *types).invoke(value, *arguments)
}
