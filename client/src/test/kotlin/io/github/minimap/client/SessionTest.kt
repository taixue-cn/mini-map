package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SessionTest {
    @TempDir
    lateinit var root: Path
    private val sent = mutableListOf<Wire.Message>()
    private val displayed = mutableListOf<TileData>()
    private val deleted = mutableListOf<TilePos>()
    private val callbacks = LinkedBlockingQueue<Runnable>()
    private val errors = CopyOnWriteArrayList<Exception>()

    private fun sync(): MapSync = MapSync(
        { sent += it },
        { callbacks.add(it) },
        { errors += it },
        object : MapSync.Display {
            override fun tile(pos: TilePos, data: TileData) {
                displayed += data
            }
            override fun missing(pos: TilePos) {
                deleted += pos
            }
        },
        TileCache(root, 1000000),
    )

    private fun offer(world: String): Wire.Offer = Wire.Offer(UUID.randomUUID(), "server", world, "1", "minecraft:overworld")

    private fun pump(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) callbacks.poll(50, TimeUnit.MILLISECONDS)?.run()
        assertTrue(condition(), "async work did not complete")
    }

    private fun requests(): List<Wire.Request> = sent.flatMap { message ->
        when (message) {
            is Wire.Request -> listOf(message)
            is Wire.BatchRequest -> message.requests
            else -> emptyList()
        }
    }
    private fun request(): Wire.Request = requests().last()
    private fun encoded(): TileCodec.Encoded = TileCodec.encode(
        TileData(
            IntArray(4096) {
                0xff125633.toInt()
            },
            IntArray(4096),
            ByteArray(4096),
        ),
    )
    private fun part(request: Wire.Request, data: TileCodec.Encoded): Wire.Part = Wire.Part(
        request.session, request.requestId, request.pos, data.hash, data.rawLength,
        data.compressed, data.body.size, 0, 1, data.body,
    )

    @Test
    fun worldSwitchAndCloseRejectOutstandingResponses() {
        sync().use { sync ->
            val pos = TilePos(0, 0)
            sync.offer(offer("A"))
            sync.view(true, listOf(pos))
            sync.tick(System.currentTimeMillis())
            pump { requests().isNotEmpty() }
            val old = request()
            sync.offer(offer("B"))
            sync.view(true, listOf(pos))
            sync.receive(part(old, encoded()), System.currentTimeMillis())
            assertTrue(displayed.isEmpty())
            assertTrue(deleted.isEmpty())
            sent.clear()
            sync.tick(System.currentTimeMillis())
            pump { requests().isNotEmpty() }
            val current = request()
            sync.view(false, emptyList())
            sync.receive(part(current, encoded()), System.currentTimeMillis())
            assertTrue(displayed.isEmpty())
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun verifiedCacheGeneratesConditionalRequestAndMissingEvicts() {
        val cache = TileCache(root, 1000000)
        val pos = TilePos(0, 0)
        val offer = offer("A")
        val data = encoded()
        cache.write(CacheIdentity.of(offer), pos, data)
        sync().use { sync ->
            sync.offer(offer)
            sync.view(true, listOf(pos))
            sync.tick(System.currentTimeMillis())
            pump { requests().isNotEmpty() }
            val request = request()
            assertArrayEquals(data.hash, request.knownHash)
            assertFalse(displayed.isEmpty())
            sync.receive(Wire.Status(offer.session, request.requestId, pos, Wire.Result.MISSING), System.currentTimeMillis())
            assertEquals(listOf(pos), deleted)
            sync.view(false, emptyList())
            sent.clear()
            sync.view(true, listOf(pos))
            sync.tick(System.currentTimeMillis() + 1000)
            pump { requests().isNotEmpty() }
            assertEquals(0, request().knownHash.size)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun fullBatchesFillTheViewportWithoutAnArtificialDelay() {
        sync().use { sync ->
            val session = offer("large-world")
            val positions = List(2048) { TilePos(it % 64, it / 64) }
            sync.offer(session)
            sync.view(true, positions)
            var count = 0
            repeat(32) { batch ->
                sync.tick(10000L + batch * 50)
                pump { requests().size == count + Wire.MAX_BATCH }
                val next = requests().drop(count)
                assertEquals(positions.subList(count, count + 64), next.map { it.pos })
                count += next.size
                next.forEach { sync.receive(Wire.Status(session.session, it.requestId, it.pos, Wire.Result.MISSING), 10000L + batch * 50) }
            }
            assertEquals(positions, requests().map { it.pos })
            assertEquals(32, sent.filterIsInstance<Wire.BatchRequest>().size)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun outstandingWindowIsBoundedAndAnOldViewportIsCancelledImmediately() {
        sync().use { sync ->
            val session = offer("bounded")
            sync.offer(session)
            sync.view(true, List(512) { TilePos(it, 0) })
            sync.tick(10000)
            pump { requests().size == 64 }
            sync.tick(10050)
            pump { requests().size == 128 }
            sync.tick(10100)
            assertEquals(128, requests().size)
            sync.view(true, listOf(TilePos(-1, -1, 2)))
            assertEquals(128, sent.filterIsInstance<Wire.Ack>().size)
            sync.tick(10150)
            pump { requests().size == 129 }
            assertEquals(TilePos(-1, -1, 2), request().pos)
        }
    }

    @Test
    fun panningStartsAtTheNewViewportCenter() {
        sync().use { sync ->
            val session = offer("panning-world")
            sync.offer(session)
            sync.view(true, listOf(TilePos(0, 0)))
            sync.tick(10000)
            pump { requests().isNotEmpty() }
            val first = request()
            sync.receive(Wire.Status(session.session, first.requestId, first.pos, Wire.Result.MISSING), 10000)
            sync.view(true, listOf(TilePos(100, 100)))
            sync.tick(10150)
            pump { requests().size == 2 }
            assertEquals(TilePos(100, 100), request().pos)
            assertTrue(errors.isEmpty())
        }
    }
}
