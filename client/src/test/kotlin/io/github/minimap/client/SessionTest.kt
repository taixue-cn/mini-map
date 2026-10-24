package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TileData
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
    private var current: MapSync? = null
    private val positionsDisplayed = mutableSetOf<TilePos>()

    private fun sync(): MapSync = MapSync(
        { sent += it },
        { callbacks.add(it) },
        { errors += it },
        object : MapSync.Display {
            override fun tile(pos: TilePos, data: TileData) {
                displayed += data
                positionsDisplayed += pos
            }
            override fun missing(pos: TilePos) {
                deleted += pos
            }
        },
        TileCache(root, 1000000),
    ).also { current = it }

    private fun offer(world: String): Wire.Offer = Wire.Offer(UUID.randomUUID(), "server", world, "1", "minecraft:overworld")

    private fun pump(now: Long = System.currentTimeMillis(), condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            callbacks.poll(10, TimeUnit.MILLISECONDS)?.run()
            current?.tick(now)
        }
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
            val reopened = System.currentTimeMillis() + 1000
            sync.tick(reopened)
            pump(reopened) { sent.any { it is Wire.Request } }
            assertEquals(0, request().knownHash.size)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun largeViewportIsFilledBeforeItsFirstTilesAreRefreshed() {
        sync().use { sync ->
            val session = offer("large-world")
            val positions = List(128) { TilePos(it % 16, it / 16) }
            sync.offer(session)
            var requests = 0
            for (index in positions.indices) {
                // The GUI rebuilds an equal visible list on every tick.
                sync.view(true, positions.toList())
                val now = 10000L + index * 150
                sync.tick(now)
                pump(now) { sent.filterIsInstance<Wire.Request>().size > requests }
                requests++
                val request = request()
                assertEquals(positions[index], request.pos, "an expired near tile must not starve an unrequested visible tile")
                sync.receive(Wire.Status(session.session, request.requestId, request.pos, Wire.Result.MISSING), now)
            }
            sync.tick(10000L + positions.size * 150)
            pump(10000L + positions.size * 150) { sent.filterIsInstance<Wire.Request>().size > requests }
            assertEquals(positions.first(), request().pos)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun panningStartsAtTheNewViewportCenter() {
        sync().use { sync ->
            val session = offer("panning-world")
            sync.offer(session)
            sync.view(true, List(4) { TilePos(it, 0) })
            sync.tick(10000)
            pump(10000) { sent.any { it is Wire.Request } }
            val first = request()
            sync.receive(Wire.Status(session.session, first.requestId, first.pos, Wire.Result.MISSING), 10000)
            sync.view(true, List(4) { TilePos(100 + it, 100) })
            sync.tick(10150)
            pump(10150) { sent.filterIsInstance<Wire.Request>().size == 2 }
            assertEquals(TilePos(100, 100), request().pos)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun unchangedRefreshDoesNotReplaceTheDisplayedSnapshot() {
        val session = offer("stable-view")
        val pos = TilePos(0, 0)
        val encoded = encoded()
        TileCache(root, 1000000).write(CacheIdentity.of(session), pos, encoded)
        sync().use { sync ->
            sync.offer(session)
            sync.view(true, listOf(pos))
            val now = System.currentTimeMillis()
            sync.tick(now)
            pump { sent.any { it is Wire.Request } }
            val original = displayed.last()
            val first = request()
            sync.receive(Wire.Status(session.session, first.requestId, pos, Wire.Result.UNCHANGED), now)
            sync.tick(now + 11000)
            pump(now + 11000) { sent.filterIsInstance<Wire.Request>().size == 2 }
            assertSame(original, displayed.last(), "conditional refresh must not rebuild native textures from an identical cache entry")
            assertArrayEquals(encoded.hash, request().knownHash)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun warmDiskViewportDisplaysWithoutWaitingForNetworkReplies() {
        val session = offer("disk-first")
        val positions = List(48) { TilePos(it % 8, it / 8) }
        val cache = TileCache(root, 1000000)
        positions.forEach { cache.write(CacheIdentity.of(session), it, encoded()) }
        sync().use { sync ->
            sync.offer(session)
            sync.view(true, positions)
            sync.tick(10000)
            pump(10000) { positionsDisplayed.containsAll(positions) }
            assertEquals(1, sent.filterIsInstance<Wire.Request>().size)
            assertTrue(deleted.isEmpty())
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun panningAndClosingKeepVerifiedSnapshotsReadyForImmediateRedisplay() {
        val session = offer("warm-pan")
        val pos = TilePos(0, 0)
        TileCache(root, 1000000).write(CacheIdentity.of(session), pos, encoded())
        sync().use { sync ->
            sync.offer(session)
            sync.view(true, listOf(pos))
            sync.tick(10000)
            pump(10000) { displayed.isNotEmpty() && sent.any { it is Wire.Request } }
            val snapshot = displayed.last()
            val first = request()
            sync.receive(Wire.Status(session.session, first.requestId, pos, Wire.Result.UNCHANGED), 10000)
            sync.view(true, listOf(TilePos(200, 200)))
            sync.view(true, listOf(pos))
            displayed.clear()
            sync.tick(10150)
            assertSame(snapshot, displayed.last(), "returning to a cached view must display before any disk or network callback")
            sync.view(false, emptyList())
            sync.view(true, listOf(pos))
            displayed.clear()
            sync.tick(10300)
            assertSame(snapshot, displayed.last(), "closing the map must not discard its warm cache")
        }
    }

    @Test
    fun aNewClientSessionRestoresDownloadedDiskTilesBeforeServerResponse() {
        val session = offer("restarted-client")
        val pos = TilePos(0, 0)
        sync().use { sync ->
            sync.offer(session)
            sync.view(true, listOf(pos))
            sync.tick(10000)
            pump(10000) { sent.any { it is Wire.Request } }
            sync.receive(part(request(), encoded()), 10000)
            pump(10000) { displayed.isNotEmpty() }
        }
        callbacks.clear()
        displayed.clear()
        sent.clear()
        sync().use { restarted ->
            restarted.offer(session)
            restarted.view(true, listOf(pos))
            restarted.tick(20000)
            pump(20000) { displayed.isNotEmpty() && sent.any { it is Wire.Request } }
            assertArrayEquals(encoded().hash, request().knownHash)
            assertEquals(0xff125633.toInt(), displayed.last().color(0))
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun oldSnapshotRemainsVisibleUntilEveryReplacementFragmentIsVerified() {
        val session = offer("atomic-update")
        val pos = TilePos(0, 0)
        TileCache(root, 1000000).write(CacheIdentity.of(session), pos, encoded())
        val random = java.util.Random(1)
        val next = TileCodec.encode(
            TileData(
                IntArray(4096) { 0xff000000.toInt() or random.nextInt(0x1000000) },
                IntArray(4096) { random.nextInt(60000) - 30000 },
                ByteArray(4096) { random.nextInt(16).toByte() },
            ),
        )
        assertTrue(next.body.size > Wire.FRAGMENT)
        sync().use { sync ->
            sync.offer(session)
            sync.view(true, listOf(pos))
            sync.tick(10000)
            pump(10000) { sent.any { it is Wire.Request } && displayed.isNotEmpty() }
            val old = displayed.last()
            val request = request()
            val count = (next.body.size + Wire.FRAGMENT - 1) / Wire.FRAGMENT
            for (index in 0 until count) {
                val start = index * Wire.FRAGMENT
                sync.receive(
                    Wire.Part(
                        session.session, request.requestId, pos, next.hash, next.rawLength,
                        next.compressed, next.body.size, index, count,
                        next.body.copyOfRange(
                            start,
                            minOf(start + Wire.FRAGMENT, next.body.size),
                        ),
                    ),
                    10000,
                )
                sync.tick(10000)
                assertSame(old, displayed.last(), "fragment arrival must never remove the old visible image")
            }
            pump(10000) { displayed.last() !== old }
            assertArrayEquals(next.hash, TileCache(root, 1000000).read(CacheIdentity.of(session), pos).orElseThrow().encoded.hash)
            assertTrue(deleted.isEmpty())
            assertTrue(errors.isEmpty())
        }
    }
}
