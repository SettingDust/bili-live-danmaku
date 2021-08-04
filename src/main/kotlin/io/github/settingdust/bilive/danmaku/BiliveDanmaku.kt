package io.github.settingdust.bilive.danmaku

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.settingdust.bilive.danmaku.Body.Companion.send
import io.github.settingdust.bilive.danmaku.MessageType.DANMU_MSG
import io.github.settingdust.bilive.danmaku.Operation.AUTH_REPLY
import io.github.settingdust.bilive.danmaku.Operation.HEARTBEAT_REPLY
import io.github.settingdust.bilive.danmaku.Operation.SEND_MSG_REPLY
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.math.BigInteger
import kotlin.concurrent.timer

val objectMapper = jacksonObjectMapper().apply {
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    propertyNamingStrategy = PropertyNamingStrategies.LOWER_CASE
}

object BiliveDanmaku {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    suspend fun connect(roomId: Int): Channel<Body> {
        val channel = Channel<Body>()

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
                val packets = frame.buffer.packets()
                for (packet in packets) {
                    when (packet.operation) {
                        HEARTBEAT_REPLY -> Body.HeartbeatReply(BigInteger(packet.body).toInt())
                        AUTH_REPLY -> objectMapper.readValue<Body.AuthenticationReply>(packet.body)
                        SEND_MSG_REPLY -> {
                            val node = withContext(Dispatchers.IO) {
                                @Suppress("BlockingMethodInNonBlockingContext") objectMapper.readTree(packet.body)
                            }
                            try {
                                when (objectMapper.convertValue<MessageType>(node["cmd"])) {
                                    DANMU_MSG -> objectMapper.readValue<Message.Danmu>(packet.body)
                                    else -> Body.Unknown(packet.body)
                                }
                            } catch (e: IllegalArgumentException) {
                                Body.Unknown(packet.body)
                            }
                        }
                        else -> Body.Unknown(packet.body)
                    }.let { channel.send(it) }
                }
            }
        }
        return channel
    }
}
