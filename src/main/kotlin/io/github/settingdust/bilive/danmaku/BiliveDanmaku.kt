package io.github.settingdust.bilive.danmaku

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.Decoder
import com.aayushatharva.brotli4j.encoder.Encoder
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.settingdust.bilive.danmaku.Body.Companion.send
import io.github.settingdust.bilive.danmaku.MessageType.DANMU_MSG
import io.github.settingdust.bilive.danmaku.Operation.AUTH
import io.github.settingdust.bilive.danmaku.Operation.AUTH_REPLY
import io.github.settingdust.bilive.danmaku.Operation.Companion.putOperation
import io.github.settingdust.bilive.danmaku.Operation.HEARTBEAT
import io.github.settingdust.bilive.danmaku.Operation.HEARTBEAT_REPLY
import io.github.settingdust.bilive.danmaku.Operation.SEND_MSG_REPLY
import io.github.settingdust.bilive.danmaku.Packet.Companion.send
import io.github.settingdust.bilive.danmaku.ProtocolVersion.Companion.putBodyType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.send
import io.ktor.util.InternalAPI
import io.ktor.util.moveToByteArray
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Date
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.concurrent.timer

suspend fun main() {
    BiliveDanmaku.listen(5050)
}

internal val objectMapper = jacksonObjectMapper().apply {
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    propertyNamingStrategy = PropertyNamingStrategies.LOWER_CASE
}

object BiliveDanmaku {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    suspend fun listen(roomId: Int) {
        // TODO Fetch id from https://api.live.bilibili.com/room/v1/Room/get_info?room_id=5050
        // TODO Fetch from https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=5050
        client.wss(
            host = "broadcastlv.chat.bilibili.com",
            path = "/sub"
        ) {
            send(Authentication(roomId = roomId))
            timer("damaku heartbeat", period = 30 * 1000) {
                runBlocking { send(Heartbeat) }
            }
            for (frame in incoming) {
                val packets = frame.buffer.packets()
                for (packet in packets) {
                    when (packet.operation) {
                        HEARTBEAT_REPLY -> HeartbeatReply(BigInteger(packet.body).toInt())
                        AUTH_REPLY -> objectMapper.readValue<AuthenticationReply>(packet.body)
                        SEND_MSG_REPLY -> {
                            val node = objectMapper.readTree(packet.body)
                            try {
                                when (objectMapper.convertValue<MessageType>(node["cmd"])) {
                                    DANMU_MSG -> objectMapper.readValue<Messages.Danmu>(packet.body)
                                    else -> String(packet.body)
                                }
                            } catch (e: IllegalArgumentException) {
                                println("Can't parse ${node["cmd"]}")
                            }
                        }
                        else -> String(packet.body)
                    }.let { println(it) }
                }
            }
        }
    }
}

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
    HEARTBEAT(2), // 心跳
    HEARTBEAT_REPLY(3), // 心跳回应
    SEND_MSG(4),
    SEND_MSG_REPLY(5), // 消息，包含弹幕、广播 TODO 链接消息类型
    DISCONNECT_REPLY(6),
    AUTH(7), // 验证消息，第一个数据包，发送 roomId
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

sealed class Body {
    companion object {
        suspend fun WebSocketSession.send(body: Body) =
            send(body.packet())
    }

    open fun packet(): Packet {
        throw UnsupportedOperationException("Shouldn't send")
    }
}

data class Authentication(
    val uid: Int = 0,
    val roomId: Int,
    val protoVer: ProtocolVersion = ProtocolVersion.INFLATE,
    val platform: String = "web",
    val type: Int = 2
) : Body() {
    override fun packet(): Packet = Packet(protoVer, AUTH, objectMapper.writeValueAsBytes(this))
}

@Suppress("DataClassPrivateConstructor")
@JsonDeserialize(using = AuthenticationReply.Companion.Deserializer::class)
data class AuthenticationReply private constructor(
    /**
     * 0    - Success
     * -101 - Token error
     */
    val code: Code
) : Body() {
    companion object {
        enum class Code(private val code: Int) {
            SUCCESS(0), TOKEN_ERROR(-101);

            companion object {
                private val byCode: Map<Int, Code> = values().associateBy { it.code }

                fun valueOf(code: Int) = byCode.getValue(code)
            }
        }

        class Deserializer(clazz: Class<AuthenticationReply>? = null) : StdDeserializer<AuthenticationReply>(clazz) {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): AuthenticationReply {
                val node = p.codec.readTree<JsonNode>(p)
                return AuthenticationReply(Code.valueOf(node["code"].asInt()))
            }
        }
    }
}

object Heartbeat : Body() {
    override fun packet(): Packet = Packet(ProtocolVersion.INFLATE, HEARTBEAT, ByteArray(0))
}

data class HeartbeatReply(val popularity: Int) : Body()

internal fun ByteBuffer.getBytes(length: Int) = ByteArray(length).also { get(it) }

enum class MessageType {
    DANMU_MSG, // 弹幕
    SEND_GIFT, // 礼物
    GUARD_BUY, // 上舰
    SUPER_CHAT_MESSAGE, // 醒目留言
    SUPER_CHAT_MESSAGE_DELETE, // 删除醒目留言
    INTERACT_WORD,
    ROOM_BANNER,
    ROOM_REAL_TIME_MESSAGE_UPDATE,
    NOTICE_MSG,
    COMBO_SEND,
    COMBO_END,
    ENTRY_EFFECT,
    WELCOME_GUARD,
    WELCOME,
    ROOM_RANK,
    ACTIVITY_BANNER_UPDATE_V2,
    PANEL,
    SUPER_CHAT_MESSAGE_JPN,
    USER_TOAST_MSG,
    ROOM_BLOCK_MSG,
    LIVE,
    PREPARING,
    ROOM_ADMIN_ENTRANCE,
    ROOM_ADMINS,
    ROOM_CHANGE,
    STOP_LIVE_ROOM_LIST,
    WIDGET_BANNER,
    LIVE_INTERACTIVE_GAME,
    ONLINE_RANK_V2,
    ONLINE_RANK_COUNT,
    HOT_RANK_CHANGED
}

object Messages {
    /**
     * @see [MessageType.DANMU_MSG]
     */
    @JsonDeserialize(using = Danmu.Companion.Deserializer::class)
    data class Danmu(val content: String, val color: Color, val timestamp: Date, val sender: User) {
        companion object {
            class Deserializer(clazz: Class<AuthenticationReply>? = null) : StdDeserializer<Danmu>(clazz) {
                override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Danmu {
                    val node = p.codec.readTree<JsonNode>(p)
                    if (node["cmd"].asText() == "DANMU_MSG") {
                        val info = node["info"].elements().asSequence().toList()
                        val meta = info[0]
                        val user = info[2]
                        val medal = info[3]
                        val userLevel = info[4]
                        val userTitle = info[5]
                        return Danmu(
                            info[1].asText(), Color(meta[3].asInt()), Date(meta[4].asLong()),
                            User(
                                user[0].asInt(),
                                user[1].asText(),
                                if (!medal.isEmpty) Medal(
                                    medal[0].asInt(),
                                    medal[1].asText(),
                                    medal[2].asText(),
                                    medal[3].asInt(),
                                    Color(medal[4].asInt())
                                ) else null,
                                UserLevel(
                                    userLevel[0].asInt(),
                                    Color(userLevel[2].asInt()),
                                    userLevel[3].asText()
                                ),
                                userTitle[0].asText() to userTitle[1].asText()
                            )
                        )
                    } else {
                        throw InvalidFormatException(p, "Can't deserialize to Danmu", node, Danmu::class.java)
                    }
                }
            }
        }
    }
}

data class User(
    val id: Int,
    val name: String,
    val medal: Medal? = null,
    val level: UserLevel,
    val title: Pair<String, String>
)

data class Medal(val level: Int, val name: String, val streamer: String, val streamerRoomId: Int, val color: Color)

data class UserLevel(val level: Int, val color: Color, val rank: String)
