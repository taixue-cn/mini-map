package io.github.minimap.fabric

import io.github.minimap.client.CacheIdentity
import io.github.minimap.client.MapSync
import io.github.minimap.client.TileCache
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.io.IOException

class MiniMapClient : ClientModInitializer {
    interface Bridge : MapSync.Display {
        fun reset()
        fun open(): Boolean
        fun visible(): List<TilePos>
    }

    private var world: ClientLevel? = null
    private lateinit var sync: MapSync
    private lateinit var bridge: Bridge
    private var helloAt = 0L
    private var errorAt = 0L

    override fun onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(MapFrame.TYPE, MapFrame.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(MapFrame.TYPE, MapFrame.CODEC)
        if (!XaeroMixinPlugin.supported()) {
            logger.info("MiniMap sync inactive: supported Xaero World Map 1.47.1 is not installed.")
            return
        }
        bridge = XaeroBridge()
        sync = MapSync(
            ::send,
            { Minecraft.getInstance().execute(it) },
            ::error,
            bridge,
            TileCache(FabricLoader.getInstance().gameDir.resolve("mini-map-cache"), 512L * 1024 * 1024),
        )
        ClientPlayNetworking.registerGlobalReceiver(MapFrame.TYPE) { frame, context ->
            try {
                val message = Wire.decode(frame.bytes, Wire.VERSION)
                context.client().execute { receive(message) }
            } catch (error: IOException) {
                error(error)
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> reset(client) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { reset(client) } }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.level !== world) reset(client)
            if (client.level == null || client.connection == null) return@register
            val now = System.currentTimeMillis()
            if (sync.offer == null) {
                if (now - helloAt >= 3000 && ClientPlayNetworking.canSend(MapFrame.TYPE)) {
                    helloAt = now
                    send(Wire.Hello(Wire.VERSION))
                }
                return@register
            }
            val open = bridge.open()
            sync.view(open, if (open) bridge.visible() else emptyList())
            sync.tick(now)
        }
    }

    private fun reset(client: Minecraft) {
        bridge.reset()
        identity = null
        dimension = null
        sync.reset()
        world = client.level
        helloAt = 0
    }

    private fun receive(message: Wire.Message) {
        val client = Minecraft.getInstance()
        if (client.level !== world) reset(client)
        if (message is Wire.Offer) {
            val level = client.level ?: return
            if (message.dimension != level.dimension().identifier().toString()) return
            try {
                val key = CacheIdentity.of(message)
                key.key()
                sync.offer(message)
                dimension = level.dimension()
                identity = key
                logger.info("MiniMap session established for current world.")
            } catch (error: IllegalArgumentException) {
                error(error)
            }
        } else {
            sync.receive(message, System.currentTimeMillis())
            if (sync.offer == null) {
                bridge.reset()
                identity = null
                dimension = null
            }
        }
    }

    private fun send(message: Wire.Message) {
        if (!ClientPlayNetworking.canSend(MapFrame.TYPE)) return
        try {
            ClientPlayNetworking.send(MapFrame(Wire.encode(message)))
        } catch (error: IOException) {
            error(error)
        } catch (error: IllegalArgumentException) {
            error(error)
        }
    }

    @Synchronized
    private fun error(error: Exception) {
        val now = System.currentTimeMillis()
        if (now - errorAt > 10000) {
            errorAt = now
            logger.warn("MiniMap request/cache failed; it will be retried: {}", error.toString())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("mini-map-client")

        @Volatile private var identity: CacheIdentity? = null

        @Volatile private var dimension: ResourceKey<Level>? = null

        // Static entry points are used from Java mixins before Fabric creates the client initializer.
        @JvmStatic
        fun identity(): CacheIdentity? = identity

        @JvmStatic
        fun isCurrentDimension(key: ResourceKey<Level>): Boolean = identity != null && key === dimension
    }
}
