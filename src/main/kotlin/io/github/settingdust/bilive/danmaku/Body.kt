package io.github.settingdust.bilive.danmaku

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import io.github.settingdust.bilive.danmaku.Packet.Companion.send
import io.ktor.http.cio.websocket.WebSocketSession

sealed class Body {
    companion object {
        suspend fun WebSocketSession.send(body: Body) =
            send(body.packet())
    }

    open fun packet(): Packet {
        throw UnsupportedOperationException("Shouldn't send")
    }

    /**
     * @see [Operation.AUTH]
     */
    data class Authentication(
        val uid: Int = 0,
        val roomId: Int,
        val protoVer: ProtocolVersion = ProtocolVersion.INFLATE,
        val platform: String = "web",
        val type: Int = 2
    ) : Body() {
        override fun packet(): Packet = Packet(protoVer, Operation.AUTH, objectMapper.writeValueAsBytes(this))
    }

    /**
     * @see [Operation.AUTH_REPLY]
     */
    data class AuthenticationReply constructor(
        /**
         * 0    - Success
         * -101 - Token error
         */
        val code: Code
    ) : Body() {
        companion object {
            @JsonDeserialize(using = Code.Deserializer::class)
            enum class Code(private val code: Int) {
                SUCCESS(0), TOKEN_ERROR(-101);

                companion object {
                    private val byCode: Map<Int, Code> = values().associateBy { it.code }

                    fun valueOf(code: Int) = byCode.getValue(code)
                }

                class Deserializer(clazz: Class<Code>? = null) :
                    StdDeserializer<Code>(clazz) {
                    override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Code {
                        val node = p.codec.readTree<JsonNode>(p)
                        return valueOf(node.asInt())
                    }
                }
            }
        }
    }

    /**
     * @see [Operation.HEARTBEAT]
     */
    object Heartbeat : Body() {
        override fun packet(): Packet = Packet(ProtocolVersion.INFLATE, Operation.HEARTBEAT, ByteArray(0))
    }

    /**
     * @see [Operation.HEARTBEAT_REPLY]
     */
    data class HeartbeatReply(val popularity: Int) : Body()

    data class Unknown(val body: ByteArray) : Body() {
        override fun toString() = String(body)

        fun node(): JsonNode = objectMapper.readTree(body)

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
