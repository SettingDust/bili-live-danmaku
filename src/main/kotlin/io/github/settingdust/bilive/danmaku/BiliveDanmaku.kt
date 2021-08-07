package io.github.settingdust.bilive.danmaku

import io.github.settingdust.bilive.danmaku.MessageType.DANMU_MSG
import io.github.settingdust.bilive.danmaku.Operation.AUTH_REPLY
import io.github.settingdust.bilive.danmaku.Operation.HEARTBEAT_REPLY
import io.github.settingdust.bilive.danmaku.Operation.SEND_MSG_REPLY
import io.github.settingdust.bilive.danmaku.Sendable.Companion.send
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.concurrent.timer
import kotlin.coroutines.CoroutineContext

internal val jsonFormat = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    allowStructuredMapKeys = true
    classDiscriminator = ""
    serializersModule = SerializersModule {
        contextual(DateAsLongSerializer)
        contextual(ColorAsIntSerializer)
        polymorphic(Sendable::class) {
            default { Sendable.PolymorphicSerializer }
            subclass(Body.Authentication::class)
            subclass(Body.Heartbeat::class)
        }
    }
}

internal val packetFormat = PacketFormat(
    serializersModule = SerializersModule {
        contextual(DateAsLongSerializer)
        contextual(ColorAsIntSerializer)
        contextual(Message.Danmu.Serializer.Packet)
    }
)

class BiliveDanmaku(override val coroutineContext: CoroutineContext) : CoroutineScope {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun connect(roomId: Int) = produce {
        // TODO Fetch id from https://api.live.bilibili.com/room/v1/Room/get_info?room_id=5050
        // TODO Fetch from https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=5050
        client.wss(
            host = "broadcastlv.chat.bilibili.com",
            path = "/sub"
        ) {
            send(Body.Authentication(roomId = roomId))
            timer("damaku heartbeat", period = 30 * 1000L) {
                runBlocking { send(Body.Heartbeat) }
            }
            for (frame in incoming) {
                val packets = RawPacketsFormat.decodeFromByteArray(frame.data)
                for (packet in packets) {
                    when (packet.operation) {
                        HEARTBEAT_REPLY -> packetFormat.decodeFromPacket<Body.HeartbeatReply>(packet)
                        AUTH_REPLY -> packetFormat.decodeFromPacket<Body.AuthenticationReply>(packet)
                        SEND_MSG_REPLY -> {
                            val element = jsonFormat.decodeFromString<JsonElement>(String(packet.body)).jsonObject
                            try {
                                when (MessageType.valueOf(element["cmd"]?.jsonPrimitive?.content ?: "")) {
                                    DANMU_MSG -> packetFormat.decodeFromPacket<Message.Danmu>(packet)
                                    else -> packetFormat.decodeFromPacket<Body.Unknown>(packet)
                                }
                            } catch (e: IllegalArgumentException) {
                                packetFormat.decodeFromPacket<Body.Unknown>(packet)
                            }
                        }
                        else -> packetFormat.decodeFromPacket<Body.Unknown>(packet)
                    }.let { send(it) }
                }
            }
        }
    }
}
