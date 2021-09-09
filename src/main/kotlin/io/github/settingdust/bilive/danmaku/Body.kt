package io.github.settingdust.bilive.danmaku

import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.send
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

val bodyJsonFormat: Json by lazy {
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowStructuredMapKeys = true
        classDiscriminator = ""
        serializersModule = SerializersModule {
            contextual(DateAsLongSerializer)
            contextual(ColorAsIntSerializer)
            polymorphic(Sendable::class) {
                default { Sendable.Serializer }
                subclass(Body.Authentication.serializer())
                subclass(Body.Heartbeat.serializer())
            }
            contextual(Message.Danmu.Serializer.Json)
            contextual(Message.SendGift.Serializer.Json)
            contextual(Message.SuperChat.Serializer.Json)
            contextual(Body.Unknown.Serializer.Json)
        }
    }
}

@Suppress("SpellCheckingInspection")
internal interface Sendable {
    var operation: Operation
    var protocol: Protocol

    companion object {
        private val packetFormat = PacketFormat(bodyJsonFormat, SerializersModule {
            contextual(DateAsLongSerializer)
            contextual(ColorAsIntSerializer)
        })

        suspend fun WebSocketSession.send(sendable: Sendable) =
            send(BinaryPacketFormat.encodeToByteArray(packetFormat.encodeToPacket(Serializer, sendable)))
    }

    object Serializer : JsonContentPolymorphicSerializer<Sendable>(Sendable::class) {
        override fun selectDeserializer(element: JsonElement) = throw UnsupportedOperationException("Shouldn't be sent")
    }
}

sealed class Body {
    /**
     * @see [Operation.AUTH]
     */
    @Serializable
    data class Authentication(
        @SerialName("clientver") val clientVer: String = "2.0.11",
        val uid: Int = 0,
        @SerialName("roomid") val roomId: Int,
        @SerialName("protover") val protoVer: Protocol = Protocol.Normal,
        val platform: String = "web",
        val type: Int = 2
    ) : Body(), Sendable {
        @Transient
        override var operation: Operation = Operation.AUTH

        @Transient
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
        @Transient
        override var operation: Operation = Operation.HEARTBEAT

        @Transient
        override var protocol: Protocol = Protocol.Inflate
    }

    /**
     * @see [Operation.HEARTBEAT_REPLY]
     */
    @Serializable(with = HeartbeatReply.Serializer::class)
    data class HeartbeatReply(val popularity: Int) : Body() {
        object Serializer : BSerializer<HeartbeatReply> {
            override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("Body.HeartbeatReply", PrimitiveKind.INT)

            override fun deserialize(decoder: Decoder): HeartbeatReply = HeartbeatReply(decoder.decodeInt())
        }
    }

    @Serializable(with = Unknown.Serializer.Packet::class)
    data class Unknown(val body: ByteArray) : Body() {
        override fun toString() = String(body)

        fun node(): JsonElement = bodyJsonFormat.parseToJsonElement(toString())

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

        internal object Serializer {
            object Packet : BSerializer<Unknown> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("Body.Unknown", PrimitiveKind.STRING)

                override fun deserialize(decoder: Decoder): Unknown = Unknown(decoder.decodeString().toByteArray())
            }

            object Json : JsonSerializer<Unknown> {
                override val descriptor: SerialDescriptor =
                    PrimitiveSerialDescriptor("Body.Unknown", PrimitiveKind.STRING)

                override fun serialize(encoder: JsonEncoder, value: Unknown) {
                    encoder.encodeJsonElement(bodyJsonFormat.encodeToJsonElement(value))
                }
            }
        }
    }
}
