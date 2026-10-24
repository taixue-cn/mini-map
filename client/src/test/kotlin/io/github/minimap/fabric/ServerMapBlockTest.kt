package io.github.minimap.fabric

import io.github.minimap.client.CacheIdentity
import io.github.minimap.protocol.TileData
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import xaero.map.region.MapBlock
import xaero.map.region.MapTile

class ServerMapBlockTest {
    @Test
    fun sampleHeightAndTopHeightDoNotUseTheBlockLightValue() {
        val block = ServerMapBlock(TileData(IntArray(4096) { 0xff336633.toInt() }, IntArray(4096) { 101 }, ByteArray(4096) { 12 }), 0)
        assertEquals(101, block.height)
        assertEquals(101, block.topHeight)
        assertTrue(block.serverOwned)
    }

    @Test
    fun nativeExplorationTakesOwnershipOfAnImportedBlock() {
        val block = ServerMapBlock(TileData(IntArray(4096) { 0xff336633.toInt() }, IntArray(4096) { 101 }, ByteArray(4096) { 12 }), 0)
        block.write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false)
        assertFalse(block.serverOwned)
        assertEquals(105, block.height)
        assertEquals(Blocks.DIRT.defaultBlockState(), block.state)
    }

    @Test
    fun verifiedServerUpdateReplacesLocallyExploredPixels() {
        val source = samples(101)
        val tile = MapTile(null, 0, 0)
        assertTrue(needsServerUpdate(tile, source, false))
        tile.importServerSamples(source, 0, 0, false)
        assertFalse(needsServerUpdate(tile, source, false))
        val live = tile.getBlock(0, 0)
        live.write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false)
        tile.importServerSamples(samples(99), 0, 0, false)
        assertEquals(99, tile.getBlock(0, 0).height)
        assertTrue((tile.getBlock(0, 0) as ServerMapBlock).serverOwned)
    }

    @Test
    fun activeServerSessionKeepsItsSamplesAuthoritativeUntilRevoked() {
        val identity = MiniMapClient::class.java.getDeclaredField("identity").apply { isAccessible = true }
        try {
            identity.set(null, CacheIdentity("server", "world", "epoch"))
            val block = ServerMapBlock(samples(101), 0)
            block.write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false)
            assertTrue(block.serverOwned)
            assertEquals(101, block.height)
            identity.set(null, null)
            block.write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false)
            assertFalse(block.serverOwned)
            assertEquals(105, block.height)
        } finally {
            identity.set(null, null)
        }
    }

    @Test
    fun missingServerTerrainDoesNotEraseLocallyExploredPixels() {
        val tile = MapTile(null, 0, 0)
        tile.importServerSamples(samples(101), 0, 0, false)
        val native = MapBlock().apply { write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false) }
        tile.setBlock(0, 0, native)
        val promoted = tile.getBlock(1, 0) as ServerMapBlock
        promoted.write(Blocks.DIRT.defaultBlockState(), 106, 106, Biomes.PLAINS, 7, false, false)
        val empty = TileData(IntArray(4096), IntArray(4096), ByteArray(4096))
        assertTrue(needsServerUpdate(tile, empty, true))
        tile.importServerSamples(empty, 0, 0, true)
        assertEquals(native, tile.getBlock(0, 0))
        assertEquals(promoted, tile.getBlock(1, 0))
        assertEquals(106, tile.getBlock(1, 0).height)
        assertEquals(0, (tile.getBlock(2, 0) as ServerMapBlock).source.color(2))
        assertFalse(needsServerUpdate(tile, empty, true))
    }

    @Test
    fun unloadedChunksCanReceiveAChangedServerSnapshotAgain() {
        val tile = MapTile(null, -1, 0)
        val first = samples(101)
        tile.importServerSamples(first, 48, 32, false)
        val block = tile.getBlock(0, 0) as ServerMapBlock
        assertEquals(first.color(32 * 64 + 48), block.source.color(32 * 64 + 48))
        val updated = samples(110)
        assertTrue(needsServerUpdate(tile, updated, false))
        tile.importServerSamples(updated, 48, 32, false)
        assertEquals(110, tile.getBlock(0, 0).height)
        assertFalse(needsServerUpdate(tile, updated, false))
    }

    @Test
    fun serverSamplesReplaceNativePixelsAsWellAsUnexploredBorders() {
        val tile = MapTile(null, 0, 0)
        val native = MapBlock().apply { write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false) }
        tile.setBlock(7, 7, native)
        val source = samples(101)
        assertTrue(needsServerUpdate(tile, source, false))
        tile.importServerSamples(source, 0, 0, false)
        assertTrue(tile.getBlock(7, 7) is ServerMapBlock)
        assertEquals(101, tile.getBlock(7, 7).height)
        assertTrue(tile.getBlock(0, 0) is ServerMapBlock)
        assertFalse(needsServerUpdate(tile, source, false))
    }

    @Test
    fun placeholderSamplesRemainExcludedFromTerrainSavesUntilExploredNatively() {
        val tile = MapTile(null, 0, 0)
        assertFalse(tile.hasServerSamples())
        val block = ServerMapBlock(samples(101), 0)
        tile.setBlock(0, 0, block)
        assertTrue(tile.hasServerSamples())
        block.write(Blocks.DIRT.defaultBlockState(), 105, 105, Biomes.PLAINS, 7, false, false)
        assertFalse(tile.hasServerSamples())
    }

    private fun samples(height: Int) = TileData(IntArray(4096) { 0xff336633.toInt() }, IntArray(4096) { height }, ByteArray(4096) { 12 })

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
