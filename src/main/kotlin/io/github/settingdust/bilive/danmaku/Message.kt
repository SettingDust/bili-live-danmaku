package io.github.settingdust.bilive.danmaku

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.Color
import java.util.Date

sealed class Message : Body() {
    /**
     * @see [MessageType.DANMU_MSG]
     */
    @Serializable
    data class Danmu(
        val content: String,
        @Contextual val color: Color,
        @Contextual val timestamp: Date,
        val sender: User
    ) : Message() {
        internal object Serializer {
            object Packet : MessageSerializer<Danmu> {
                override val descriptor: SerialDescriptor = serializer().descriptor

                override fun deserialize(decoder: Decoder): Danmu = jsonFormat.decodeFromString(decoder.decodeString())
            }

            object Json : MessageSerializer<Danmu> {
                override fun deserialize(decoder: Decoder): Danmu {
                    require(decoder is JsonDecoder)
                    val element = decoder.decodeJsonElement().jsonObject
                    if (element["cmd"]?.jsonPrimitive?.content.equals(MessageType.DANMU_MSG.name, true)) {
                        val info = element["info"]!!.jsonArray
                        val meta = info[0].jsonArray
                        val user = info[2].jsonArray
                        val medal = info[3].jsonArray
                        val userLevel = info[4].jsonArray
                        val userTitle = info[5].jsonArray
                        return Danmu(
                            info[1].jsonPrimitive.content,
                            Color(meta[3].jsonPrimitive.int),
                            Date(meta[4].jsonPrimitive.long),
                            User(
                                user[0].jsonPrimitive.int,
                                user[1].jsonPrimitive.content,
                                if (!medal.isEmpty()) Medal(
                                    medal[0].jsonPrimitive.int,
                                    medal[1].jsonPrimitive.content,
                                    medal[2].jsonPrimitive.content,
                                    medal[3].jsonPrimitive.int,
                                    Color(medal[4].jsonPrimitive.int)
                                ) else null,
                                UserLevel(
                                    userLevel[0].jsonPrimitive.int,
                                    Color(userLevel[2].jsonPrimitive.int),
                                    userLevel[3].jsonPrimitive.content
                                ),
                                userTitle[0].jsonPrimitive.content to userTitle[1].jsonPrimitive.content
                            )
                        )
                    } else throw SerializationException("Can't deserialize")
                }

                override val descriptor: SerialDescriptor = serializer().descriptor
            }
        }
    }
}

@Serializable
data class User(
    val id: Int,
    val name: String,
    val medal: Medal? = null,
    val level: UserLevel,
    val title: Pair<String, String>
)

@Serializable
data class Medal(val level: Int, val name: String, val streamer: String, val roomId: Int, @Contextual val color: Color)

@Serializable
data class UserLevel(val level: Int, @Contextual val color: Color, val rank: String)

enum class MessageType {
    /**
     * @see [Message.Danmu]
     */
    DANMU_MSG,
    SEND_GIFT, // 礼物
    GUARD_BUY, // 上舰
    SUPER_CHAT_MESSAGE, // 醒目留言
    SUPER_CHAT_MESSAGE_DELETE, // 删除醒目留言
    INTERACT_WORD,
    ROOM_BANNER,
    ROOM_REAL_TIME_MESSAGE_UPDATE,
    NOTICE_MSG,
    COMBO_SEND,
    COMBO_END,
    ENTRY_EFFECT,
    WELCOME_GUARD,
    WELCOME,
    ROOM_RANK,
    ACTIVITY_BANNER_UPDATE_V2,
    PANEL,
    SUPER_CHAT_MESSAGE_JPN,
    USER_TOAST_MSG,
    ROOM_BLOCK_MSG,
    LIVE,
    PREPARING,
    ROOM_ADMIN_ENTRANCE,
    ROOM_ADMINS,
    ROOM_CHANGE,
    STOP_LIVE_ROOM_LIST,
    WIDGET_BANNER,
    LIVE_INTERACTIVE_GAME,
    ONLINE_RANK_V2,
    ONLINE_RANK_COUNT,
    HOT_RANK_CHANGED;

    companion object {
        @OptIn(ExperimentalStdlibApi::class)
        private val byName: Map<String, MessageType> = buildMap {
            values().forEach { put(it.name, it) }
        }

        fun valueOf(name: String) = byName.getValue(name)
    }
}
