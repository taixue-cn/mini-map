package io.github.minimap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Bounded protocol v1. Lengths are checked before allocation, including fragment payloads. */
object Wire {
    const val CHANNEL = "minimap:sync_v1"
    const val VERSION = 1
    const val MAX_FRAME = 24576
    const val FRAGMENT = 16384
    const val MAX_PARTS = 4
    private const val MAGIC = 0x4d4d0001

    sealed interface Message

    @JvmRecord
    data class Hello(val version: Int) : Message

    @JvmRecord
    data class Offer(val session: UUID, val serverId: String, val worldId: String, val epoch: String, val dimension: String) : Message

    @JvmRecord
    data class Open(val session: UUID, val open: Boolean) : Message

    @JvmRecord
    data class Request(val session: UUID, val requestId: Int, val pos: TilePos, val knownHash: ByteArray) : Message

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
    fun encode(message: Message): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeInt(MAGIC)
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
                    position(message.pos)
                    blob(message.knownHash, 32)
                }
                is Status -> {
                    writeByte(4)
                    uuid(message.session)
                    writeInt(message.requestId)
                    position(message.pos)
                    writeByte(message.result.ordinal)
                }
                is Part -> {
                    writeByte(5)
                    uuid(message.session)
                    writeInt(message.requestId)
                    position(message.pos)
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
        if (input.readInt() != MAGIC) throw IOException("Unknown protocol")
        try {
            val message = with(input) {
                when (readUnsignedByte()) {
                    0 -> Hello(readInt())
                    1 -> Offer(uuid(), string(), string(), string(), string())
                    2 -> Open(uuid(), readBoolean())
                    3 -> Request(uuid(), readInt(), position(), blob(32))
                    4 -> Status(uuid(), readInt(), position(), Result.entries[readUnsignedByte()])
                    5 -> Part(
                        uuid(), readInt(), position(),
                        blob(
                            32,
                        ),
                        readInt(), readBoolean(), readInt(), readInt(), readInt(), blob(FRAGMENT),
                    )
                    6 -> Ack(uuid(), readInt())
                    7 -> Revoke(uuid())
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
    private fun DataOutputStream.position(value: TilePos) {
        writeInt(value.x)
        writeInt(value.z)
    }
    private fun DataInputStream.position(): TilePos = TilePos(readInt(), readInt())

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
