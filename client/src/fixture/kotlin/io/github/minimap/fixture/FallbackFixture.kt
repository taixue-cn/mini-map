package io.github.minimap.fixture

import io.github.minimap.fabric.MiniMapClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import java.nio.file.Files
import java.nio.file.Path

class FallbackFixture : ClientModInitializer {
    private var ticks = 0
    private var connecting = false
    private var done = false

    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (done || ++ticks % 20 != 0) return@register
            if (client.level == null && !connecting && ticks >= 100) {
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
            val passed = client.level != null && MiniMapClient.identity() == null
            if ((ticks < 400 || !passed) && ticks < 1600) return@register
            done = true
            Files.writeString(
                Path.of(System.getenv("MINIMAP_TEST_REPORT")),
                """{"status":"${if (passed) "PASS" else "FAIL"}","detail":"Normal multiplayer without Xaero"}""",
            )
            client.stop()
        }
    }
}
