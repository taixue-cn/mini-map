package io.github.minimap.fixture

import com.google.gson.JsonObject
import io.github.minimap.fabric.MiniMapClient
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

/** Actual client fixture: the external harness also checks captured display pixels throughout each phase. */
class StabilityFixture : ClientModInitializer {
    private val started = System.currentTimeMillis()
    private var connected = false
    private var warmAt = 0L
    private var finished = false
    private var ticks = 0

    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (finished) return@register
            try {
                inspect(client)
            } catch (error: Throwable) {
                finish(client, false, error.toString())
            }
        }
    }

    private fun inspect(client: Minecraft) {
        val now = System.currentTimeMillis()
        if (now - started > 150000) {
            finish(client, false, "stability timeout")
            return
        }
        if (client.level == null) {
            if (!connected && ++ticks > 100) {
                connected = true
                val address = System.getenv("MINIMAP_TEST_SERVER")
                ConnectScreen.startConnecting(
                    TitleScreen(),
                    client,
                    ServerAddress.parseString(address),
                    ServerData("stability", address, ServerData.Type.OTHER),
                    false,
                    null,
                )
            }
            return
        }
        val identity = MiniMapClient.identity() ?: return
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        if (!processor.isMapWorldUsable || identity.worldFolder() != processor.currentMWId) return
        val elapsed = if (warmAt == 0L) 0L else now - warmAt
        val phase = when {
            warmAt == 0L -> "warming"
            elapsed < 6000 -> "steady"
            elapsed < 8000 -> "away"
            elapsed < 12000 -> "return"
            elapsed < 14000 -> "closed"
            elapsed < 18000 -> "reopen"
            elapsed < 30000 -> "border"
            else -> "done"
        }
        if (phase == "closed") {
            if (client.gui.screen() is GuiMap) client.gui.setScreen(null)
        } else {
            if (client.gui.screen() !is GuiMap) client.gui.setScreen(GuiMap(null, null, processor, client.player))
            val screen = client.gui.screen() as GuiMap
            GuiMap::class.java.getDeclaredField("attachedCamera").apply { isAccessible = true }.setBoolean(null, false)
            val coordinate = when (phase) {
                "away" -> 4000.0
                "border" -> 0.0
                else -> 672.0
            }
            for (field in listOf("cameraX", "cameraZ")) {
                GuiMap::class.java.getDeclaredField(field).apply {
                    isAccessible = true
                }.setDouble(screen, coordinate)
            }
            val scale = if (phase == "return") 0.269 else 0.802
            GuiMap::class.java.getDeclaredField("userScale").apply { isAccessible = true }.setDouble(screen, scale)
            GuiMap::class.java.getDeclaredField("destScale").apply { isAccessible = true }.setDouble(null, scale)
            GuiMap::class.java.getDeclaredField("zoomAnim").apply { isAccessible = true }.set(screen, null)
        }
        val chunk = processor.getMapChunk(Int.MAX_VALUE, 10, 10)
        if (warmAt == 0L && chunk?.leafTexture?.isUploaded == true) warmAt = now
        val state = JsonObject().apply {
            addProperty("phase", phase)
            addProperty("elapsed", elapsed)
            addProperty("uploaded", chunk?.leafTexture?.isUploaded ?: false)
            addProperty("user_scale", (client.gui.screen() as? GuiMap)?.userScale)
        }
        Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT") + ".frame"), state.toString())
        if (phase == "done") finish(client, true, "steady, pan-return, close-reopen and native border phases completed")
    }

    private fun finish(client: Minecraft, passed: Boolean, detail: String) {
        finished = true
        Files.writeString(
            Path.of(System.getenv("MINIMAP_TEST_REPORT")),
            JsonObject().apply {
                addProperty("status", if (passed) "PASS" else "FAIL")
                addProperty("detail", detail)
            }.toString(),
        )
        client.stop()
    }
}
