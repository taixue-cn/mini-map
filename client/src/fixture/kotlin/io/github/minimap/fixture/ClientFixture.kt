package io.github.minimap.fixture

import com.google.gson.JsonObject
import io.github.minimap.fabric.MiniMapClient
import io.github.minimap.protocol.TileData
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Exercise native Xaero buffers with a tile outside vanilla's loaded chunk range. */
class ClientFixture : ClientModInitializer {
    private var started = 0L
    private var connecting = false
    private var opened = false
    private var done = false
    private var initialSeen = false
    private var changeSeen = false
    private var ticks = 0

    override fun onInitializeClient() {
        started = System.currentTimeMillis()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done || ++ticks % 20 != 0) return@register
            try {
                inspect(client)
            } catch (error: Throwable) {
                finish(client, false, error.toString())
            }
        }
    }

    private fun inspect(client: Minecraft) {
        if (System.currentTimeMillis() - started > 120000) {
            finish(client, false, "timeout")
            return
        }
        if (client.level == null) {
            if (!connecting && ticks > 100) {
                connecting = true
                val address = System.getenv("MINIMAP_TEST_SERVER")
                ConnectScreen.startConnecting(
                    TitleScreen(),
                    client,
                    ServerAddress.parseString(address),
                    ServerData("isolated", address, ServerData.Type.OTHER),
                    false,
                    null,
                )
            }
            return
        }
        client.options.renderDistance().set(2)
        if (System.getenv("MINIMAP_TEST_MODE") == "fallback") {
            if (ticks > 400) finish(client, MiniMapClient.identity() == null, "normal multiplayer without a MiniMap session")
            return
        }
        val identity = MiniMapClient.identity() ?: return
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        Files.writeString(
            report(".debug"),
            "usable=${processor.isMapWorldUsable} root=${processor.currentWorldId} mw=${processor.currentMWId} expected=${identity.worldFolder()} cave=${processor.currentCaveLayer} screen=${client.gui.screen()}",
        )
        if (!processor.isMapWorldUsable || identity.worldFolder() != processor.currentMWId) return
        if (!opened) {
            client.gui.setScreen(GuiMap(null, null, processor, client.player))
            opened = true
        }
        val screen = client.gui.screen()
        if (screen is GuiMap) {
            GuiMap::class.java.getDeclaredField("attachedCamera").apply { isAccessible = true }.setBoolean(null, false)
            for (field in listOf("cameraX", "cameraZ")) {
                GuiMap::class.java.getDeclaredField(field).apply { isAccessible = true }.setDouble(screen, 672.0)
            }
        }
        val tile = processor.getMapTile(Int.MAX_VALUE, 40, 40) ?: return
        val target = processor.getLeafMapRegion(Int.MAX_VALUE, 1, 1, false) ?: return
        val block = tile.getBlock(0, 0) ?: return
        if (block.javaClass.simpleName != "ServerMapBlock") return
        val data = block.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(block) as TileData
        val updates = System.getenv("MINIMAP_TEST_MODE") == "updates"
        val customProvider = System.getenv("MINIMAP_TEST_MODE") == "provider"
        val expectedColor = if (customProvider) System.getenv("MINIMAP_TEST_ARGB").toLong(16).toInt() else 0xffe04090.toInt()
        val expectedHeight = if (customProvider) System.getenv("MINIMAP_TEST_HEIGHT").toInt() else 71
        val expectedLight = if (customProvider) System.getenv("MINIMAP_TEST_LIGHT").toByte() else 12.toByte()
        if (updates) {
            when {
                data.color(0) == 0xffe04090.toInt() && data.height(0) == 71 && data.light(0) == 12.toByte() -> initialSeen = true
                data.color(0) == 0xff30b050.toInt() && data.height(0) == 72 && data.light(0) == 10.toByte() -> changeSeen = true
                data.color(0) != 0 -> error("Unexpected updated samples")
            }
        } else {
            check(data.color(0) == expectedColor && data.height(0) == expectedHeight && data.light(0) == expectedLight) {
                "Surface samples changed"
            }
        }
        check(!client.level!!.chunkSource.hasChunk(40, 40)) { "Remote tile is inside vanilla loaded chunks" }
        val chunk = processor.getMapChunk(Int.MAX_VALUE, 10, 10)
        Files.writeString(
            report(".debug"),
            " update=${chunk.toUpdateBuffers} uploaded=${chunk.leafTexture.isUploaded} prepared=${target.isAllCachePrepared}",
            StandardOpenOption.APPEND,
        )
        if (chunk.toUpdateBuffers) return
        val pixels = chunk.leafTexture.directColorBuffer ?: return
        if (!chunk.leafTexture.isUploaded) return
        if (updates) {
            if (!initialSeen || !changeSeen || data.color(0) != 0 || pixels.get(3).toInt() != 0) return
            finish(client, true, "Native Xaero received initial tile, incremental color/height/light update, then transparent deletion")
            return
        }
        check(
            (pixels.get(0).toInt() and 255) == ((expectedColor ushr 16) and 255) &&
                (pixels.get(1).toInt() and 255) == ((expectedColor ushr 8) and 255) &&
                (pixels.get(2).toInt() and 255) == (expectedColor and 255),
        ) {
            "Native texture color differs"
        }
        finish(client, true, "Remote samples imported into native Xaero outside loaded chunks; stable cache identity active")
    }

    private fun report(suffix: String = ""): Path = Path.of(System.getenv("MINIMAP_TEST_REPORT") + suffix)

    private fun finish(client: Minecraft, passed: Boolean, detail: String) {
        done = true
        val path = report()
        Files.createDirectories(path.parent)
        val json = JsonObject().apply {
            addProperty("status", if (passed) "PASS" else "FAIL")
            addProperty("detail", detail)
        }
        Files.writeString(path, json.toString())
        client.stop()
    }
}
