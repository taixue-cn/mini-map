package io.github.minimap.server

import io.github.minimap.protocol.TileCodec
import io.github.minimap.protocol.TilePos
import io.github.minimap.protocol.Wire
import java.util.UUID

object TileParts {
    @JvmStatic
    fun split(session: UUID, request: Int, pos: TilePos, tile: TileCodec.Encoded): List<Wire.Part> {
        val count = (tile.body.size + Wire.FRAGMENT - 1) / Wire.FRAGMENT
        return List(count) { index ->
            Wire.Part(
                session, request, pos, tile.hash, tile.rawLength, tile.compressed, tile.body.size, index, count,
                tile.body.copyOfRange(index * Wire.FRAGMENT, minOf(tile.body.size, (index + 1) * Wire.FRAGMENT)),
            )
        }
    }
}
