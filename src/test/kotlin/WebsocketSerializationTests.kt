import io.github.settingdust.bilive.danmaku.BinaryPacketFormat
import io.github.settingdust.bilive.danmaku.Operation
import io.github.settingdust.bilive.danmaku.Packet
import io.github.settingdust.bilive.danmaku.Protocol
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals

class WebsocketSerializationTests {
    @Test
    fun `test bliving frame format`() {
        assertEquals(
            BinaryPacketFormat.decodeFromByteBuffer(
                ByteBuffer.wrap(
                    byteArrayOf(
                        0, 0, 0, 26, 0, 16, 0, 0, 0, 0, 0, 8, 0, 0, 0, 1, 123, 34, 99, 111, 100, 101, 34, 58, 48, 125
                    )
                )
            ),
            Packet(
                26,
                16,
                Protocol.Inflate,
                Operation.AUTH_REPLY,
                1,
                byteArrayOf(123, 34, 99, 111, 100, 101, 34, 58, 48, 125)
            )
        )
    }
}