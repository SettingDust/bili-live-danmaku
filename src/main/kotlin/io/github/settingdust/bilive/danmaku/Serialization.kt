package io.github.settingdust.bilive.danmaku

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.awt.Color
import java.util.Date

internal interface MessageSerializer<T : Message> : KSerializer<T> {
    override fun deserialize(decoder: Decoder): T = throw UnsupportedOperationException("Shouldn't be deserialized")

    override fun serialize(encoder: Encoder, value: T) {
        throw UnsupportedOperationException("Shouldn't be serialized")
    }
}

object DateAsLongSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeLong(value.time)
    override fun deserialize(decoder: Decoder): Date = Date(decoder.decodeLong())
}

object ColorAsIntSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeInt(value.rgb)
    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeInt())
}
