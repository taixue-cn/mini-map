package io.github.minimap.fixture

import io.github.minimap.fabric.MiniMapClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import xaero.map.WorldMapSession
import xaero.map.gui.GuiMap
import java.nio.file.Files
import java.nio.file.Path

/** Uses only the released v1 client's API; CPU texture buffers may be freed after GPU upload. */
class LegacyFixture : ClientModInitializer {
    private var ticks = 0
    private var connecting = false
    private var opened = false
    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (++ticks % 20 != 0) return@register
            check(ticks < 2400) { "Legacy client did not receive a native texture" }
            if (client.level == null) {
                if (!connecting && ticks >= 100) {
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
                return@register
            }
            if (MiniMapClient.identity() == null) return@register
            val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return@register
            if (!processor.isMapWorldUsable) return@register
            if (!opened) {
                client.gui.setScreen(GuiMap(null, null, processor, client.player))
                opened = true
            }
            val screen = client.gui.screen() as? GuiMap ?: return@register
            GuiMap::class.java.getDeclaredField("attachedCamera").apply { isAccessible = true }.setBoolean(null, false)
            for (name in listOf("cameraX", "cameraZ")) {
                GuiMap::class.java.getDeclaredField(name).apply { isAccessible = true }.setDouble(screen, 672.0)
            }
            val block = processor.getMapTile(Int.MAX_VALUE, 40, 40)?.getBlock(0, 0) ?: return@register
            if (block.javaClass.simpleName != "ServerMapBlock") return@register
            val data = block.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(block)
            check(data.javaClass.getMethod("color", Int::class.javaPrimitiveType).invoke(data, 0) == 0xffe04090.toInt())
            val texture = processor.getMapChunk(Int.MAX_VALUE, 10, 10)?.leafTexture ?: return@register
            if (!texture.isUploaded || texture.glColorTexture == null) return@register
            check(!client.level!!.chunkSource.hasChunk(40, 40))
            Files.writeString(
                Path.of(System.getenv("MINIMAP_TEST_REPORT")),
                """{"status":"PASS","detail":"Released v1 client completed handshake and uploaded verified remote samples from v2 server"}""",
            )
            client.stop()
        }
    }
}
