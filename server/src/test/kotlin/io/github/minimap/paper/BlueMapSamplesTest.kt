package io.github.minimap.paper

import io.github.minimap.api.MapProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

class BlueMapSamplesTest {
    class Blob(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        fun decompress(): InputStream = this
    }

    class Storage {
        val images = mutableMapOf<Long, ByteArray>()

        fun lowresTiles(level: Int): Storage {
            assertEquals(1, level)
            return this
        }

        fun read(x: Int, z: Int): Blob? = images[(x.toLong() shl 32) or (z.toLong() and 0xffffffffL)]?.let(::Blob)

        fun tile(x: Int, z: Int, height: Int, color: Int) {
            val image = BufferedImage(501, 1002, BufferedImage.TYPE_INT_ARGB)
            for (blockZ in 0 until 500) {
                for (blockX in 0 until 500) {
                    image.setRGB(blockX, blockZ, color)
                    image.setRGB(blockX, blockZ + 501, 0xff0c0000.toInt() or (height and 65535))
                }
            }
            val buffer = ByteArrayOutputStream()
            ImageIO.write(image, "png", buffer)
            images[(x.toLong() shl 32) or (z.toLong() and 0xffffffffL)] = buffer.toByteArray()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun provider(storage: Storage): BlueMapProvider {
        val provider = BlueMapProvider(null)
        val field = BlueMapProvider::class.java.getDeclaredField("bindings").apply { isAccessible = true }
        val constructor = Class.forName("${BlueMapProvider::class.java.name}\$Binding").declaredConstructors.first().apply {
            isAccessible =
                true
        }
        (field.get(provider) as MutableMap<String, Any>)["map"] = constructor.newInstance(storage, 500, 500)
        return provider
    }

    @Test
    fun readsAcrossLowresBoundariesWithoutChangingHeightOrLight() {
        val storage = Storage()
        storage.tile(0, 0, -64, 0xff112233.toInt())
        storage.tile(1, 0, 200, 0xff445566.toInt())
        val data = provider(storage).read(MapProvider.MapRef("map", "1"), 7, 0).orElseThrow()
        assertEquals(-64, data.height()[51])
        assertEquals(200, data.height()[52])
        assertEquals(12.toByte(), data.blockLight()[51])
        assertEquals(0xff112233.toInt(), data.argb()[51])
        assertEquals(0xff445566.toInt(), data.argb()[52])
    }

    @Test
    fun negativeCoordinatesAndAbsentStorageStayIsolated() {
        val storage = Storage()
        storage.tile(-1, -1, -32768, 0xff123456.toInt())
        val provider = provider(storage)
        val data = provider.read(MapProvider.MapRef("map", "1"), -1, -1).orElseThrow()
        assertEquals(-32768, data.height()[0])
        assertEquals(0xff123456.toInt(), data.argb()[4095])
        assertTrue(provider.read(MapProvider.MapRef("map", "1"), 0, 0).isEmpty)
    }
}
