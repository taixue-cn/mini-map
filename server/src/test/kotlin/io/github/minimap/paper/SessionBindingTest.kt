package io.github.minimap.paper

import io.github.minimap.api.MapProvider
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Optional
import java.util.UUID

class SessionBindingTest {
    private class Provider : MapProvider {
        val worlds = mutableMapOf<UUID, MapProvider.MapRef>()
        override fun resolve(world: MapProvider.WorldRef): Optional<MapProvider.MapRef> = Optional.ofNullable(worlds[world.uid])
        override fun read(map: MapProvider.MapRef, tileX: Int, tileZ: Int): Optional<MapProvider.SurfaceTile> = Optional.empty()

        // A provider's value equality cannot transfer an existing session to a new authority.
        override fun equals(other: Any?): Boolean = other is Provider
        override fun hashCode(): Int = 1
    }

    @Test
    fun availabilityElsewhereDoesNotKeepAWithdrawnWorldBindingAlive() {
        val provider = Provider()
        val original = UUID.randomUUID()
        val destination = UUID.randomUUID()
        val map = MapProvider.MapRef("shared-map", "same-epoch")
        val ref = MapProvider.WorldRef(original, "world", "minecraft:overworld", Path.of("world"))
        provider.worlds[original] = map
        val binding = MiniMapServer.Binding(provider, map, original, "cache-world", "epoch", ref.dimension)
        assertTrue(binding.matches(binding.copy(map = provider.resolve(ref).orElseThrow())))
        provider.worlds.remove(original)
        provider.worlds[destination] = map
        assertTrue(provider.available(map))
        assertFalse(binding.matches(provider.resolve(ref).map { binding.copy(map = it) }.orElse(null)))
        assertFalse(binding.matches(binding.copy(world = destination)))
    }

    @Test
    fun providerMapAndEveryCacheIdentityFieldBelongToTheSession() {
        val provider = Provider()
        val binding = MiniMapServer.Binding(provider, MapProvider.MapRef("map", "1"), UUID.randomUUID(), "world", "epoch", "dimension")
        assertTrue(binding.matches(binding.copy()))
        for (changed in listOf(
            binding.copy(provider = Provider()),
            binding.copy(map = MapProvider.MapRef("other", "1")),
            binding.copy(map = MapProvider.MapRef("map", "2")),
            binding.copy(world = UUID.randomUUID()),
            binding.copy(worldId = "other"),
            binding.copy(epoch = "other"),
            binding.copy(dimension = "other"),
        )) {
            assertFalse(binding.matches(changed))
        }
        assertFalse(binding.matches(null))
    }
}
