package io.github.settingdust.bilive.danmaku

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.io.UnsupportedEncodingException
import java.time.Instant

inline fun <reified T> Json.decodeFromJsonElementOrNull(json: JsonElement?): T? =
    try {
        json?.let { decodeFromJsonElement(it) }
    } catch (e: UnsupportedEncodingException) {
        null
    }

interface BSerializer<T> : KSerializer<T> {
    override fun deserialize(decoder: Decoder): T = throw UnsupportedOperationException("Shouldn't be deserialized")

    override fun serialize(encoder: Encoder, value: T) {
        throw UnsupportedOperationException("Shouldn't be serialized")
    }
}

interface JsonSerializer<T> : BSerializer<T> {
    override fun deserialize(decoder: Decoder): T {
        require(decoder is JsonDecoder)
        return deserialize(decoder)
    }

    override fun serialize(encoder: Encoder, value: T) {
        require(encoder is JsonEncoder)
        return serialize(encoder, value)
    }

    fun deserialize(decoder: JsonDecoder): T = throw UnsupportedOperationException("Shouldn't be deserialized")

    fun serialize(encoder: JsonEncoder, value: T) {
        throw UnsupportedOperationException("Shouldn't be serialized")
    }
}

interface MessageSerializer<T : Message> : JsonSerializer<T> {
    val type: MessageType
    override fun deserialize(decoder: JsonDecoder): T {
        val json = decoder.decodeJsonElement().jsonObject
        if (json["cmd"]?.jsonPrimitive?.content.equals(type.name, true)) {
            return deserialize(json, decoder)
        } else throw SerializationException("Can't deserialize")
    }

    fun deserialize(json: JsonObject, decoder: JsonDecoder): T
}

object InstantAsLongSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

object ColorAsIntSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeInt(value.rgb)
    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeInt())
}
