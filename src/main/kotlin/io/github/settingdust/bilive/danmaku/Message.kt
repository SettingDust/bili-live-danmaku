package io.github.settingdust.bilive.danmaku

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import java.awt.Color
import java.util.Date

sealed class Message : Body() {
    /**
     * @see [MessageType.DANMU_MSG]
     */
    @JsonDeserialize(using = Danmu.Deserializer::class)
    data class Danmu(val content: String, val color: Color, val timestamp: Date, val sender: User) : Message() {
        class Deserializer(clazz: Class<AuthenticationReply>? = null) : StdDeserializer<Danmu>(clazz) {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): Danmu {
                val node = p.codec.readTree<JsonNode>(p)
                if (node["cmd"].asText() == "DANMU_MSG") {
                    val info = node["info"].elements().asSequence().toList()
                    val meta = info[0]
                    val user = info[2]
                    val medal = info[3]
                    val userLevel = info[4]
                    val userTitle = info[5]
                    return Danmu(
                        info[1].asText(), Color(meta[3].asInt()), Date(meta[4].asLong()),
                        User(
                            user[0].asInt(),
                            user[1].asText(),
                            if (!medal.isEmpty) Medal(
                                medal[0].asInt(),
                                medal[1].asText(),
                                medal[2].asText(),
                                medal[3].asInt(),
                                Color(medal[4].asInt())
                            ) else null,
                            UserLevel(
                                userLevel[0].asInt(),
                                Color(userLevel[2].asInt()),
                                userLevel[3].asText()
                            ),
                            userTitle[0].asText() to userTitle[1].asText()
                        )
                    )
                } else {
                    throw InvalidFormatException(p, "Can't deserialize to Danmu", node, Danmu::class.java)
                }
            }
        }
    }
}

data class User(
    val id: Int,
    val name: String,
    val medal: Medal? = null,
    val level: UserLevel,
    val title: Pair<String, String>
)

data class Medal(val level: Int, val name: String, val streamer: String, val roomId: Int, val color: Color)

data class UserLevel(val level: Int, val color: Color, val rank: String)

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
    HOT_RANK_CHANGED
}
