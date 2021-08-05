package io.github.settingdust.bilive.danmaku

import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.send
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

internal interface Sendable {
    val operation: Operation
    val protocol: Protocol

    companion object {
        suspend fun WebSocketSession.send(sendable: Sendable) =
            send(RawPacketFormat.encodeToByteArray(packetFormat.encodeToPacket(sendable)))
    }
}

sealed class Body {
    /**
     * @see [Operation.AUTH]
     */
    @Serializable
    data class Authentication(
        val uid: Int = 0,
        val roomId: Int,
        val protoVer: Protocol = Protocol.Inflate,
        val platform: String = "web",
        val type: Int = 2
    ) : Body(), Sendable {
        override val operation: Operation = Operation.AUTH
        override val protocol: Protocol = protoVer
    }

    /**
     * @see [Operation.AUTH_REPLY]
     */
    @Serializable
    data class AuthenticationReply constructor(
        /**
         * 0    - Success
         * -101 - Token error
         */
        val code: Code
    ) : Body() {
        @Serializable(with = Code.Serializer::class)
        enum class Code(private val code: Int) {
            SUCCESS(0), TOKEN_ERROR(-101);

            companion object {
                private val byCode: Map<Int, Code> = values().associateBy { it.code }

                fun valueOf(code: Int) = byCode.getValue(code)
            }

            object Serializer : KSerializer<Code> {
                override fun deserialize(decoder: Decoder): Code = valueOf(decoder.decodeInt())

                override val descriptor: SerialDescriptor
                    get() = PrimitiveSerialDescriptor("AuthenticationReply.Code", PrimitiveKind.INT)

                override fun serialize(encoder: Encoder, value: Code) {
                    encoder.encodeInt(value.code)
                }
            }
        }
    }

    /**
     * @see [Operation.HEARTBEAT]
     */
    object Heartbeat : Body(), Sendable {
        override val operation: Operation = Operation.HEARTBEAT
        override val protocol: Protocol = Protocol.Inflate
    }

    /**
     * @see [Operation.HEARTBEAT_REPLY]
     */
    @Serializable
    data class HeartbeatReply(val popularity: Int) : Body()

    @Serializable
    data class Unknown(val body: ByteArray) : Body() {
        override fun toString() = String(body)

        fun node(): JsonElement = jsonFormat.parseToJsonElement(toString())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Unknown

            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            return body.contentHashCode()
        }
    }
}
