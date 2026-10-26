package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Client-thread session state; one bounded worker handles storage and decompression. */
class MapSync(
    private val sender: (Wire.Message) -> Unit,
    private val clientThread: (Runnable) -> Unit,
    private val errors: (Exception) -> Unit,
    private val display: Display,
    private val cache: TileCache,
) : AutoCloseable {
    interface Display {
        fun tile(pos: TilePos, data: TileData)
        fun missing(pos: TilePos)
    }

    private class Pending(val pos: TilePos, val start: Long) {
        val assembly = TileAssembly()
        var decoding = false
    }

    private val worker = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        { action -> Thread(action, "minimap-cache").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val pending = mutableMapOf<Int, Pending>()
    private val loaded = linkedMapOf<TilePos, TileData>()
    private val checked = mutableMapOf<TilePos, Long>()
    private var visible = emptyList<TilePos>()
    private var open = false
    private var sequence = 0
    private var renderCursor = 0
    private var requestCursor = 0
    var offer: Wire.Offer? = null
        private set

    fun offer(value: Wire.Offer) {
        reset()
        CacheIdentity.of(value).key()
        offer = value
    }

    fun reset() {
        offer = null
        open = false
        visible = emptyList()
        pending.clear()
        loaded.clear()
        checked.clear()
        requestCursor = 0
    }

    fun view(isOpen: Boolean, positions: List<TilePos>) {
        val session = offer ?: return
        if (open != isOpen) {
            open = isOpen
            sender(Wire.Open(session.session, open))
            pending.clear()
            checked.clear()
        }
        if (!open) {
            visible = emptyList()
            loaded.clear()
            return
        }
        val nextVisible = positions
        if (visible != nextVisible) requestCursor = 0
        visible = nextVisible
        val keep = visible.toSet()
        loaded.keys.retainAll(keep)
        checked.keys.retainAll(keep)
        val abandoned = pending.filterValues { it.pos !in keep }.keys.toList()
        for (id in abandoned) {
            pending.remove(id)
            sender(Wire.Ack(session.session, id))
        }
    }

    fun tick(now: Long) {
        val session = offer ?: return
        if (!open) return
        val expired = pending.filterValues { now - it.start > 125000 }.keys.toList()
        for (id in expired) {
            sender(Wire.Ack(session.session, id))
            pending.remove(id)
        }
        if (loaded.isNotEmpty()) {
            val entry = loaded.entries.toList()[Math.floorMod(renderCursor++, loaded.size)]
            display.tile(entry.key, entry.value)
        }
        if (pending.size >= Wire.MAX_PENDING || visible.isEmpty()) return
        val requests = mutableListOf<Wire.Request>()
        val waiting = pending.values.mapTo(mutableSetOf()) { it.pos }
        val limit = minOf(Wire.MAX_BATCH, Wire.MAX_PENDING - pending.size)
        // Fill unseen terrain before spending request slots on revalidation.
        val fresh = visible.any { it !in checked && it !in waiting }
        for (offset in visible.indices) {
            val index = (requestCursor + offset) % visible.size
            val pos = visible[index]
            if (pos in waiting || (fresh && pos in checked) || now - checked.getOrDefault(pos, Long.MIN_VALUE / 2) < 10000) continue
            val id = ++sequence
            pending[id] = Pending(pos, now)
            requests += Wire.Request(session.session, id, pos, byteArrayOf())
            if (requests.size == limit) {
                requestCursor = (index + 1) % visible.size
                break
            }
        }
        if (requests.isEmpty()) return
        submit({
            val entries = requests.map { it to cache.read(CacheIdentity.of(session), it.pos).orElse(null) }
            clientThread(
                Runnable {
                    val active = entries.mapNotNull { (request, entry) ->
                        val pending = pending[request.requestId] ?: return@mapNotNull null
                        if (!active(session, request.requestId, pending)) return@mapNotNull null
                        entry?.let {
                            loaded[request.pos] = it.tile
                            display.tile(request.pos, it.tile)
                        }
                        request.copy(knownHash = entry?.encoded?.hash ?: byteArrayOf())
                    }
                    if (active.isNotEmpty()) sender(Wire.BatchRequest(session.session, active))
                },
            )
        }, {
            val active = requests.filter { pending[it.requestId]?.let { request -> active(session, it.requestId, request) } == true }
            if (active.isNotEmpty()) sender(Wire.BatchRequest(session.session, active))
        })
    }

    private fun active(session: Wire.Offer, id: Int, request: Pending): Boolean = open && offer === session && pending[id] === request

    fun receive(message: Wire.Message, now: Long) {
        val session = offer ?: return
        when {
            message is Wire.Revoke && message.session == session.session -> reset()
            message is Wire.Status && message.session == session.session -> {
                val request = pending[message.requestId] ?: return
                if (request.pos != message.pos) return
                pending.remove(message.requestId)
                sender(Wire.Ack(session.session, message.requestId))
                checked[message.pos] = if (message.result == Wire.Result.RETRY) now - 9000 else now
                if (message.result == Wire.Result.MISSING) {
                    loaded.remove(message.pos)
                    display.missing(message.pos)
                    submit({ cache.delete(CacheIdentity.of(session), message.pos) }, {})
                }
            }
            message is Wire.Part && message.session == session.session -> receivePart(message, session)
        }
    }

    private fun receivePart(part: Wire.Part, session: Wire.Offer) {
        val request = pending[part.requestId] ?: return
        if (request.decoding || request.pos != part.pos) return
        try {
            val encoded = request.assembly.add(part).orElse(null) ?: return
            request.decoding = true
            submit({
                val data = TileCodec.decode(encoded)
                try {
                    cache.write(CacheIdentity.of(session), part.pos, encoded)
                } catch (error: IOException) {
                    errors(error)
                }
                clientThread(
                    Runnable {
                        if (!active(session, part.requestId, request)) return@Runnable
                        pending.remove(part.requestId)
                        sender(Wire.Ack(session.session, part.requestId))
                        checked[part.pos] = System.currentTimeMillis()
                        if (part.pos in visible) {
                            loaded[part.pos] = data
                            display.tile(part.pos, data)
                        }
                    },
                )
            }, { fail(part.requestId, request, session) })
        } catch (error: IOException) {
            errors(error)
            fail(part.requestId, request, session)
        }
    }

    private fun fail(id: Int, request: Pending, session: Wire.Offer) {
        if (active(session, id, request)) {
            pending.remove(id)
            sender(Wire.Ack(session.session, id))
        }
    }

    private fun submit(action: () -> Unit, failed: () -> Unit) {
        try {
            worker.execute {
                try {
                    action()
                } catch (error: Exception) {
                    errors(error)
                    clientThread(Runnable(failed))
                }
            }
        } catch (error: RejectedExecutionException) {
            errors(error)
            failed()
        }
    }

    override fun close() {
        reset()
        worker.shutdownNow()
    }
}
