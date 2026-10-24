package io.github.minimap.paper

import io.github.minimap.api.MapProvider
import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import io.github.minimap.server.TileParts
import io.github.minimap.server.TokenBucket
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Session mutations stay on the server thread; workers only load and encode immutable tiles. */
class MiniMapServer :
    JavaPlugin(),
    Listener,
    PluginMessageListener {
    internal data class Binding(
        val provider: MapProvider,
        val map: MapProvider.MapRef,
        val world: UUID,
        val worldId: String,
        val epoch: String,
        val dimension: String,
    ) {
        fun matches(current: Binding?): Boolean = current != null && provider === current.provider && this == current
    }

    private class Session(val binding: Binding, rate: Double, val version: Int) {
        val id: UUID = UUID.randomUUID()
        val bytes = TokenBucket(rate, maxOf(rate, Wire.MAX_FRAME.toDouble()), System.nanoTime())
        val requests = TokenBucket(if (version == 1) 8.0 else 2048.0, if (version == 1) 8.0 else 2048.0, System.nanoTime())
        val pending = mutableMapOf<Int, Long>()
        val outbound = ArrayDeque<Outbound>()
        var open = false
        var generation = 0
        var requestsRead = 0L
        var changed = 0L
        var missing = 0L
        var retry = 0L
        var sentBytes = 0L
    }

    private data class Outbound(val bytes: ByteArray, val generation: Int, val requestId: Int)
    private data class Cached(val expires: Long, val data: TileCodec.Encoded?)
    private data class CacheKey(val provider: MapProvider, val map: MapProvider.MapRef, val pos: TilePos)
    private val sessions = mutableMapOf<UUID, Session>()
    private val helloAt = mutableMapOf<UUID, Long>()
    private val cache = ConcurrentHashMap<CacheKey, CompletableFuture<Cached>>()
    private lateinit var workers: ThreadPoolExecutor
    private lateinit var blueMap: BlueMapProvider
    private lateinit var total: TokenBucket
    private lateinit var serverId: String

    @Volatile private var stopping = false
    private var ticks = 0

    override fun onEnable() {
        saveDefaultConfig()
        serverId = config.getString("server-id", "").orEmpty()
        if (serverId.isBlank()) {
            serverId = UUID.randomUUID().toString()
            config.set("server-id", serverId)
            saveConfig()
        }
        require(serverId.toByteArray(Charsets.UTF_8).size <= 256) { "server-id too long" }
        val threads = config.getInt("worker-threads", 2).coerceIn(1, 8)
        workers = ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(256),
            { action -> Thread(action, "minimap-storage").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        blueMap = BlueMapProvider(this)
        val rate = maxOf(Wire.MAX_FRAME.toDouble(), config.getDouble("total-bytes-per-second", 4194304.0))
        total = TokenBucket(rate, rate, System.nanoTime())
        for (channel in listOf(Wire.CHANNEL, Wire.LEGACY_CHANNEL)) server.messenger.registerIncomingPluginChannel(this, channel, this)
        for (channel in listOf(Wire.CHANNEL, Wire.LEGACY_CHANNEL)) server.messenger.registerOutgoingPluginChannel(this, channel)
        server.pluginManager.registerEvents(this, this)
        server.scheduler.runTaskTimer(this, Runnable { tick() }, 1, 1)
        logger.info("MiniMap Server enabled; world-scoped optional protocol v${Wire.VERSION}")
    }

    override fun onDisable() {
        stopping = true
        sessions.clear()
        helloAt.clear()
        cache.clear()
        if (::workers.isInitialized) workers.shutdownNow()
    }

    @EventHandler
    fun leave(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
        helloAt.remove(event.player.uniqueId)
    }

    @EventHandler
    fun change(event: PlayerChangedWorldEvent) = revoke(event.player)

    private fun revoke(player: Player) {
        val previous = sessions.remove(player.uniqueId) ?: return
        send(player, Wire.Revoke(previous.id), previous.version)
    }

    private fun permitted(player: Player): Boolean {
        val path = "worlds.${player.world.name}"
        val permission = config.getString("$path.permission", "").orEmpty()
        return player.hasPermission("minimap.use") && config.getBoolean("$path.enabled", true) &&
            (permission.isBlank() || player.hasPermission(permission))
    }

    private fun resolve(player: Player): Binding? {
        if (!permitted(player)) return null
        val world = player.world
        val reference = MapProvider.WorldRef(world.uid, world.name, world.key.toString(), world.worldFolder.toPath())
        val providers = server.servicesManager.getRegistrations(MapProvider::class.java).sortedByDescending { it.priority.ordinal }
        for (registration in providers) {
            val provider = registration.provider
            try {
                val found = bind(provider, reference)
                if (found != null || provider.authoritative(reference)) return found
            } catch (error: RuntimeException) {
                // A failed authority check must not fall through to a less restrictive provider.
                logger.warning("MapProvider failed to resolve current world: $error")
                return null
            }
        }
        return bind(blueMap, reference)
    }

    private fun bind(provider: MapProvider, world: MapProvider.WorldRef): Binding? {
        val map = provider.resolve(world).orElse(null) ?: return null
        val path = "worlds.${world.name}"
        return Binding(
            provider,
            map,
            world.uid,
            config.getString("$path.id", provider.cacheId(world, map))!!,
            config.getString("$path.epoch", map.epoch)!!,
            world.dimension,
        )
    }

    private fun current(player: Player, session: Session): Boolean {
        if (sessions[player.uniqueId] !== session || !player.isOnline || session.binding.world != player.world.uid) return false
        // A map can remain available after it is rebound to another world or provider.
        return session.binding.matches(resolve(player)) && available(session.binding)
    }

    private fun available(binding: Binding): Boolean = try {
        binding.provider.available(binding.map)
    } catch (_: RuntimeException) {
        false
    }

    override fun onPluginMessageReceived(channel: String, player: Player, data: ByteArray) {
        if (channel != Wire.CHANNEL && channel != Wire.LEGACY_CHANNEL) return
        val version = if (channel == Wire.LEGACY_CHANNEL) 1 else Wire.VERSION
        try {
            val message = Wire.decode(data, version)
            if (message is Wire.Hello) {
                val now = System.nanoTime()
                if (now - helloAt.getOrDefault(player.uniqueId, 0L) < 1_000_000_000L) return
                helloAt[player.uniqueId] = now
                if (message.version != version) return
                revoke(player)
                val binding = resolve(player) ?: return
                val rate = maxOf(Wire.MAX_FRAME.toDouble(), config.getDouble("player-bytes-per-second", 131072.0))
                val session = Session(binding, rate, version)
                sessions[player.uniqueId] = session
                send(player, Wire.Offer(session.id, serverId, binding.worldId, binding.epoch, binding.dimension), version)
                return
            }
            val session = sessions[player.uniqueId] ?: return
            if (session.version != version) return
            if (!current(player, session)) {
                revoke(player)
                return
            }
            when {
                message is Wire.Open && message.session == session.id -> {
                    if (session.open != message.open) {
                        session.open = message.open
                        session.generation++
                        session.pending.clear()
                        session.outbound.clear()
                    }
                }
                message is Wire.Ack && message.session == session.id -> session.pending.remove(message.requestId)
                message is Wire.Request && message.session == session.id && session.open -> request(player, session, message)
                message is Wire.BatchRequest && message.session == session.id && session.open && version >= 2 -> {
                    for (request in message.requests) request(player, session, request)
                }
            }
        } catch (error: IOException) {
            logger.fine("Rejected malformed MiniMap message: ${error.message}")
        } catch (error: IllegalArgumentException) {
            logger.fine("Rejected malformed MiniMap message: ${error.message}")
        }
    }

    private fun request(player: Player, session: Session, request: Wire.Request) {
        if (request.knownHash.isNotEmpty() && request.knownHash.size != 32) return
        if (session.pending.size >= (if (session.version == 1) 4 else Wire.MAX_PENDING) || request.requestId in session.pending ||
            !session.requests.take(1, System.nanoTime())
        ) {
            return
        }
        session.requestsRead++
        session.pending[request.requestId] = System.nanoTime()
        val generation = session.generation
        load(CacheKey(session.binding.provider, session.binding.map, request.pos)).whenComplete { result, error ->
            if (stopping) return@whenComplete
            server.scheduler.runTask(
                this,
                Runnable {
                    if (!current(player, session) || !session.open || session.generation != generation ||
                        request.requestId !in session.pending
                    ) {
                        return@Runnable
                    }
                    val tile = result?.data
                    if (error != null) {
                        session.retry++
                        logger.fine("MiniMap tile read failed: $error")
                    } else if (tile == null) {
                        session.missing++
                    } else if (!MessageDigest.isEqual(tile.hash, request.knownHash)) {
                        session.changed++
                    }
                    when {
                        error != null -> enqueue(session, Wire.Status(session.id, request.requestId, request.pos, Wire.Result.RETRY))
                        tile == null -> enqueue(session, Wire.Status(session.id, request.requestId, request.pos, Wire.Result.MISSING))
                        MessageDigest.isEqual(
                            tile.hash,
                            request.knownHash,
                        ) -> enqueue(session, Wire.Status(session.id, request.requestId, request.pos, Wire.Result.UNCHANGED))
                        else -> TileParts.split(session.id, request.requestId, request.pos, tile).forEach { enqueue(session, it) }
                    }
                },
            )
        }
    }

    private fun load(key: CacheKey): CompletableFuture<Cached> {
        val now = System.nanoTime()
        return cache.compute(key) { _, old ->
            if (old != null && !old.isCompletedExceptionally && (!old.isDone || old.getNow(null).expires > now)) {
                old
            } else {
                try {
                    CompletableFuture.supplyAsync({
                        try {
                            val samples = key.provider.readScaled(key.map, key.pos.x, key.pos.z, key.pos.level).orElse(null)
                            val tile = samples?.let { TileData(it.argb(), it.height(), it.blockLight()) }
                            Cached(System.nanoTime() + 2_000_000_000L, tile?.takeUnless { it.empty() }?.let(TileCodec::encode))
                        } catch (error: IOException) {
                            throw CompletionException(error)
                        }
                    }, workers)
                } catch (error: RejectedExecutionException) {
                    CompletableFuture.failedFuture(error)
                }
            }
        }!!
    }

    private fun enqueue(session: Session, message: Wire.Message) {
        try {
            if (session.outbound.size <
                Wire.MAX_PENDING * Wire.MAX_PARTS
            ) {
                session.outbound.addLast(
                    Outbound(
                        Wire.encode(message, session.version),
                        session.generation,
                        when (message) {
                            is Wire.Part -> message.requestId
                            is Wire.Status -> message.requestId
                            else -> error("Unexpected queued message")
                        },
                    ),
                )
            }
        } catch (error: IOException) {
            logger.warning("Cannot encode MiniMap response: $error")
        }
    }

    private fun send(player: Player, message: Wire.Message, version: Int) {
        try {
            player.sendPluginMessage(this, if (version == 1) Wire.LEGACY_CHANNEL else Wire.CHANNEL, Wire.encode(message, version))
        } catch (error: IOException) {
            logger.fine("MiniMap send failed: $error")
        } catch (error: IllegalArgumentException) {
            logger.fine("MiniMap send failed: $error")
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        val players = server.onlinePlayers.toMutableList()
        ticks++
        // Rotate the first sender to keep a busy player from consuming every global token.
        if (players.isNotEmpty()) java.util.Collections.rotate(players, ticks % players.size)
        for (player in players) {
            val session = sessions[player.uniqueId] ?: continue
            if (!current(player, session)) {
                revoke(player)
                continue
            }
            if (ticks % 600 == 0 && session.open &&
                session.requestsRead > 0
            ) {
                logger.info(
                    "MiniMap ${player.name} world=${player.world.name} protocol=${session.version} requested=${session.requestsRead} changed=${session.changed} missing=${session.missing} retry=${session.retry} bytes=${session.sentBytes} pending=${session.pending.size}",
                )
            }
            session.pending.entries.removeIf { now - it.value > 120_000_000_000L }
            var sent = 0
            while (sent < 32 && session.outbound.isNotEmpty()) {
                val next = session.outbound.first()
                if (next.generation != session.generation || next.requestId !in session.pending) {
                    session.outbound.removeFirst()
                    continue
                }
                val size = next.bytes.size
                if (!session.bytes.available(size, now) || !total.available(size, now)) break
                session.bytes.take(size, now)
                total.take(size, now)
                session.outbound.removeFirst()
                player.sendPluginMessage(this, if (session.version == 1) Wire.LEGACY_CHANNEL else Wire.CHANNEL, next.bytes)
                session.sentBytes += size
                sent++
            }
        }
        if (ticks % 200 == 0 && cache.size > 128) {
            cache.entries.removeIf { it.value.isDone && (it.value.isCompletedExceptionally || it.value.getNow(null).expires < now) }
        }
    }
}
