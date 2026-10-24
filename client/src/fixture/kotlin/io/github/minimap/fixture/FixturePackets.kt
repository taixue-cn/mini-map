package io.github.minimap.fixture

import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import java.util.UUID

/** Client-thread observations only; no provider, payload or networking behaviour is changed. */
object FixturePackets {
    private val counts = linkedMapOf<String, Long>()
    private val revoked = mutableSetOf<UUID>()
    private val target = TilePos(10, 10)

    @JvmStatic
    fun capture(message: Wire.Message, incoming: Boolean) {
        if (System.getenv("MINIMAP_TEST_MODE") != "lifecycle") return
        fun increment(name: String, value: Long = 1) {
            counts[name] = counts.getOrDefault(name, 0) + value
        }
        when {
            !incoming && message is Wire.Request -> {
                increment("requests")
                if (message.pos == target) {
                    increment("targetRequests")
                    if (message.knownHash.isNotEmpty()) increment("knownHashRequests")
                }
            }
            incoming && message is Wire.Part -> {
                increment("parts")
                if (message.session in revoked) increment("staleParts")
                if (message.pos == target) {
                    increment("targetParts")
                    if (message.index == 0) {
                        increment("rawBytes", message.rawLength.toLong())
                        increment("bodyBytes", message.totalLength.toLong())
                        if (message.compressed) increment("compressedTiles")
                    }
                }
            }
            incoming && message is Wire.Status && message.pos == target -> increment(message.result.name)
            incoming && message is Wire.Revoke -> {
                revoked += message.session
                increment("revokes")
            }
        }
    }

    fun snapshot(): Map<String, Long> = counts.toMap()
}
