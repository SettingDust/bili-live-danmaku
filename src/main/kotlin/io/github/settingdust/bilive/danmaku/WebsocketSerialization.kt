package io.github.settingdust.bilive.danmaku

import com.aayushatharva.brotli4j.Brotli4jLoader
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.nio.ByteBuffer
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

internal fun ByteBuffer.getBytes(length: Int) = ByteArray(length).also { get(it) }

@OptIn(ExperimentalSerializationApi::class)
internal object BinaryPacketFormat : SerialFormat {
    override val serializersModule = EmptySerializersModule

    fun decodeFromByteBuffer(bytes: ByteBuffer) = bytes.run {
        val length = int
        val headerLength = short
        val protocol = Protocol.valueOf(short)
        val operation = Operation.values()[int]
        val sequenceId = int
        val body = getBytes(length - headerLength).let {
            try {
                protocol.decode(it)
            } catch (e: ZipException) {
                it
            }
        }
        Packet(length, headerLength, protocol, operation, sequenceId, body)
    }

    fun encodeToByteArray(value: Packet): ByteArray = value.run {
        val data = protocol.encode(body)
        val length = headerLength + data.size
        ByteBuffer.wrap(ByteArray(length))
            .putInt(length)
            .putShort(headerLength)
            .putShort(protocol.version)
            .putInt(operation.operation)
            .putInt(sequenceId)
            .put(protocol.encode(body))
            .array()
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object PacketFrameFormat : SerialFormat {
    override val serializersModule = EmptySerializersModule

    @OptIn(ExperimentalStdlibApi::class)
    fun decodeFromByteBuffer(bytes: ByteBuffer) = bytes.run {
        BinaryPacketFormat.decodeFromByteBuffer(this).let {
            buildList {
                val buffer = ByteBuffer.wrap(it.body)
                try {
                    while (buffer.hasRemaining()) add(BinaryPacketFormat.decodeFromByteBuffer(buffer))
                } catch (e: Exception) {
                    if (isEmpty()) add(it)
                }
            }
        }
    }

    fun decodeFromByteArray(bytes: ByteArray) = decodeFromByteBuffer(ByteBuffer.wrap(bytes))
}

@OptIn(ExperimentalSerializationApi::class)
internal class PacketFormat(
    private val jsonFormat: Json,
    override val serializersModule: SerializersModule = EmptySerializersModule
) : SerialFormat {
    fun <T : Sendable> encodeToPacket(serializer: SerializationStrategy<T>, value: T): Packet {
        val buffer = ByteBuffer.allocate(1024)

        Encoder(jsonFormat, buffer, serializersModule).also { it.encodeSerializableValue(serializer, value) }

        val body = value.protocol.encode(buffer.array().sliceArray(0 until buffer.position()))
        val length = Packet.HEADER_LENGTH + body.size

        return BinaryPacketFormat.decodeFromByteBuffer(
            ByteBuffer.allocate(length)
                .putInt(length)
                .putShort(Packet.HEADER_LENGTH)
                .putShort(value.protocol.version)
                .putInt(value.operation.operation)
                .putInt(Packet.SEQUENCE_ID)
                .put(body)
                .also { it.position(0) }
        )
    }

    fun <T : Body> decodeFromPacket(serializer: DeserializationStrategy<T>, packet: Packet): T {
        val buffer = ByteBuffer.wrap(packet.body)
        val decoder = Decoder(jsonFormat, buffer, serializersModule)
        val result = decoder.decodeSerializableValue(serializer)
        if (result is Sendable) {
            result.protocol = packet.protocol
            result.operation = packet.operation
        }
        return result
    }

    inline fun <reified T : Sendable> encodeToPacket(value: T): Packet =
        encodeToPacket(serializersModule.serializer(), value)

    inline fun <reified T : Body> decodeFromPacket(packet: Packet): T =
        decodeFromPacket(serializersModule.serializer(), packet)

    class Encoder(
        private val jsonFormat: Json,
        private val buffer: ByteBuffer,
        override val serializersModule: SerializersModule
    ) : AbstractEncoder() {
        override fun encodeInt(value: Int) {
            buffer.putInt(value)
        }

        override fun encodeShort(value: Short) {
            buffer.putShort(value)
        }

        override fun encodeLong(value: Long) {
            buffer.putLong(value)
        }

        override fun encodeBoolean(value: Boolean) = encodeShort(if (value) 1 else 0)

        override fun encodeByte(value: Byte) {
            buffer.put(value)
        }

        override fun encodeChar(value: Char) {
            buffer.putChar(value)
        }

        override fun encodeDouble(value: Double) {
            buffer.putDouble(value)
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = encodeInt(index)

        override fun encodeFloat(value: Float) {
            buffer.putFloat(value)
        }

        override fun encodeString(value: String) {
            buffer.put(value.toByteArray())
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            encodeString(jsonFormat.encodeToString(serializer, value))
        }
    }

    class Decoder(
        private val jsonFormat: Json,
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

        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
            try {
                buffer.mark()
                jsonFormat.decodeFromString(deserializer, decodeString())
            } catch (e: SerializationException) {
                buffer.reset()
                super.decodeSerializableValue(deserializer)
            }
    }
}

internal data class Packet(
    val length: Int,
    val headerLength: Short = HEADER_LENGTH,
    val protocol: Protocol,
    val operation: Operation,
    val sequenceId: Int = SEQUENCE_ID,
    val body: ByteArray
) {
    companion object {
        internal const val HEADER_LENGTH: Short = 16
        internal const val SEQUENCE_ID = 1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (length != other.length) return false
        if (headerLength != other.headerLength) return false
        if (protocol != other.protocol) return false
        if (operation != other.operation) return false
        if (sequenceId != other.sequenceId) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + headerLength
        result = 31 * result + protocol.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + sequenceId
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
    internal open fun encode(bytes: ByteArray) = bytes
    internal open fun decode(bytes: ByteArray) = bytes

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
        override fun encode(bytes: ByteArray) =
            bytes.inputStream().use { src -> DeflaterInputStream(src).use { it.readBytes() } }

        override fun decode(bytes: ByteArray) =
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
        // Use lazy due to https://youtrack.jetbrains.com/issue/KT-8970
        private val byVersion: Map<Short, Protocol> by lazy {
            setOf(
                Inflate,
                Normal,
                Deflate,
                Brotli
            ).associateBy { it.version }
        }

        fun valueOf(version: Short) = byVersion.getValue(version)
    }

    object Serializer : BSerializer<Protocol> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Protocol", PrimitiveKind.SHORT)

        override fun serialize(encoder: Encoder, value: Protocol) {
            encoder.encodeShort(value.version)
        }

        override fun deserialize(decoder: Decoder): Protocol = valueOf(decoder.decodeShort())
    }
}
