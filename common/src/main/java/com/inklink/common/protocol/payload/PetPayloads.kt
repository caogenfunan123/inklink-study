package com.inklink.common.protocol.payload

import com.google.gson.annotations.SerializedName

/**
 * 宠物背包与多物种实体定义
 * 生命周期: EGG(蛋,互动3次孵化) -> CUB(幼年 Lv1) -> JUVENILE(少年 Lv2-3)
 *          -> ADOLESCENT(青年 Lv4) -> ADULT(成年 Lv5+, 按性格+照料定型 finalForm)
 *
 * V1.1 游戏化新增：性格特质 playfulness/affection、成年形态 finalForm、场景 sceneId、
 * Buff 衰减暂停计时 energyPauseUntilTs/moodPauseUntilTs。
 * 注意：新增字段均带默认值，Gson 向后兼容（旧端忽略、新端读旧档走默认值）。
 */
data class PetItem(
    @SerializedName("petId") val petId: String,
    @SerializedName("petType") var petType: String = "cat",
    @SerializedName("name") var name: String = "小可爱",
    @SerializedName("hunger") var hunger: Int = 80,
    @SerializedName("happiness") var happiness: Int = 80,
    @SerializedName("clean") var clean: Int = 90,
    @SerializedName("energy") var energy: Int = 100,
    @SerializedName("health") var health: Int = 100,
    @SerializedName("exp") var exp: Int = 0,
    @SerializedName("level") var level: Int = 1,
    @SerializedName("lastUpdateTs") var lastUpdateTs: Long = System.currentTimeMillis(),
    @SerializedName("birthTs") var birthTs: Long = System.currentTimeMillis(),
    @SerializedName("unlocked") var unlocked: Boolean = true,
    @SerializedName("skinId") var skinId: String = "default",
    @SerializedName("lifeStage") var lifeStage: String = "CUB",
    @SerializedName("isSleeping") var isSleeping: Boolean = false,
    @SerializedName("isAlive") var isAlive: Boolean = true,
    @SerializedName("interactions") var interactions: Int = 0,
    /** 爱玩度 [-100,100]：经常玩耍升高，长期冷落降低；影响空闲自主行为倾向 */
    @SerializedName("playfulness") var playfulness: Int = 0,
    /** 亲密度 [-100,100]：抚摸/单击互动升高；影响触摸反馈烈度 */
    @SerializedName("affection") var affection: Int = 0,
    /** 成年定型形态: ""(未定型) / BALANCED / PLAYFUL / STUDIOUS / DROOPY */
    @SerializedName("finalForm") var finalForm: String = "",
    /** 当前佩戴装饰（头饰槽）：""=无 / deco_bandana 头巾 / deco_glasses 眼镜 */
    @SerializedName("decoId") var decoId: String = "",
    /** 玩耍/学习累计次数（成年 finalForm 判定输入） */
    @SerializedName("playCount") var playCount: Int = 0,
    @SerializedName("learnCount") var learnCount: Int = 0,
    /** 当前像素场景: bedroom / living_room / windowsill */
    @SerializedName("sceneId") var sceneId: String = "bedroom",
    /** buff_energy 赠送后精力衰减暂停截止时间戳；0=无暂停 */
    @SerializedName("energyPauseUntilTs") var energyPauseUntilTs: Long = 0,
    /** buff_mood 赠送后开心衰减暂停截止时间戳；0=无暂停 */
    @SerializedName("moodPauseUntilTs") var moodPauseUntilTs: Long = 0,
    /** 上次照料行为时间戳，用于"长期冷落"性格衰减判定 */
    @SerializedName("lastCareTs") var lastCareTs: Long = 0,
    /** 上次随机事件触发时间戳（冷却判定，防结算风暴连环事件） */
    @SerializedName("lastEventTs") var lastEventTs: Long = 0,
    /**
     * 各属性独立小数余量（rate*budget 不足 1 点的残留），跨 tick 累积。
     * 分属性存储而非共享累加器：避免快速属性(饥饿1/s)吃满整个时间窗、
     * 把慢速属性(开心0.7/s)的小数清零，导致非整速率在 1s tick 下永不衰减。
     */
    @SerializedName("remHunger") var remHunger: Double = 0.0,
    @SerializedName("remMood") var remMood: Double = 0.0,
    @SerializedName("remEnergy") var remEnergy: Double = 0.0,
    @SerializedName("remClean") var remClean: Double = 0.0,
    @SerializedName("remHealth") var remHealth: Double = 0.0,
    /** 离世时间戳；0=未离世（V1.2 生命闭环：虚弱沉睡超宽限期未治疗即离世） */
    @SerializedName("passedAtTs") var passedAtTs: Long = 0,
    /** 最近一次 health 归零时间戳，用于离世宽限期计时（复活后清零） */
    @SerializedName("lastHealthZeroTs") var lastHealthZeroTs: Long = 0
)

data class PetBag(
    @SerializedName("activePetId") var activePetId: String,
    @SerializedName("petList") val petList: MutableList<PetItem> = mutableListOf(),
    @SerializedName("coin") var coin: Int = 0,
    /** 治疗药丸数量（阶段七字段；V1.1 迁移并入 itemStock["potion_heal"]，仅保留兼容读取） */
    @SerializedName("pillCount") var pillCount: Int = 0,
    /** 数据结构版本，用于旧档迁移判定（1=阶段七, 2=V1.1 游戏化） */
    @SerializedName("schemaVer") var schemaVer: Int = 1,
    /** 道具库存 itemId->数量（food_normal/food_premium/toy/book/shower_gel/sleep_potion/potion_heal） */
    @SerializedName("itemStock") var itemStock: MutableMap<String, Int> = mutableMapOf(),
    /** 已解锁外观装饰 id 列表（deco_bandana/deco_glasses），跨设备赠礼解锁 */
    @SerializedName("unlockedDecorations") var unlockedDecorations: MutableList<String> = mutableListOf(),
    /** 已解锁像素场景 id 列表 */
    @SerializedName("unlockedScenes") var unlockedScenes: MutableList<String> = mutableListOf("bedroom"),
    /** 里程碑达成记录 milestoneId->true */
    @SerializedName("milestones") var milestones: MutableMap<String, Boolean> = mutableMapOf(),
    /** 每日目标进度 yyyy-MM-dd -> (feed/play/clean 完成计数)，跨日重置 */
    @SerializedName("dailyGoalDate") var dailyGoalDate: String = "",
    @SerializedName("dailyGoalFeed") var dailyGoalFeed: Int = 0,
    @SerializedName("dailyGoalPlay") var dailyGoalPlay: Int = 0,
    @SerializedName("dailyGoalClean") var dailyGoalClean: Int = 0,
    @SerializedName("dailyGoalClaimed") var dailyGoalClaimed: Boolean = false,
    /** 成长时间线「时刻」列表（V1.2 情感记忆，独立于事件日志环形 500 淘汰） */
    @SerializedName("moments") var moments: MutableList<Moment> = mutableListOf(),
    /** 离世宠物纪念册列表（V1.2 生命闭环） */
    @SerializedName("memorials") var memorials: MutableList<Memorial> = mutableListOf()
)

/**
 * 成长时间线「高光时刻」（V1.2 情感记忆）。
 * 从事件日志中筛出的、值得回看的关键事件，独立于 500 条环形淘汰长期保留。
 */
data class Moment(
    @SerializedName("momentId") val momentId: String,
    @SerializedName("petId") val petId: String,
    /** HATCH / LEVEL_UP / FORM / REVIVE / GIFT / MILESTONE / PASSED */
    @SerializedName("type") val type: String,
    @SerializedName("title") val title: String,
    @SerializedName("ts") val ts: Long,
    @SerializedName("snapshot") val snapshot: String = ""
)

/**
 * 离世宠物纪念册（V1.2 生命闭环）。
 * 宠物离世后归档，供回看；重生不继承资产，仅保留纪念册。
 */
data class Memorial(
    @SerializedName("memorialId") val memorialId: String,
    @SerializedName("petId") val petId: String,
    @SerializedName("name") val name: String,
    @SerializedName("petType") val petType: String,
    @SerializedName("birthTs") val birthTs: Long,
    @SerializedName("passedTs") val passedTs: Long,
    @SerializedName("lifespanMs") val lifespanMs: Long,
    @SerializedName("finalForm") val finalForm: String,
    @SerializedName("playfulness") val playfulness: Int,
    @SerializedName("affection") val affection: Int,
    @SerializedName("momentCount") val momentCount: Int
)

/**
 * 互动指令载荷 (PET_INTERACT_CMD / 31)
 */
data class PetInteractCmdPayload(
    @SerializedName("action") val action: String, // FEED, PLAY, CLEAN, SLEEP, LEARN, HEAL
    @SerializedName("foodType") val foodType: String = "SNACK",
    @SerializedName("count") val count: Int = 1,
    @SerializedName("triggerTs") val triggerTs: Long = System.currentTimeMillis()
)

/**
 * 互动执行回执 (PET_INTERACT_ACK / 32)
 */
data class PetInteractAckPayload(
    @SerializedName("success") val success: Boolean,
    @SerializedName("deltaHunger") val deltaHunger: Int = 0,
    @SerializedName("deltaHappiness") val deltaHappiness: Int = 0,
    @SerializedName("petSnapshot") val petSnapshot: PetItem? = null,
    /** 执行结果说明（被拒绝原因/成功文案），旧端忽略即可 */
    @SerializedName("note") val note: String? = null,
    @SerializedName("ackTs") val ackTs: Long = System.currentTimeMillis()
)

/**
 * 远程重置 PIN 码指令 (CMD_RESET_HOST_PIN / 36)
 */
data class ResetHostPinPayload(
    @SerializedName("newPinHash") val newPinHash: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("authSignature") val authSignature: String
)

/**
 * 远程语音文本任务 (REMOTE_TASK_SEND / 45)
 */
data class RemoteTaskPayload(
    @SerializedName("taskId") val taskId: String,
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 任务确认与播放回执 (REMOTE_TASK_ACK / 46)
 */
data class RemoteTaskAckPayload(
    @SerializedName("taskId") val taskId: String,
    @SerializedName("status") val status: String, // RECEIVED, PLAYED
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 通用错误回执 (ERROR_RESPONSE / 52)
 */
data class ErrorResponsePayload(
    @SerializedName("refMsgType") val refMsgType: Int,
    @SerializedName("errorCode") val errorCode: Int,
    @SerializedName("message") val message: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 远程小游戏邀请与动作 (PET_GAME_INVITE / 33, PET_GAME_ACTION / 34)
 */
data class PetGameInvitePayload(
    @SerializedName("inviteId") val inviteId: String,
    @SerializedName("gameType") val gameType: String = "RPS",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class PetGameActionPayload(
    @SerializedName("inviteId") val inviteId: String,
    @SerializedName("actionData") val actionData: String, // ROCK, PAPER, SCISSORS
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 宠物报警受惊事件 (PET_ALERT_EVENT / 35)
 */
data class PetAlertEventPayload(
    @SerializedName("alertType") val alertType: String,
    @SerializedName("description") val description: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 商店购买与远程赠礼 (PET_SHOP_BUY / 42, PET_REMOTE_GIFT / 44)
 */
data class PetShopBuyPayload(
    @SerializedName("itemId") val itemId: String,
    @SerializedName("itemType") val itemType: String,
    @SerializedName("success") val success: Boolean,
    @SerializedName("newCoin") val newCoin: Int,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 远程赠礼 (PET_REMOTE_GIFT / 44)。
 * V1.1 三类礼物统一走本载荷，giftType 判别，零新增码位：
 *  - item: 实物道具入库 itemId=food_normal/food_premium/toy/book/shower_gel/sleep_potion/potion_heal
 *  - deco: 解锁外观装饰 itemId=deco_bandana/deco_glasses
 *  - buff: 即时增益 itemId=buff_energy(精力+40,暂停衰减10min)/buff_mood(开心+30,暂停衰减10min)
 * 兼容：旧端不识别 giftType 时按 itemId 前缀走原有 coin_/零食逻辑。
 */
data class PetRemoteGiftPayload(
    @SerializedName("itemId") val itemId: String,
    @SerializedName("count") val count: Int = 1,
    @SerializedName("giftType") val giftType: String = "item", // item, deco, buff, coin
    @SerializedName("triggerTs") val triggerTs: Long = System.currentTimeMillis()
)

/**
 * 赠礼回执 (复用 PET_INTERACT_ACK / 32 通道之外的独立轻回执)。
 * 沿用 CMD_ACK 亦可；此处显式建模便于主控端展示到账结果。旧端忽略即可。
 */
data class PetGiftAckPayload(
    @SerializedName("itemId") val itemId: String,
    @SerializedName("giftType") val giftType: String,
    @SerializedName("success") val success: Boolean,
    @SerializedName("note") val note: String? = null,
    @SerializedName("petSnapshot") val petSnapshot: PetItem? = null,
    @SerializedName("ackTs") val ackTs: Long = System.currentTimeMillis()
)

/**
 * 背包宠物串门互动 (PET_BAG_INTERACT / 43)。
 * 本地串门：targetPetId 为被访休眠宠物；跨设备串门（好友）：targetPetId 为访客方活跃宠物。
 * V1.1 裁决：JSON 子类型判别，零新增码位——
 *  LOCAL_VISIT=本机背包串门；FRIEND_VISIT=好友跨设备串门；CALL_VISIT=孩子呼唤家长远程探望；TEASE=好友远程逗弄。
 */
data class PetBagInteractPayload(
    @SerializedName("targetPetId") val targetPetId: String,
    @SerializedName("subType") val subType: String = "LOCAL_VISIT",
    @SerializedName("visitorName") val visitorName: String = "",
    @SerializedName("visitorPetType") val visitorPetType: String = "",
    @SerializedName("triggerTs") val triggerTs: Long = System.currentTimeMillis()
)
