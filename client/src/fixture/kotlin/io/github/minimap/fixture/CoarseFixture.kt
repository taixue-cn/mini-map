package io.github.minimap.fixture

import com.google.gson.JsonObject
import io.github.minimap.client.ViewportTiles
import io.github.minimap.fabric.MiniMapClient
import io.github.minimap.fabric.mixin.GuiMapAccess
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import java.nio.file.Files
import java.nio.file.Path

/** Actual distributed client: far corner coarse textures, then detailed native samples outside loaded chunks. */
class CoarseFixture : ClientModInitializer {
    private val started = System.currentTimeMillis()
    private var ticks = 0
    private var connecting = false
    private var opened = false
    private var detailed = false
    private var done = false
    private var coarseAt = 0L
    private var captured = false
    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done || ++ticks % 10 != 0) return@register
            try {
                inspect(client)
            } catch (failure: Throwable) {
                finish(client, false, failure.stackTraceToString())
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
        val identity = MiniMapClient.identity() ?: return
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        if (!processor.isMapWorldUsable || identity.worldFolder() != processor.currentMWId) return
        if (!opened) {
            client.gui.setScreen(GuiMap(null, null, processor, client.player))
            opened = true
        }
        val screen = client.gui.screen() as? GuiMap ?: return
        GuiMap::class.java.getDeclaredField("attachedCamera").apply { isAccessible = true }.setBoolean(null, false)
        for (field in listOf("cameraX", "cameraZ")) {
            GuiMap::class.java.getDeclaredField(field).apply {
                isAccessible = true
            }.setDouble(screen, 4000.0)
        }
        val scale = if (detailed) 1.0 else 0.195
        GuiMap::class.java.getDeclaredField("userScale").apply { isAccessible = true }.setDouble(screen, scale)
        GuiMap::class.java.getDeclaredField("destScale").apply { isAccessible = true }.setDouble(null, scale)
        GuiMap::class.java.getDeclaredField("zoomAnim").apply { isAccessible = true }.set(screen, null)
        if (!detailed) {
            val level = processor.mapSaveLoad.mainTextureLevel
            if (level != 2) return
            val view = screen as GuiMapAccess
            val positions = ViewportTiles.visible(
                view.`minimap$cameraX`(),
                view.`minimap$cameraZ`(),
                client.window.width,
                client.window.height,
                view.`minimap$scale`(),
                level,
            )
            var ready = 0
            for (pos in positions) {
                val region = processor.getLeveledRegion(Int.MAX_VALUE, pos.x shr 3, pos.z shr 3, level) ?: continue
                if (!region.hasTextures()) continue
                val texture = region.getTexture(pos.x and 7, pos.z and 7) ?: continue
                if (texture.javaClass.simpleName == "ServerBranchTexture" && texture.isUploaded && texture.glColorTexture != null) ready++
            }
            Files.writeString(
                Path.of(System.getenv("MINIMAP_TEST_REPORT") + ".debug"),
                "level=$level ready=$ready/${positions.size} first=${positions.first()} last=${positions.last()} scale=${view.`minimap$scale`()} width=${client.window.width} height=${client.window.height}",
            )
            if (ready != positions.size) return
            if (coarseAt == 0L) {
                coarseAt = System.currentTimeMillis()
                Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT") + ".coarse-ready"), "native coarse texture uploaded")
            }
            if (!captured && System.currentTimeMillis() - coarseAt >= 2000) {
                captured = true
                Screenshot.grab(client.gameDirectory, "coarse.png", client.gameRenderer.mainRenderTarget(), 1) {}
            }
            if (System.currentTimeMillis() - coarseAt >= 5000 &&
                Files.exists(client.gameDirectory.toPath().resolve("screenshots/coarse.png"))
            ) {
                detailed = true
            }
        } else {
            if (processor.mapSaveLoad.mainTextureLevel != 0) return
            val block = processor.getMapTile(Int.MAX_VALUE, 250, 250)?.getBlock(0, 0) ?: return
            if (block.javaClass.simpleName != "ServerMapBlock") return
            check(!client.level!!.chunkSource.hasChunk(250, 250))
            finish(
                client,
                true,
                "Full viewport corner uploaded as a native coarse texture, zoom-in loaded detail; coarse_ms=${coarseAt - started}",
            )
        }
    }
    private fun finish(client: Minecraft, passed: Boolean, detail: String) {
        done = true
        val report = JsonObject().apply {
            addProperty("status", if (passed) "PASS" else "FAIL")
            addProperty("detail", detail)
        }
        Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT")), report.toString())
        client.stop()
    }
}
