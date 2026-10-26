package io.github.minimap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Bounded protocol v2 (with legacy v1 framing). Lengths are checked before allocation, including fragment payloads. */
object Wire {
    const val LEGACY_CHANNEL = "minimap:sync_v1"
    const val CHANNEL = "minimap:sync_v2"
    const val VERSION = 2
    const val MAX_BATCH = 64
    const val MAX_PENDING = 128
    const val MAX_FRAME = 24576
    const val FRAGMENT = 16384
    const val MAX_PARTS = 4
    private const val MAGIC = 0x4d4d0000

    sealed interface Message

    @JvmRecord
    data class Hello(val version: Int) : Message

    @JvmRecord
    data class Offer(val session: UUID, val serverId: String, val worldId: String, val epoch: String, val dimension: String) : Message

    @JvmRecord
    data class Open(val session: UUID, val open: Boolean) : Message

    @JvmRecord
    data class Request(val session: UUID, val requestId: Int, val pos: TilePos, val knownHash: ByteArray) : Message

    data class BatchRequest(val session: UUID, val requests: List<Request>) : Message {
        init {
            require(requests.size in 1..MAX_BATCH && requests.all { it.session == session })
            require(requests.map { it.requestId }.distinct().size == requests.size)
        }
    }

    enum class Result { UNCHANGED, MISSING, RETRY }

    @JvmRecord
    data class Status(val session: UUID, val requestId: Int, val pos: TilePos, val result: Result) : Message

    @JvmRecord
    data class Part(
        val session: UUID,
        val requestId: Int,
        val pos: TilePos,
        val hash: ByteArray,
        val rawLength: Int,
        val compressed: Boolean,
        val totalLength: Int,
        val index: Int,
        val count: Int,
        val bytes: ByteArray,
    ) : Message

    @JvmRecord
    data class Ack(val session: UUID, val requestId: Int) : Message

    @JvmRecord
    data class Revoke(val session: UUID) : Message

    @JvmStatic
    @Throws(IOException::class)
    fun encode(message: Message, version: Int = VERSION): ByteArray {
        require(version in 1..VERSION)
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeInt(MAGIC or version)
        with(output) {
            when (message) {
                is Hello -> {
                    writeByte(0)
                    writeInt(message.version)
                }
                is Offer -> {
                    writeByte(1)
                    uuid(message.session)
                    string(message.serverId)
                    string(message.worldId)
                    string(message.epoch)
                    string(message.dimension)
                }
                is Open -> {
                    writeByte(2)
                    uuid(message.session)
                    writeBoolean(message.open)
                }
                is Request -> {
                    writeByte(3)
                    uuid(message.session)
                    writeInt(message.requestId)
                    position(message.pos, version)
                    blob(message.knownHash, 32)
                }
                is Status -> {
                    writeByte(4)
                    uuid(message.session)
                    writeInt(message.requestId)
                    position(message.pos, version)
                    writeByte(message.result.ordinal)
                }
                is Part -> {
                    writeByte(5)
                    uuid(message.session)
                    writeInt(message.requestId)
                    position(message.pos, version)
                    blob(message.hash, 32)
                    writeInt(message.rawLength)
                    writeBoolean(message.compressed)
                    writeInt(message.totalLength)
                    writeInt(message.index)
                    writeInt(message.count)
                    blob(message.bytes, FRAGMENT)
                }
                is Ack -> {
                    writeByte(6)
                    uuid(message.session)
                    writeInt(message.requestId)
                }
                is Revoke -> {
                    writeByte(7)
                    uuid(message.session)
                }
                is BatchRequest -> {
                    if (version < 2) throw IOException("Batch requires v2")
                    writeByte(8)
                    uuid(message.session)
                    writeInt(message.requests.size)
                    for (request in message.requests) {
                        writeInt(request.requestId)
                        position(request.pos, version)
                        blob(request.knownHash, 32)
                    }
                }
            }
        }
        if (bytes.size() > MAX_FRAME) throw IOException("Frame too large")
        return bytes.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): Message {
        if (bytes.size > MAX_FRAME) throw IOException("Frame too large")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = input.readInt()
        val version = magic and 0xffff
        if (magic and -65536 != MAGIC || version !in 1..VERSION) throw IOException("Unknown protocol")
        try {
            val message = with(input) {
                when (readUnsignedByte()) {
                    0 -> Hello(readInt())
                    1 -> Offer(uuid(), string(), string(), string(), string())
                    2 -> Open(uuid(), readBoolean())
                    3 -> Request(uuid(), readInt(), position(version), blob(32))
                    4 -> Status(uuid(), readInt(), position(version), Result.entries[readUnsignedByte()])
                    5 -> Part(
                        uuid(), readInt(), position(version),
                        blob(
                            32,
                        ),
                        readInt(), readBoolean(), readInt(), readInt(), readInt(), blob(FRAGMENT),
                    )
                    6 -> Ack(uuid(), readInt())
                    7 -> Revoke(uuid())
                    8 -> {
                        if (version < 2) throw IOException("Batch requires v2")
                        val session = uuid()
                        val count = readInt()
                        if (count !in 1..MAX_BATCH) throw IOException("Invalid batch size")
                        BatchRequest(session, List(count) { Request(session, readInt(), position(version), blob(32)) })
                    }
                    else -> throw IOException("Unknown message")
                }
            }
            if (input.available() != 0) throw IOException("Trailing frame bytes")
            return message
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid message", error)
        } catch (error: IndexOutOfBoundsException) {
            throw IOException("Invalid message", error)
        }
    }

    private fun DataOutputStream.uuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.uuid(): UUID = UUID(readLong(), readLong())
    private fun DataOutputStream.string(value: String) = blob(value.toByteArray(Charsets.UTF_8), 256)
    private fun DataInputStream.string(): String = blob(256).toString(Charsets.UTF_8)
    private fun DataOutputStream.position(value: TilePos, version: Int) {
        if (version == 1 && value.level != 0) throw IOException("Legacy resolution must be zero")
        writeInt(value.x)
        writeInt(value.z)
        if (version >= 2) writeByte(value.level)
    }
    private fun DataInputStream.position(version: Int): TilePos = TilePos(readInt(), readInt(), if (version >= 2) readUnsignedByte() else 0)

    private fun DataOutputStream.blob(value: ByteArray, maximum: Int) {
        if (value.size > maximum) throw IOException("Field too large")
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.blob(maximum: Int): ByteArray {
        val length = readInt()
        if (length < 0 || length > maximum || length > available()) throw IOException("Invalid field length")
        return readNBytes(length)
    }
}
