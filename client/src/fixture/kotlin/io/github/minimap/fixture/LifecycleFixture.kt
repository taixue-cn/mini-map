package io.github.minimap.fixture

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.minimap.client.TileCache
import io.github.minimap.fabric.MiniMapClient
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/** File-driven native assertions allow an independent server harness to mutate actual saved map data. */
class LifecycleFixture : ClientModInitializer {
    private val root = Path.of(requireNotNull(System.getenv("MINIMAP_TEST_SCENARIO_DIR")))
    private val gson = Gson()
    private val started = System.currentTimeMillis()
    private var connecting = false
    private var done = false
    private var ticks = 0
    private var sequence = -1
    private var acknowledged = false
    private var began = 0L
    private var stable = 0L
    private var baseline = emptyMap<String, Long>()

    override fun onInitializeClient() {
        check(Files.isRegularFile(root.resolve(".mini-map-lifecycle")))
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done || ++ticks % 10 != 0) return@register
            try {
                inspect(client)
            } catch (failure: Throwable) {
                finish(client, false, failure.toString())
            }
        }
    }

    private fun inspect(client: Minecraft) {
        val now = System.currentTimeMillis()
        check(now - started < 600000) { "Lifecycle timeout" }
        if (client.level == null) {
            if (!connecting && ticks >= 100) {
                connecting = true
                val address = requireNotNull(System.getenv("MINIMAP_TEST_SERVER"))
                ConnectScreen.startConnecting(
                    TitleScreen(),
                    client,
                    ServerAddress.parseString(address),
                    ServerData("isolated lifecycle", address, ServerData.Type.OTHER),
                    false,
                    null,
                )
            }
            return
        }
        client.options.renderDistance().set(2)
        val file = root.resolve("client-command.json")
        if (!Files.exists(file)) return
        val command = gson.fromJson(Files.readString(file), JsonObject::class.java)
        val next = command["sequence"].asInt
        if (sequence != next) {
            sequence = next
            acknowledged = false
            began = now
            stable = 0
            baseline = FixturePackets.snapshot()
            write(root.resolve("client-observed.json"), mapOf("sequence" to sequence))
        }
        write(
            root.resolve("client-state.json"),
            mapOf(
                "sequence" to sequence,
                "identity" to MiniMapClient.identity()?.key(),
                "dimension" to client.level!!.dimension().identifier().toString(),
                "counts" to FixturePackets.snapshot(),
            ),
        )
        if (acknowledged) return
        check(now - began < 90000) { "Phase $sequence timeout: ${FixturePackets.snapshot()}" }
        val operation = command["operation"].asString
        if (operation == "finish") {
            finish(client, true, "Actual native lifecycle assertions completed")
            return
        }
        val identity = MiniMapClient.identity()
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
        val open = command["open"]?.asBoolean ?: false
        val currentScreen = client.gui.screen() as? GuiMap
        if (open && processor != null && processor.isMapWorldUsable &&
            (currentScreen == null || currentScreen.dimensionOnInit !== processor.mapWorld.currentDimension)
        ) {
            client.gui.setScreen(GuiMap(null, null, processor, client.player))
        } else if (!open && client.gui.screen() is GuiMap) {
            client.gui.setScreen(null)
        }
        (client.gui.screen() as? GuiMap)?.let { screen ->
            GuiMap::class.java.getDeclaredField("attachedCamera").apply { isAccessible = true }.setBoolean(null, false)
            for (name in listOf("cameraX", "cameraZ")) {
                GuiMap::class.java.getDeclaredField(name).apply { isAccessible = true }.setDouble(screen, 672.0)
            }
        }
        val evidence = linkedMapOf<String, Any>("sequence" to sequence, "operation" to operation)
        val ready = when (operation) {
            "revoked" -> identity == null && delta("revokes") > 0
            "world" -> identity == null && client.level!!.dimension().identifier().toString() == command["dimension"].asString
            "idle" -> identity != null && processor != null && processor.isMapWorldUsable && identity.worldFolder() == processor.currentMWId
            "tile", "missing" -> {
                if (identity == null || processor == null || !processor.isMapWorldUsable ||
                    identity.worldFolder() != processor.currentMWId
                ) {
                    return
                }
                val block = processor.getMapTile(Int.MAX_VALUE, 40, 40)?.getBlock(0, 0) ?: return
                if (block.javaClass.simpleName != "ServerMapBlock") return
                val data = block.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(block) as TileData
                val chunk = processor.getMapChunk(Int.MAX_VALUE, 10, 10)
                if (chunk.toUpdateBuffers || !chunk.leafTexture.isUploaded) return
                val pixels = chunk.leafTexture.directColorBuffer ?: return
                val color = data.color(0)
                check(pixels.capacity() >= 4096 * 4) { "Native texture has unexpected dimensions" }
                if ((0 until 4096).any { index ->
                        (0..3).any { channel ->
                            val shift = intArrayOf(16, 8, 0, 24)[channel]
                            pixels.get(index * 4 + channel).toInt() and 255 != (data.color(index) ushr shift) and 255
                        }
                    }
                ) {
                    return
                }
                check(!client.level!!.chunkSource.hasChunk(40, 40)) { "Target is inside vanilla's loaded chunks" }
                val digest = digest(data)
                val cache = TileCache(FabricLoader.getInstance().gameDir.resolve("mini-map-cache"), 512L * 1024 * 1024)
                val cached = cache.read(identity, TilePos(10, 10)).orElse(null)
                evidence["digest"] = digest
                evidence["gpuUploaded"] = true
                evidence["gpuSamples"] = 4096
                evidence["outsideVanilla"] = true
                evidence["height"] = data.height(0)
                evidence["light"] = data.light(0).toInt()
                evidence["argb"] = Integer.toUnsignedString(color, 16)
                evidence["cachePresent"] = cached != null
                if (operation == "missing") {
                    (0 until 4096).all { data.color(it) == 0 && data.height(it) == 0 && data.light(it) == 0.toByte() } &&
                        cached == null && delta("MISSING") > 0
                } else {
                    val expected = command["digest"]?.asString
                    val previous = command["previousDigest"]?.asString
                    val samples = (0 until 4096).all {
                        data.height(it) == command["height"].asInt && data.light(it).toInt() == command["light"].asInt &&
                            data.color(it) ushr 24 != 0
                    }
                    samples && (expected == null || digest == expected) && (previous == null || digest != previous) &&
                        cached != null && digest(cached.tile) == digest &&
                        (command["unchanged"]?.asBoolean != true || delta("UNCHANGED") > 0 && delta("knownHashRequests") > 0)
                }
            }
            else -> error("Unknown lifecycle operation")
        }
        if (!ready) {
            stable = 0
            return
        }
        if (operation == "idle" && now - began < 1000) baseline = FixturePackets.snapshot()
        if (stable == 0L) stable = now
        if (now - stable < (command["holdMs"]?.asLong ?: 500)) return
        val counts = FixturePackets.snapshot()
        check(counts.getOrDefault("staleParts", 0) == 0L) { "Received a tile after its session was revoked" }
        evidence["counts"] = counts
        evidence["delta"] = (counts.keys + baseline.keys).associateWith { delta(it) }
        evidence["dimension"] = client.level!!.dimension().identifier().toString()
        if (identity != null) {
            evidence["cacheIdentity"] = identity.key()
            evidence["serverFolder"] = processor?.currentWorldId ?: ""
            evidence["worldFolder"] = processor?.currentMWId ?: ""
        }
        write(root.resolve("client-ack.json"), evidence)
        acknowledged = true
    }

    private fun delta(name: String): Long = FixturePackets.snapshot().getOrDefault(name, 0) - baseline.getOrDefault(name, 0)

    private fun finish(client: Minecraft, passed: Boolean, detail: String) {
        done = true
        write(
            Path.of(requireNotNull(System.getenv("MINIMAP_TEST_REPORT"))),
            mapOf(
                "status" to if (passed) "PASS" else "FAIL",
                "detail" to detail,
            ),
        )
        client.stop()
    }

    private fun write(path: Path, value: Any) {
        val temporary = path.resolveSibling(path.fileName.toString() + ".pending")
        Files.writeString(temporary, gson.toJson(value))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun digest(data: TileData): String {
        val bytes = ByteBuffer.allocate(4096 * 7)
        for (index in 0 until 4096) bytes.putInt(data.color(index)).putShort(data.height(index).toShort()).put(data.light(index))
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
    }
}
