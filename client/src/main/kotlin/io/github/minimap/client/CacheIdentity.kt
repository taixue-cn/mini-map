package io.github.minimap.client

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.Wire
import java.util.HexFormat

/** Length-delimited logical identities cannot collide through concatenation or escape cache directories. */
@JvmRecord
data class CacheIdentity(val server: String, val world: String, val epoch: String) {
    fun serverFolder(): String = "Multiplayer_minimap_${digest(server)}"
    fun worldFolder(): String = "minimap_${digest(world, epoch)}"
    fun key(): String = digest(server, world, epoch)

    companion object {
        @JvmStatic
        fun of(offer: Wire.Offer): CacheIdentity = CacheIdentity(offer.serverId, offer.worldId, offer.epoch)

        private fun digest(vararg parts: String): String {
            val text = buildString {
                for (part in parts) {
                    require(part.isNotBlank()) { "Blank identity" }
                    append(part.length).append(':').append(part)
                }
            }
            return HexFormat.of().formatHex(TileCodec.hash(text.toByteArray(Charsets.UTF_8)))
        }
    }
}
