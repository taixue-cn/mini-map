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

    private fun request(): Wire.Request = sent.filterIsInstance<Wire.Request>().last()
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
            pump { sent.any { it is Wire.Request } }
            val old = request()
            sync.offer(offer("B"))
            sync.view(true, listOf(pos))
            sync.receive(part(old, encoded()), System.currentTimeMillis())
            assertTrue(displayed.isEmpty())
            assertTrue(deleted.isEmpty())
            sent.clear()
            sync.tick(System.currentTimeMillis())
            pump { sent.any { it is Wire.Request } }
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
            pump { sent.any { it is Wire.Request } }
            val request = request()
            assertArrayEquals(data.hash, request.knownHash)
            assertFalse(displayed.isEmpty())
            sync.receive(Wire.Status(offer.session, request.requestId, pos, Wire.Result.MISSING), System.currentTimeMillis())
            assertEquals(listOf(pos), deleted)
            sync.view(false, emptyList())
            sent.clear()
            sync.view(true, listOf(pos))
            sync.tick(System.currentTimeMillis() + 1000)
            pump { sent.any { it is Wire.Request } }
            assertEquals(0, request().knownHash.size)
            assertTrue(errors.isEmpty())
        }
    }
}
