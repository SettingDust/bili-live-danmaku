package io.github.settingdust.bilive.danmaku

import io.github.settingdust.bilive.danmaku.MessageType.DANMU_MSG
import io.github.settingdust.bilive.danmaku.MessageType.SEND_GIFT
import io.github.settingdust.bilive.danmaku.MessageType.SUPER_CHAT_MESSAGE
import io.github.settingdust.bilive.danmaku.Operation.AUTH_REPLY
import io.github.settingdust.bilive.danmaku.Operation.HEARTBEAT_REPLY
import io.github.settingdust.bilive.danmaku.Operation.SEND_MSG_REPLY
import io.github.settingdust.bilive.danmaku.Sendable.Companion.send
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.handleCoroutineException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.concurrent.timer
import kotlin.coroutines.CoroutineContext

internal val packetFormat = PacketFormat(
    jsonFormat,
    SerializersModule {
        contextual(DateAsLongSerializer)
        contextual(ColorAsIntSerializer)
    }
)

fun main() = runBlocking {
    BiliveDanmaku(coroutineContext).connect(5050).consumeEach {
        println(it)
    }
}

class BiliveDanmaku(override val coroutineContext: CoroutineContext) : CoroutineScope {
    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
    fun connect(roomId: Int) = produce {
        // TODO Fetch id from https://api.live.bilibili.com/room/v1/Room/get_info?room_id=5050
        // NOT REQUIRED 2021-8-18 Fetch token from https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=$roomId
        // val token = client.get<JsonElement>("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=$roomId")
        //     .jsonObject["data"]!!
        //     .jsonObject["token"]!!
        //     .jsonPrimitive.content
        client.wss(
            host = "broadcastlv.chat.bilibili.com",
            path = "/sub"
        ) {
            send(Body.Authentication(roomId = roomId))
            timer("damaku heartbeat", period = 30 * 1000L) {
                try {
                    runBlocking { send(Body.Heartbeat) }
                } catch (t: Throwable) {
                    handleCoroutineException(coroutineContext, t)
                }
            }
            for (frame in incoming) {
                val packets = PacketFrameFormat.decodeFromByteBuffer(frame.buffer)
                for (packet in packets) {
                    when (packet.operation) {
                        HEARTBEAT_REPLY -> packetFormat.decodeFromPacket<Body.HeartbeatReply>(packet)
                        AUTH_REPLY -> packetFormat.decodeFromPacket<Body.AuthenticationReply>(packet)
                        SEND_MSG_REPLY -> {
                            val element = jsonFormat.decodeFromString<JsonElement>(String(packet.body)).jsonObject
                            try {
                                when (MessageType.valueOf(element["cmd"]?.jsonPrimitive?.content ?: "")) {
                                    DANMU_MSG -> packetFormat.decodeFromPacket<Message.Danmu>(packet)
                                    SEND_GIFT -> packetFormat.decodeFromPacket<Message.SendGift>(packet)
                                    SUPER_CHAT_MESSAGE -> packetFormat.decodeFromPacket<Message.SuperChat>(packet)
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
