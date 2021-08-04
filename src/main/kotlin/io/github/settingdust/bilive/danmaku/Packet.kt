package io.github.settingdust.bilive.danmaku

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.Decoder
import com.aayushatharva.brotli4j.encoder.Encoder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import io.github.settingdust.bilive.danmaku.Operation.Companion.putOperation
import io.github.settingdust.bilive.danmaku.ProtocolVersion.Companion.putBodyType
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.send
import io.ktor.util.InternalAPI
import io.ktor.util.moveToByteArray
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

fun ByteBuffer.packets(): List<Packet> {
    val packets = mutableListOf<Packet>()
    val packet = Packet(this)
    try {
        val buffer = ByteBuffer.wrap(packet.body)
        while (buffer.hasRemaining()) {
            packets += Packet(buffer)
        }
    } catch (e: Exception) {
        if (packets.isEmpty()) packets += packet
    }
    return packets
}

data class Packet(
    var length: Int,
    val headerLength: Short = HEADER_LENGTH,
    val protocolVersion: ProtocolVersion,
    val operation: Operation,
    val sequence: Int = SEQUENCE_ID,
    var body: ByteArray
) {
    companion object {
        const val HEADER_LENGTH: Short = 16
        const val SEQUENCE_ID: Int = 1

        suspend fun WebSocketSession.send(packet: Packet) = send(packet.buffer())

        @OptIn(InternalAPI::class)
        suspend fun WebSocketSession.send(buffer: ByteBuffer) = send(buffer.moveToByteArray())
    }

    @OptIn(InternalAPI::class)
    constructor(buffer: ByteBuffer) : this(
        buffer.int,
        buffer.short,
        ProtocolVersion.valueOf(buffer.short),
        Operation.valueOf(buffer.int),
        buffer.int,
        ByteArray(0)
    ) {
        body = buffer.getBytes(length - headerLength)
        runBlocking {
            try {
                body = protocolVersion.decode(body)
            } catch (e: ZipException) {
                // e.printStack()
            }
        }
    }

    constructor(protocolVersion: ProtocolVersion, operation: Operation, body: ByteArray) : this(
        0,
        protocolVersion = protocolVersion,
        operation = operation,
        body = body
    ) {
        length = headerLength + this.body.size
    }

    suspend fun buffer(): ByteBuffer {
        val data = protocolVersion.encode(body)
        length = headerLength + data.size
        return ByteBuffer.wrap(ByteArray(length))
            .putInt(length)
            .putShort(headerLength)
            .putBodyType(protocolVersion)
            .putOperation(operation)
            .putInt(sequence)
            .put(data)
            .also { it.position(0) }
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

enum class Operation(private val operation: Int) {
    HANDSHAKE(0),
    HANDSHAKE_REPLY(1),

    /**
     * @see [Body.Heartbeat]
     */
    HEARTBEAT(2), // 心跳

    /**
     * @see [Body.HeartbeatReply]
     */
    HEARTBEAT_REPLY(3), // 心跳回应
    SEND_MSG(4),

    /**
     * @see [MessageType]
     */
    SEND_MSG_REPLY(5),
    DISCONNECT_REPLY(6),

    /**
     * @see [Body.Authentication]
     */
    AUTH(7), // 验证消息，第一个数据包，发送 roomId

    /**
     * @see [Body.AuthenticationReply]
     */
    AUTH_REPLY(8), // 验证回应
    RAW(9),
    PROTO_READY(10),
    PROTO_FINISH(11),
    CHANGE_ROOM(12),
    CHANGE_ROOM_REPLY(13),
    REGISTER(14),
    REGISTER_REPLY(15),
    UNREGISTER(16),
    UNREGISTER_REPLY(17);

    companion object {
        private val byOperation: Map<Int, Operation> = values().associateBy { it.operation }

        fun valueOf(operation: Int) = byOperation.getValue(operation)

        fun ByteBuffer.putOperation(operation: Operation): ByteBuffer = putInt(operation.operation)
    }
}

@JsonSerialize(using = ProtocolVersion.Companion.Serializer::class)
enum class ProtocolVersion(
    private val version: Short,
    val encode: suspend (ByteArray) -> ByteArray = { it },
    val decode: suspend (ByteArray) -> ByteArray = { it }
) {
    INFLATE(0), // JSON 纯文本
    NORMAL(1),
    DEFLATE(
        2,
        { data -> data.inputStream().use { source -> DeflaterInputStream(source).use { it.readBytes() } } },
        { data -> data.inputStream().use { source -> InflaterInputStream(source).use { it.readBytes() } } }
    ),
    BROTLI(
        3,
        { Encoder.compress(it) },
        { Decoder.decompress(it).decompressedData ?: throw IllegalStateException("Brotli decompress failed") }
    ); // 需要 brotli 解压

    init {
        Brotli4jLoader.ensureAvailability()
    }

    companion object {
        private val byVersion: Map<Short, ProtocolVersion> = values().associateBy { it.version }

        fun valueOf(version: Short) = byVersion.getValue(version)

        fun ByteBuffer.putBodyType(protocolVersion: ProtocolVersion): ByteBuffer = putShort(protocolVersion.version)

        class Serializer(clazz: Class<ProtocolVersion>? = null) : StdSerializer<ProtocolVersion>(clazz) {
            override fun serialize(value: ProtocolVersion, gen: JsonGenerator, provider: SerializerProvider?) {
                gen.writeNumber(value.version)
            }
        }
    }
}

internal fun ByteBuffer.getBytes(length: Int) = ByteArray(length).also { get(it) }
