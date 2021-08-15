package io.github.settingdust.bilive.danmaku

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.Color
import java.math.BigInteger
import java.time.Instant
import java.util.Date

sealed class Message : Body() {
    /**
     * @see [MessageType.DANMU_MSG]
     */
    @Serializable(with = Danmu.Serializer.Packet::class)
    data class Danmu(
        val content: String,
        val color: Color,
        val timestamp: Date,
        val sender: User
    ) : Message() {
        internal object Serializer {
            object Packet : BodySerializer<Danmu> {
                override val descriptor: SerialDescriptor = serializer().descriptor

                override fun deserialize(decoder: Decoder): Danmu =
                    jsonFormat.decodeFromString(Json, decoder.decodeString())
            }

            object Json : BodySerializer<Danmu> {
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
                                    Color(medal[4].jsonPrimitive.int),
                                    Color(medal[7].jsonPrimitive.int),
                                    Color(medal[8].jsonPrimitive.int),
                                    Color(medal[9].jsonPrimitive.int),
                                    Medal.Type.values()[medal[10].jsonPrimitive.int],
                                    medal[11].jsonPrimitive.int == 1
                                ) else null,
                                level = UserLevel(
                                    userLevel[0].jsonPrimitive.int,
                                    Color(userLevel[2].jsonPrimitive.int),
                                    userLevel[3].jsonPrimitive.content
                                ),
                                title = userTitle[0].jsonPrimitive.content to userTitle[1].jsonPrimitive.content
                            )
                        )
                    } else throw SerializationException("Can't deserialize")
                }

                override val descriptor: SerialDescriptor = serializer().descriptor
            }
        }
    }

    @Serializable(with = SendGift.Serializer.Packet::class)
    data class SendGift(
        val sender: User,
        val timestamp: Instant,
        val number: Int,
        val giftId: Int,
        val giftName: String,
        val totalNumber: Int,
        val price: Int,
        val coinType: CoinType,
        val isFirst: Boolean,
        val medalStreamerId: Int?,
        val id: BigInteger
    ) : Message() {
        enum class CoinType {
            SILVER, GOLD;
        }

        internal object Serializer {
            object Packet : BodySerializer<SendGift> {
                override val descriptor: SerialDescriptor = serializer().descriptor

                override fun deserialize(decoder: Decoder): SendGift =
                    jsonFormat.decodeFromString(Json, decoder.decodeString())
            }

            object Json : BodySerializer<SendGift> {
                override val descriptor: SerialDescriptor = serializer().descriptor

                override fun deserialize(decoder: Decoder): SendGift {
                    require(decoder is JsonDecoder)
                    val element = decoder.decodeJsonElement().jsonObject
                    if (element["cmd"]?.jsonPrimitive?.content.equals(MessageType.SEND_GIFT.name, true)) {
                        val data = element["data"]!!.jsonObject
                        val medal = element["medal_info"]?.jsonObject
                        return SendGift(
                            User(
                                data["uid"]!!.jsonPrimitive.int,
                                data["uname"]!!.jsonPrimitive.content,
                                if (medal != null) Medal(
                                    medal["medal_level"]!!.jsonPrimitive.int,
                                    medal["medal_name"]!!.jsonPrimitive.content,
                                    color = Color(medal["medal_color"]!!.jsonPrimitive.int),
                                    borderColor = Color(medal["medal_color"]!!.jsonPrimitive.int),
                                    startColor = Color(medal["medal_color"]!!.jsonPrimitive.int),
                                    endColor = Color(medal["medal_color"]!!.jsonPrimitive.int),
                                    type = Medal.Type.values()[medal["guard_level"]!!.jsonPrimitive.int],
                                    activated = medal["is_lighted"]!!.jsonPrimitive.int == 1
                                ) else null,
                                data["face"]!!.jsonPrimitive.content
                            ),
                            Instant.ofEpochSecond(data["timestamp"]!!.jsonPrimitive.long),
                            data["num"]!!.jsonPrimitive.int,
                            data["giftId"]!!.jsonPrimitive.int,
                            data["giftName"]!!.jsonPrimitive.content,
                            data["super_gift_num"]!!.jsonPrimitive.int,
                            data["price"]!!.jsonPrimitive.int,
                            CoinType.valueOf(data["coin_type"]!!.jsonPrimitive.content.uppercase()),
                            data["is_first"]!!.jsonPrimitive.boolean,
                            medal?.get("target_id")?.jsonPrimitive?.intOrNull,
                            data["tid"]!!.jsonPrimitive.content.toBigInteger()
                        )
                    } else throw SerializationException("Can't deserialize")
                }
            }
        }
    }
}

@Serializable
data class User(
    val id: Int,
    val name: String,
    val medal: Medal? = null,
    val avatar: String? = null,
    val level: UserLevel? = null,
    val title: Pair<String, String>? = null
)

@Serializable
data class Medal(
    val level: Int,
    val name: String,
    val streamer: String? = null,
    val roomId: Int? = null,
    @Contextual val color: Color,
    @Contextual val borderColor: Color,
    @Contextual val startColor: Color,
    @Contextual val endColor: Color,
    val type: Type,
    val activated: Boolean
) {
    enum class Type {
        NORMAL, GOVERNOR, ADMIRAL, CAPTAIN
    }
}

@Serializable
data class UserLevel(val level: Int, @Contextual val color: Color, val rank: String)

@Serializable
enum class MessageType {
    LIVE, // 开播
    PREPARING, // 下播

    /**
     * 弹幕
     * @see Message.Danmu
     */
    DANMU_MSG,

    /**
     * 礼物
     * @see Message.SendGift
     */
    SEND_GIFT,
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
    ROOM_ADMIN_ENTRANCE,
    ROOM_ADMINS, // 房管列表
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
