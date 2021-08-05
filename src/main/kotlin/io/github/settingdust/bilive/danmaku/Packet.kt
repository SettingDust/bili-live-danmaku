package io.github.settingdust.bilive.danmaku

import com.aayushatharva.brotli4j.Brotli4jLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

internal fun ByteBuffer.getBytes(length: Int) = ByteArray(length).also { get(it) }

@OptIn(ExperimentalSerializationApi::class)
internal object RawPacketFormat : SerialFormat {
    fun decodeFromByteArray(bytes: ByteBuffer) = runBlocking {
        bytes.run {
            Packet(
                length = int,
                headerLength = short,
                protocolVersion = Protocol.valueOf(short),
                operation = Operation.values()[int],
                sequence = int
            ).apply {
                body = getBytes(length)
                try {
                    body = protocolVersion.decode(body)
                } catch (e: ZipException) {
                }
            }
        }
    }

    fun encodeToByteArray(value: Packet): ByteArray =
        value.run {
            val data = runBlocking { protocolVersion.encode(body) }
            val length = headerLength + data.size
            ByteBuffer.wrap(ByteArray(length))
                .putInt(length)
                .putShort(headerLength)
                .putShort(protocolVersion.version)
                .putInt(operation.operation)
                .putInt(sequence)
                .put(runBlocking { protocolVersion.encode(body) })
                .array()
        }

    override val serializersModule: SerializersModule
        get() = EmptySerializersModule
}

@OptIn(ExperimentalSerializationApi::class)
internal object RawPacketsFormat : SerialFormat {
    @OptIn(ExperimentalStdlibApi::class)
    fun decodeFromByteArray(bytes: ByteArray) = ByteBuffer.wrap(bytes)
        .run {
            RawPacketFormat.decodeFromByteArray(this).let {
                buildList {
                    val buffer = ByteBuffer.wrap(it.body)
                    try {
                        while (buffer.hasRemaining()) add(RawPacketFormat.decodeFromByteArray(buffer))
                    } catch (e: Exception) {
                        if (isEmpty()) add(it)
                    }
                }
            }
        }

    override val serializersModule: SerializersModule
        get() = EmptySerializersModule
}

@OptIn(ExperimentalSerializationApi::class)
internal class PacketFormat(override val serializersModule: SerializersModule = EmptySerializersModule) : SerialFormat {
    fun <T : Sendable> encodeToPacket(serializer: SerializationStrategy<T>, value: T): Packet {
        val stream = ByteArrayOutputStream()
        val output = DataOutputStream(stream)
        val encoder = Encoder(output, serializersModule)
        encoder.encodeSerializableValue(serializer, value)
        return RawPacketFormat.decodeFromByteArray(ByteBuffer.wrap(stream.toByteArray()))
    }

    fun <T : Body> decodeFromPacket(serializer: DeserializationStrategy<T>, packet: Packet): T {
        val buffer = ByteBuffer.wrap(packet.body)
        val decoder = Decoder(buffer, serializersModule)
        return decoder.decodeSerializableValue(serializer)
    }

    inline fun <reified T : Sendable> encodeToPacket(value: T): Packet = encodeToPacket(serializer(), value)

    inline fun <reified T : Body> decodeFromPacket(packet: Packet): T = decodeFromPacket(serializer(), packet)

    class Encoder(
        private val output: DataOutput,
        override val serializersModule: SerializersModule
    ) : AbstractEncoder() {
        override fun encodeInt(value: Int) {
            output.writeInt(value)
        }

        override fun encodeShort(value: Short) {
            output.writeShort(value.toInt())
        }

        override fun encodeLong(value: Long) {
            output.writeLong(value)
        }

        override fun encodeBoolean(value: Boolean) = encodeShort(if (value) 1 else 0)

        override fun encodeByte(value: Byte) {
            output.writeByte(value.toInt())
        }

        override fun encodeChar(value: Char) {
            output.writeChar(value.code)
        }

        override fun encodeDouble(value: Double) {
            output.writeDouble(value)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = encodeInt(index)

        override fun encodeFloat(value: Float) {
            output.writeFloat(value)
        }

        override fun encodeString(value: String) {
            output.write(value.toByteArray())
        }
    }

    class Decoder(
        private val buffer: ByteBuffer,
        override val serializersModule: SerializersModule
    ) : AbstractDecoder() {
        private var elementIndex = 0

        override fun decodeBoolean(): Boolean = buffer.short == 1.toShort()

        override fun decodeByte(): Byte = buffer.get()

        override fun decodeChar(): Char = buffer.char

        override fun decodeDouble(): Double = buffer.double

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = decodeInt()

        override fun decodeFloat(): Float = buffer.float

        override fun decodeInt(): Int = buffer.int

        override fun decodeLong(): Long = buffer.long

        override fun decodeShort(): Short = buffer.short

        override fun decodeString(): String = String(buffer.getBytes(buffer.remaining()))

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (elementIndex == descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
            return elementIndex++
        }
    }
}

data class Packet(
    var length: Int = 0,
    val headerLength: Short = HEADER_LENGTH,
    val protocolVersion: Protocol,
    val operation: Operation,
    val sequence: Int = SEQUENCE_ID,
    var body: ByteArray = ByteArray(0)
) {
    companion object {
        const val HEADER_LENGTH: Short = 16
        const val SEQUENCE_ID: Int = 1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (length != other.length) return false
        if (headerLength != other.headerLength) return false
        if (protocolVersion != other.protocolVersion) return false
        if (operation != other.operation) return false
        if (sequence != other.sequence) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + headerLength
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + sequence
        result = 31 * result + body.contentHashCode()
        return result
    }
}

enum class Operation {
    HANDSHAKE,
    HANDSHAKE_REPLY,

    /**
     * @see [Body.Heartbeat]
     */
    HEARTBEAT, // 心跳

    /**
     * @see [Body.HeartbeatReply]
     */
    HEARTBEAT_REPLY, // 心跳回应
    SEND_MSG,

    /**
     * @see [MessageType]
     */
    SEND_MSG_REPLY,
    DISCONNECT_REPLY,

    /**
     * @see [Body.Authentication]
     */
    AUTH, // 验证消息，第一个数据包，发送 roomId

    /**
     * @see [Body.AuthenticationReply]
     */
    AUTH_REPLY, // 验证回应
    RAW,
    PROTO_READY,
    PROTO_FINISH,
    CHANGE_ROOM,
    CHANGE_ROOM_REPLY,
    REGISTER,
    REGISTER_REPLY,
    UNREGISTER,
    UNREGISTER_REPLY;

    val operation: Int = ordinal
}

@Serializable(with = Protocol.Serializer::class)
sealed class Protocol(
    val version: Short
) {
    open fun encode(bytes: ByteArray) = bytes
    open fun decode(bytes: ByteArray) = bytes

    /**
     * Plain Json
     */
    object Inflate : Protocol(0)

    /**
     * @see Body.HeartbeatReply
     */
    object Normal : Protocol(1)

    /**
     * Data compressed
     */
    object Deflate : Protocol(2) {
        override fun encode(bytes: ByteArray): ByteArray =
            bytes.inputStream().use { src -> DeflaterInputStream(src).use { it.readBytes() } }

        override fun decode(bytes: ByteArray): ByteArray =
            bytes.inputStream().use { src -> InflaterInputStream(src).use { it.readBytes() } }
    }

    /**
     * Data compressed with Brotli
     */
    object Brotli : Protocol(3) {
        init {
            Brotli4jLoader.ensureAvailability()
        }

        override fun encode(bytes: ByteArray): ByteArray = com.aayushatharva.brotli4j.encoder.Encoder.compress(bytes)
        override fun decode(bytes: ByteArray) =
            requireNotNull(com.aayushatharva.brotli4j.decoder.Decoder.decompress(bytes).decompressedData)
    }

    companion object {
        private val byVersion: Map<Short, Protocol> = setOf(Inflate, Normal, Deflate, Brotli).associateBy { it.version }

        fun valueOf(version: Short) = byVersion.getValue(version)
    }

    object Serializer : KSerializer<Protocol> {
        override fun deserialize(decoder: Decoder): Protocol = valueOf(decoder.decodeShort())

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Protocol", PrimitiveKind.SHORT)

        override fun serialize(encoder: Encoder, value: Protocol) = encoder.encodeShort(value.version)
    }
}
