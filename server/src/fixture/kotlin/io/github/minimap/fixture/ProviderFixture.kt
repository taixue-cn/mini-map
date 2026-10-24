package io.github.minimap.fixture

import io.github.minimap.api.MapProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

/** Deterministic remote surface for isolated loopback tests; excluded from release artifacts. */
class ProviderFixture :
    JavaPlugin(),
    MapProvider {
    private var standardBlueMap: StandardBlueMapFixture? = null
    private val firstRead = AtomicLong()

    override fun onEnable() {
        if (System.getenv("MINIMAP_TEST_UPSTREAM_BLUEMAP") == "true") {
            standardBlueMap = StandardBlueMapFixture(this).also { it.start() }
            return
        }
        server.servicesManager.register(MapProvider::class.java, this, this, ServicePriority.Highest)
        logger.info("MINIMAP_FIXTURE_READY")
    }

    override fun onDisable() {
        standardBlueMap?.close()
    }

    override fun resolve(world: MapProvider.WorldRef): Optional<MapProvider.MapRef> =
        Optional.of(MapProvider.MapRef(world.uid.toString(), "1"))

    override fun readScaled(map: MapProvider.MapRef, tileX: Int, tileZ: Int, level: Int): Optional<MapProvider.SurfaceTile> {
        if (System.getenv("MINIMAP_TEST_COARSE") ==
            "true"
        ) {
            return Optional.of(
                MapProvider.SurfaceTile(
                    IntArray(4096) {
                        0xffe04090.toInt()
                    },
                    IntArray(4096) { 71 },
                    ByteArray(4096) { 12 },
                ),
            )
        }
        return super.readScaled(map, tileX, tileZ, level)
    }

    override fun read(map: MapProvider.MapRef, tileX: Int, tileZ: Int): Optional<MapProvider.SurfaceTile> {
        val stability = java.lang.Boolean.getBoolean("minimap.fixture.stability")
        if ((tileX != 10 || tileZ != 10) && !(stability && tileX in -3..3 && tileZ in -3..3)) return Optional.empty()
        val now = System.currentTimeMillis()
        firstRead.compareAndSet(0, now)
        val age = now - firstRead.get()
        if (java.lang.Boolean.getBoolean("minimap.fixture.updates") && age >= 18000) return Optional.empty()
        val changed = (java.lang.Boolean.getBoolean("minimap.fixture.updates") && age >= 8000) ||
            (stability && tileX == 10 && tileZ == 10 && age >= 10000)
        return Optional.of(
            MapProvider.SurfaceTile(
                IntArray(4096) { if (changed) 0xff30b050.toInt() else 0xffe04090.toInt() },
                IntArray(4096) { if (changed) 72 else 71 },
                ByteArray(4096) { if (changed) 10 else 12 },
            ),
        )
    }
}
