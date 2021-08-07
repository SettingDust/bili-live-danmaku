package io.github.settingdust.bilive.danmaku

import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.send
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement

internal interface Sendable {
    var operation: Operation
    var protocol: Protocol

    companion object {
        suspend fun WebSocketSession.send(sendable: Sendable) =
            send(
                RawPacketFormat.encodeToByteArray(
                    packetFormat.encodeToPacket(
                        PolymorphicSerializer,
                        sendable
                    )
                )
            )
    }

    object PolymorphicSerializer : JsonContentPolymorphicSerializer<Sendable>(Sendable::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<out Sendable> =
            throw UnsupportedOperationException("Shouldn't be sent")
    }
}

sealed class Body {
    /**
     * @see [Operation.AUTH]
     */
    @Serializable
    data class Authentication(
        val uid: Int = 0,
        @SerialName("roomid") val roomId: Int,
        @SerialName("protover") val protoVer: Protocol = Protocol.Inflate,
        val platform: String = "web",
        val type: Int = 2
    ) : Body(), Sendable {
        override var operation: Operation = Operation.AUTH
        override var protocol: Protocol = protoVer
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

            internal object Serializer : KSerializer<Code> {
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
    @Serializable
    object Heartbeat : Body(), Sendable {
        override var operation: Operation = Operation.HEARTBEAT
        override var protocol: Protocol = Protocol.Inflate

        internal object Serializer : BodySerializer<Heartbeat> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Body.Heartbeat") {

            }

            override fun serialize(encoder: Encoder, value: Heartbeat) {
            }
        }
    }

    /**
     * @see [Operation.HEARTBEAT_REPLY]
     */
    @Serializable(with = HeartbeatReply.Serializer::class)
    data class HeartbeatReply(val popularity: Int) : Body() {
        object Serializer : BodySerializer<HeartbeatReply> {
            override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("Body.HeartbeatReply", PrimitiveKind.INT)

            override fun deserialize(decoder: Decoder): HeartbeatReply = HeartbeatReply(decoder.decodeInt())
        }
    }

    @Serializable(with = Unknown.Serializer::class)
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

        object Serializer : BodySerializer<Unknown> {
            override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("Body.Unknown", PrimitiveKind.STRING)

            override fun deserialize(decoder: Decoder): Unknown {
                return Unknown(decoder.decodeString().toByteArray())
            }
        }
    }
}
