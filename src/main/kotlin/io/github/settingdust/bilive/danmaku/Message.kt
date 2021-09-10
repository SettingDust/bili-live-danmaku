package io.github.settingdust.bilive.danmaku

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.awt.Color
import java.io.UnsupportedEncodingException
import java.time.Instant

sealed class Message : Body() {
    /**
     * @see [MessageType.DANMU_MSG]
     */
    @Serializable(with = Danmu.Serializer.Packet::class)
    data class Danmu(
        val content: String,
        val color: Color,
        val timestamp: Instant,
        /**
         * 不包含 avatar
         */
        val sender: User
    ) : Message() {
        object Serializer {
            object Packet : BSerializer<Danmu> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Danmu")

                override fun deserialize(decoder: Decoder): Danmu =
                    bodyJsonFormat.decodeFromString(Json, decoder.decodeString())

                override fun serialize(encoder: Encoder, value: Danmu) {
                    encoder.encodeString(bodyJsonFormat.encodeToString(Json, value))
                }
            }

            object Json : MessageSerializer<Danmu> {
                override val type: MessageType = MessageType.DANMU_MSG

                override fun deserialize(json: JsonObject, decoder: JsonDecoder): Danmu {
                    val info = json["info"]!!.jsonArray
                    val meta = info[0].jsonArray
                    return Danmu(
                        info[1].jsonPrimitive.content,
                        Color(meta[3].jsonPrimitive.int),
                        Instant.ofEpochMilli(meta[4].jsonPrimitive.long),
                        decoder.json.decodeFromJsonElement(info)
                    )
                }

                override fun serialize(encoder: JsonEncoder, value: Danmu) {
                    encoder.encodeJsonElement(encoder.json.encodeToJsonElement(buildJsonObject {
                        put("content", value.content)
                        put("color", encoder.json.encodeToJsonElement(value.color))
                        put("timestamp", encoder.json.encodeToJsonElement(value.timestamp))
                        put("sender", encoder.json.encodeToJsonElement(value.sender))
                    }))
                }

                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Danmu")
            }
        }
    }

    @Serializable(with = SendGift.Serializer.Packet::class)
    data class SendGift(
        val id: ULong,
        /**
         * User 不包含 title, level
         * Medal 不包含 anchorRoom, anchorName
         */
        val sender: User,
        val timestamp: Instant,
        val number: Int,
        val giftId: Int,
        val giftName: String,
        val totalNumber: Int,
        val price: Int,
        val coinType: CoinType,
        val isFirst: Boolean
    ) : Message() {
        enum class CoinType {
            SILVER, GOLD;
        }

        object Serializer {
            object Packet : BSerializer<SendGift> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SendGift")

                override fun deserialize(decoder: Decoder): SendGift =
                    bodyJsonFormat.decodeFromString(Json, decoder.decodeString())

                override fun serialize(encoder: Encoder, value: SendGift) {
                    encoder.encodeString(bodyJsonFormat.encodeToString(Json, value))
                }
            }

            object Json : MessageSerializer<SendGift> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SendGift")

                override val type: MessageType = MessageType.SEND_GIFT

                override fun deserialize(json: JsonObject, decoder: JsonDecoder): SendGift {
                    val data = json["data"]!!.jsonObject
                    return SendGift(
                        data["tid"]!!.jsonPrimitive.content.toULong(),
                        decoder.json.decodeFromJsonElement(data),
                        Instant.ofEpochSecond(data["timestamp"]!!.jsonPrimitive.long),
                        data["num"]!!.jsonPrimitive.int,
                        data["giftId"]!!.jsonPrimitive.int,
                        data["giftName"]!!.jsonPrimitive.content,
                        data["super_gift_num"]!!.jsonPrimitive.int,
                        data["price"]!!.jsonPrimitive.int,
                        CoinType.valueOf(data["coin_type"]!!.jsonPrimitive.content.uppercase()),
                        data["is_first"]!!.jsonPrimitive.boolean
                    )
                }

                override fun serialize(encoder: JsonEncoder, value: SendGift) {
                    encoder.encodeJsonElement(buildJsonObject {
                        put("id", encoder.json.encodeToJsonElement(value.id))
                        put("sender", encoder.json.encodeToJsonElement(value.sender))
                        put("timestamp", encoder.json.encodeToJsonElement(value.timestamp))
                        put("number", value.number)
                        put("giftId", value.giftId)
                        put("giftName", value.giftName)
                        put("totalNumber", value.totalNumber)
                        put("price", value.price)
                        put("coinType", encoder.json.encodeToJsonElement(value.coinType))
                        put("isFirst", value.isFirst)
                    })
                }
            }
        }
    }

    @Serializable(with = SuperChat.Serializer.Packet::class)
    data class SuperChat(
        /**
         * 包含全部
         */
        val sender: User,
        val content: String,
        val color: Color,
        val price: Int,
        val startTime: Instant,
        val endTime: Instant,
        val background: Background
    ) : Message() {

        @Serializable(with = Background.Serializer::class)
        data class Background(
            val color: Color,
            val bottomColor: Color,
            val endColor: Color,
            val startColor: Color,
            val image: String,
            val priceColor: Color
        ) {
            internal object Serializer : JsonSerializer<Background> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Background")
                override fun deserialize(decoder: JsonDecoder): Background {
                    val json = decoder.decodeJsonElement().jsonObject
                    return Background(
                        Color.decode(json["background_color"]!!.jsonPrimitive.content),
                        Color.decode(json["background_bottom_color"]!!.jsonPrimitive.content),
                        Color.decode(json["background_color_end"]!!.jsonPrimitive.content),
                        Color.decode(json["background_color_start"]!!.jsonPrimitive.content),
                        json["background_image"]!!.jsonPrimitive.content,
                        Color.decode(json["background_price_color"]!!.jsonPrimitive.content)
                    )
                }

                override fun serialize(encoder: JsonEncoder, value: Background) {
                    encoder.encodeJsonElement(bodyJsonFormat.encodeToJsonElement(value))
                }
            }
        }

        object Serializer {
            object Packet : BSerializer<SuperChat> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SuperChat")

                override fun deserialize(decoder: Decoder): SuperChat =
                    bodyJsonFormat.decodeFromString(Json, decoder.decodeString())

                override fun serialize(encoder: Encoder, value: SuperChat) {
                    encoder.encodeString(bodyJsonFormat.encodeToString(Json, value))
                }
            }

            object Json : MessageSerializer<SuperChat> {
                override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SuperChat")

                override val type: MessageType = MessageType.SUPER_CHAT_MESSAGE

                override fun deserialize(json: JsonObject, decoder: JsonDecoder): SuperChat {
                    val data = json["data"]!!.jsonObject
                    return SuperChat(
                        decoder.json.decodeFromJsonElement(data),
                        data["message"]!!.jsonPrimitive.content,
                        Color.decode(data["message_font_color"]!!.jsonPrimitive.content),
                        data["price"]!!.jsonPrimitive.int,
                        Instant.ofEpochSecond(data["start_time"]!!.jsonPrimitive.long),
                        Instant.ofEpochSecond(data["end_time"]!!.jsonPrimitive.long),
                        decoder.json.decodeFromJsonElement(data)
                    )
                }

                override fun serialize(encoder: JsonEncoder, value: SuperChat) {
                    encoder.encodeJsonElement(buildJsonObject {
                        put("sender", encoder.json.encodeToJsonElement(value.sender))
                        put("content", value.content)
                        put("color", encoder.json.encodeToJsonElement(value.color))
                        put("price", value.price)
                        put("startTime", encoder.json.encodeToJsonElement(value.startTime))
                        put("endTime", encoder.json.encodeToJsonElement(value.endTime))
                        put("background", encoder.json.encodeToJsonElement(value.background))
                    })
                }
            }
        }
    }
}

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
    COMBO_SEND,
    GUARD_BUY, // 上舰
    SUPER_CHAT_MESSAGE, // 醒目留言
    SUPER_CHAT_MESSAGE_DELETE, // 删除醒目留言
    INTERACT_WORD, // 用户进入
    ROOM_BANNER,
    ROOM_REAL_TIME_MESSAGE_UPDATE,
    NOTICE_MSG,
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

@Serializable(with = User.Serializer::class)
data class User(
    val id: Int,
    val name: String,
    val avatar: String? = null,
    val level: UserLevel? = null,
    val title: String? = null,
    val medal: Medal? = null
) {
    internal object Serializer : JsonSerializer<User> {
        override fun deserialize(decoder: JsonDecoder): User = when (val json = decoder.decodeJsonElement()) {
            is JsonArray -> {
                val userInfo = json[2].jsonArray
                val userTitle = json[5].jsonArray
                User(
                    userInfo[0].jsonPrimitive.int,
                    userInfo[1].jsonPrimitive.content,
                    level = decoder.json.decodeFromJsonElementOrNull(json[4]),
                    title = userTitle[0].jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() },
                    medal = decoder.json.decodeFromJsonElementOrNull(json[3])
                )
            }
            is JsonObject -> when {
                json.containsKey("user_info") -> {
                    val userInfo = json["user_info"]!!.jsonObject
                    User(
                        json["uid"]!!.jsonPrimitive.int,
                        userInfo["uname"]!!.jsonPrimitive.content,
                        userInfo["face"]!!.jsonPrimitive.content,
                        decoder.json.decodeFromJsonElementOrNull(userInfo),
                        userInfo["title"]!!.jsonPrimitive.content.takeIf { it.isNotBlank() && it != "0" },
                        decoder.json.decodeFromJsonElementOrNull(json["medal_info"])
                    )
                }
                else -> User(
                    json["uid"]!!.jsonPrimitive.int,
                    json["uname"]!!.jsonPrimitive.content,
                    json["face"]!!.jsonPrimitive.content,
                    medal = decoder.json.decodeFromJsonElementOrNull(json["medal_info"])
                )
            }
            else -> throw UnsupportedEncodingException()
        }

        override val descriptor: SerialDescriptor = serializer<Medal>().descriptor

        override fun serialize(encoder: JsonEncoder, value: User) {
            encoder.encodeJsonElement(buildJsonObject {
                put("id", value.id)
                put("name", value.name)
                put("avatar", value.avatar)
                put("level", encoder.json.encodeToJsonElement(value.level))
                put("title", value.title)
                put("medal", encoder.json.encodeToJsonElement(value.medal))
            })
        }
    }
}

@Serializable(with = Medal.Serializer::class)
data class Medal(
    val level: Int,
    val name: String,
    val color: Color,
    val borderColor: Color,
    val startColor: Color,
    val endColor: Color,
    val guardType: GuardType,
    val lighted: Boolean,
    val anchorId: Int,
    val anchorRoom: Int? = null,
    val anchorName: String? = null
) {

    enum class GuardType {
        NORMAL, GOVERNOR, ADMIRAL, CAPTAIN
    }

    internal object Serializer : JsonSerializer<Medal> {
        override fun deserialize(decoder: JsonDecoder): Medal = when (val json = decoder.decodeJsonElement()) {
            is JsonArray ->
                when {
                    json.isNotEmpty() ->
                        Medal(
                            json[0].jsonPrimitive.int,
                            json[1].jsonPrimitive.content,
                            Color(json[4].jsonPrimitive.int),
                            Color(json[7].jsonPrimitive.int),
                            Color(json[8].jsonPrimitive.int),
                            Color(json[9].jsonPrimitive.int),
                            GuardType.values()[json[10].jsonPrimitive.int],
                            json[11].jsonPrimitive.int == 1,
                            json[12].jsonPrimitive.int,
                            json[3].jsonPrimitive.int,
                            json[2].jsonPrimitive.content,
                        )
                    else -> throw UnsupportedEncodingException()
                }
            is JsonObject -> when {
                json["target_id"]?.jsonPrimitive?.intOrNull == 0 -> throw UnsupportedEncodingException()
                (json["anchor_roomid"]
                    ?.jsonPrimitive
                    ?.intOrNull ?: 0) == 0
                -> Medal(
                    json["medal_level"]!!.jsonPrimitive.int,
                    json["medal_name"]!!.jsonPrimitive.content,
                    Color.decode(json["medal_color"]!!.jsonPrimitive.content),
                    Color(json["medal_color_border"]!!.jsonPrimitive.int),
                    Color(json["medal_color_start"]!!.jsonPrimitive.int),
                    Color(json["medal_color_end"]!!.jsonPrimitive.int),
                    GuardType.values()[json["guard_level"]!!.jsonPrimitive.int],
                    json["is_lighted"]!!.jsonPrimitive.int == 1,
                    json["target_id"]!!.jsonPrimitive.int,
                )
                else -> Medal(
                    json["medal_level"]!!.jsonPrimitive.int,
                    json["medal_name"]!!.jsonPrimitive.content,
                    Color.decode(json["medal_color"]!!.jsonPrimitive.content),
                    Color(json["medal_color_border"]!!.jsonPrimitive.int),
                    Color(json["medal_color_start"]!!.jsonPrimitive.int),
                    Color(json["medal_color_end"]!!.jsonPrimitive.int),
                    GuardType.values()[json["guard_level"]!!.jsonPrimitive.int],
                    json["is_lighted"]!!.jsonPrimitive.int == 1,
                    json["target_id"]!!.jsonPrimitive.int,
                    json["anchor_roomid"]!!.jsonPrimitive.int,
                    json["anchor_uname"]!!.jsonPrimitive.content,
                )
            }
            else -> throw UnsupportedEncodingException()
        }

        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Medal") {
            element<Int>("level")
            element<String>("name")
            element("color", ColorAsIntSerializer.descriptor)
            element("borderColor", ColorAsIntSerializer.descriptor)
            element("startColor", ColorAsIntSerializer.descriptor)
            element("endColor", ColorAsIntSerializer.descriptor)
            element<GuardType>("guardType")
            element<Boolean>("lighted")
            element<Int>("anchorId")
            element<Int?>("anchorRoom")
            element<String?>("anchorName")
        }

        override fun serialize(encoder: JsonEncoder, value: Medal) {
            encoder.encodeJsonElement(buildJsonObject {
                put("level", value.level)
                put("name", value.name)
                put("color", encoder.json.encodeToJsonElement(value.color))
                put("borderColor", encoder.json.encodeToJsonElement(value.borderColor))
                put("startColor", encoder.json.encodeToJsonElement(value.startColor))
                put("endColor", encoder.json.encodeToJsonElement(value.endColor))
                put("guardType", encoder.json.encodeToJsonElement(value.guardType))
                put("lighted", value.lighted)
                put("anchorId", value.anchorId)
                put("anchorRoom", value.anchorRoom)
                put("anchorName", value.anchorName)
            })
        }
    }
}

@Serializable(with = UserLevel.Serializer::class)
data class UserLevel(
    val level: Int,
    val color: Color,
    val rank: String? = null
) {
    internal object Serializer : JsonSerializer<UserLevel> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("UserLevel") {
            element<Int>("level")
            element("color", ColorAsIntSerializer.descriptor)
            element<String?>("rank")
        }

        override fun deserialize(decoder: JsonDecoder): UserLevel = when (val json = decoder.decodeJsonElement()) {
            is JsonObject -> UserLevel(
                json["user_level"]!!.jsonPrimitive.int,
                Color.decode(json["level_color"]!!.jsonPrimitive.content)
            )
            is JsonArray -> UserLevel(
                json[0].jsonPrimitive.int,
                Color(json[2].jsonPrimitive.int),
                json[3].jsonPrimitive.content
            )
            else -> throw UnsupportedEncodingException()
        }

        override fun serialize(encoder: JsonEncoder, value: UserLevel) {

            encoder.encodeJsonElement(buildJsonObject {
                put("level", value.level)
                put("color", encoder.json.encodeToJsonElement(value.color))
                put("rank", value.rank)
            })
        }
    }
}
