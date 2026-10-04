package com.inklink.controller.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.PetBagInteractPayload
import com.inklink.common.protocol.payload.PetGameInvitePayload
import com.inklink.common.protocol.payload.PetRemoteGiftPayload
import com.inklink.common.protocol.payload.RemoteTaskPayload
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import java.util.UUID

/**
 * 主控端宠物高级管理与远程任务面板
 */
class PetDetailActivity : AppCompatActivity() {

    private lateinit var app: InkControllerApplication
    private lateinit var targetDeviceId: String
    private val gson = Gson()
    private lateinit var tvStats: TextView
    private var tvTtsHint: android.widget.TextView? = null

    /**
     * 异步消息到达后切回 UI 线程的安全封装：页面已销毁时不执行，
     * 否则 AlertDialog 会抛 BadTokenException 崩溃（曾复现：收到 CMD_PET_EVENT 后关页）。
     */
    private fun safeUi(block: () -> Unit) {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) block()
        }
    }

    private val transportListener = object : com.inklink.common.transport.TransportListener {
        override fun onTextMessage(message: InkMessage) {
            if (message.fromDeviceId != targetDeviceId) return
            when (message.messageType) {
                MessageType.CMD_PET_EVENT -> {
                    // 受控端主动上报（如 event_hungry_alert）：主控端只弹窗，不存音频（§七.1）
                    val ev = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetEventPayload::class.java)
                    }.getOrNull()
                    when (ev?.eventId) {
                        "event_hungry_alert" -> safeUi { showHungerAlert(ev.ttsText) }
                        else -> Unit   // 其它 eventId 是主控端自己发出的回声，忽略
                    }
                }
                MessageType.DEVICE_STATUS_REPORT -> {
                    val st = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.DeviceStatusPayload::class.java)
                    }.getOrNull()
                    // null = 受控端还在探测 TTS 引擎初始化，不下结论，避免开机瞬间误显"不支持"
                    if (st?.ttsAvailable == false) {
                        runOnUiThread {
                            tvTtsHint?.text = "⚠️ 手表无可用 TTS 引擎：文字播报已自动关闭，预制音效不受影响"
                        }
                    }
                }
                MessageType.PET_BAG_SYNC -> {
                    val bag = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetBag::class.java)
                    }.getOrNull()
                    if (bag != null) {
                        val activePet = bag.petList.find { it.petId == bag.activePetId } ?: bag.petList.firstOrNull()
                         runOnUiThread {
                             if (activePet != null) {
                                 renderPetStats(activePet, bag.coin, bag.petList.size, bag.pillCount)
                             }
                         }
                    }
                }
                MessageType.PET_STATE_SYNC -> {
                    val pet = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetItem::class.java)
                    }.getOrNull()
                     if (pet != null) {
                         runOnUiThread {
                             renderPetStats(pet)
                             notifyLevelUpIfRaised(pet.name.ifBlank { pet.petType }, pet.level)
                         }
                     }
                }
                MessageType.REMOTE_TASK_ACK -> {
                    val ack = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.RemoteTaskAckPayload::class.java)
                    }.getOrNull()
                    if (ack != null && ack.status == "PLAYED") {
                        safeUi { showTaskRewardDialog(ack.taskId) }
                    }
                }
                MessageType.PET_BAG_INTERACT -> {
                    // 43 子类型：CALL_VISIT=孩子呼唤家长远程探望
                    val ip = runCatching {
                        gson.fromJson(message.payload, PetBagInteractPayload::class.java)
                    }.getOrNull()
                    safeUi {
                        if (ip?.subType == "CALL_VISIT") {
                            AlertDialog.Builder(this@PetDetailActivity)
                                .setTitle("💌 ${ip.visitorName.ifBlank { "孩子的宠物" }} 想见你")
                                .setMessage("孩子说：爸爸妈妈快来看看TA的宠物吧！")
                                .setPositiveButton("稍后去看看", null)
                                .setNegativeButton("知道了", null)
                                .show()
                        } else {
                            Toast.makeText(this@PetDetailActivity, "🐾 孩子的宠物正在串门玩耍", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                MessageType.PET_INTERACT_ACK -> {
                    // 32 通道双报文（V1.1 JSON 判别，零新增码位）：
                    // 含 giftType = 赠礼 ACK；否则 = 照料互动 ACK
                    val raw = runCatching { org.json.JSONObject(message.payload ?: "{}") }.getOrNull()
                    val isGiftAck = raw?.optString("giftType")?.isNotBlank() == true
                    if (isGiftAck) {
                        val ack = runCatching {
                            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetGiftAckPayload::class.java)
                        }.getOrNull() ?: return
                        runOnUiThread {
                            val label = when (ack.giftType) {
                                "buff" -> "Buff增益"
                                "deco" -> "装饰"
                                "coin" -> "零花钱"
                                else -> "道具"
                            }
                            val desc = if (ack.success) "🎁 ${label}已到账${ack.note?.let { "（$it）" } ?: ""}"
                            else "🎁 赠礼未生效：${ack.note ?: "未知原因"}"
                            Toast.makeText(this@PetDetailActivity, desc, Toast.LENGTH_SHORT).show()
                            ack.petSnapshot?.let { renderPetStats(it) }
                        }
                    } else {
                        val ack = runCatching {
                            gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetInteractAckPayload::class.java)
                        }.getOrNull()
                        if (ack != null) {
                            runOnUiThread {
                                val desc = when {
                                    ack.success -> "互动成功：饱食 +${ack.deltaHunger}，心情 +${ack.deltaHappiness}" +
                                            (ack.note?.let { "（$it）" } ?: "")
                                    else -> "受控端执行被拒：${ack.note ?: "未知原因"}"
                                }
                                Toast.makeText(this@PetDetailActivity, desc, Toast.LENGTH_SHORT).show()
                                ack.petSnapshot?.let { renderPetStats(it) }
                            }
                        }
                    }
                }
                MessageType.PET_GAME_ACTION -> {
                    val action = runCatching {
                        gson.fromJson(message.payload, com.inklink.common.protocol.payload.PetGameActionPayload::class.java)
                    }.getOrNull()
                    if (action != null) {
                        safeUi { settleGameResult(action.inviteId, action.actionData) }
                    }
                }
                else -> Unit
            }
        }

        override fun onAudioMessage(frame: ByteArray) = Unit
        override fun onConnectionChanged(connected: Boolean) = Unit
    }

    private var prevListener: com.inklink.common.transport.TransportListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet_detail)

        app = application as InkControllerApplication
        targetDeviceId = intent.getStringExtra("target_device_id") ?: ""

        val tvHeader = findViewById<TextView>(R.id.tvPetHeader)
        tvStats = findViewById(R.id.tvPetStats)
        val btnTask = findViewById<Button>(R.id.btnSendRemoteTask)
        val btnGift = findViewById<Button>(R.id.btnSendGift)
        val btnGame = findViewById<Button>(R.id.btnGameInvite)
        val btnFeed = findViewById<Button>(R.id.btnFeedPet)

        // 远程投喂：单击投 1 份，长按合并为一次 5 连投（防抖合并，单条消息携带 count）
        btnFeed.setOnClickListener { sendInteractCmd("FEED", 1) }
        btnFeed.setOnLongClickListener {
            sendInteractCmd("FEED", 5)
            true
        }

        tvHeader.text = "🐾 受控端 (${targetDeviceId.take(8)}) 宠物控制台"

        // 远程日常照料按钮：与受控端共同照顾宠物（31 指令 action 扩展）
        findViewById<Button>(R.id.btnRemotePlay).setOnClickListener { sendInteractCmd("PLAY", 1) }
        findViewById<Button>(R.id.btnRemoteClean).setOnClickListener { sendInteractCmd("CLEAN", 1) }
        findViewById<Button>(R.id.btnRemoteLearn).setOnClickListener { sendInteractCmd("LEARN", 1) }
        findViewById<Button>(R.id.btnRemoteSleep).setOnClickListener { sendInteractCmd("SLEEP", 1) }
        findViewById<Button>(R.id.btnRemoteHeal).setOnClickListener { sendInteractCmd("HEAL", 1) }

        // ---- 互动音频（《音频系统 Final-Rev1》§六）----
        tvTtsHint = findViewById(R.id.tvTtsHint)
        val swTts = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swEnableTts)
        // 先同步 UI 状态、再挂监听：反过来会在进入页面时把当前值无意义地回写一次
        swTts.isChecked = com.inklink.controller.audio.AudioSettings.isTtsEnabled(this)
        swTts.setOnCheckedChangeListener { _, checked ->
            com.inklink.controller.audio.AudioSettings.setTtsEnabled(this, checked)
            tvTtsHint?.text = if (checked)
                "开启后指令携带播报文本，手表能不能念由手表的 TTS 引擎决定（不支持则自动跳过）"
            else
                "关闭后只播放预制音效，不再向手表发送朗读文本。"
        }
        findViewById<Button>(R.id.btnVoiceFeed).setOnClickListener {
            sendVoiceEvent("event_feed", "🍖 喂养", "我吃饱啦")
        }
        findViewById<Button>(R.id.btnVoiceTouch).setOnClickListener {
            sendVoiceEvent("event_touch", "✋ 抚摸", "好舒服呀")
        }
        findViewById<Button>(R.id.btnVoiceHappy).setOnClickListener {
            sendVoiceEvent("event_happy", "😊 逗开心", "我很开心")
        }
        findViewById<Button>(R.id.btnVoiceSleep).setOnClickListener {
            sendVoiceEvent("event_sleep", "💤 哄睡", "我要睡觉了")
        }
        findViewById<Button>(R.id.btnVoiceWakeup).setOnClickListener {
            sendVoiceEvent("event_wakeup", "☀️ 唤醒", "我醒过来了")
        }
        findViewById<Button>(R.id.btnSndCall).setOnClickListener { sendSystemSound("alert_call", "📢 设备呼叫", "主人我呼叫你") }
        findViewById<Button>(R.id.btnSndNotify).setOnClickListener { sendSystemSound("alert_notify", "🔔 通知提示", "") }
        findViewById<Button>(R.id.btnSndWarn).setOnClickListener { sendSystemSound("alert_warn", "⚠️ 告警提示", "") }
        findViewById<Button>(R.id.btnSndAck).setOnClickListener { sendSystemSound("sound_ack", "✅ 确认反馈", "") }

        prevListener = app.transportManager.listener
        app.transportManager.setListener(object : com.inklink.common.transport.TransportListener {
            override fun onTextMessage(message: InkMessage) {
                prevListener?.onTextMessage(message)
                transportListener.onTextMessage(message)
            }
            override fun onAudioMessage(frame: ByteArray) {
                prevListener?.onAudioMessage(frame)
            }
            override fun onConnectionChanged(connected: Boolean) {
                prevListener?.onConnectionChanged(connected)
            }
        })

        // 初始向受控端拉取全量背包与状态
        app.transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_BAG_SYNC.code,
                fromDeviceId = app.deviceId,
                targetDeviceId = targetDeviceId,
                payload = "{}"
            )
        )

        btnTask.setOnClickListener {
            val input = EditText(this).apply { hint = "输入语音任务内容 (限120字)" }
            AlertDialog.Builder(this)
                .setTitle("发布远程语音任务")
                .setView(input)
                .setPositiveButton("发送") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isNotBlank()) {
                        val payload = RemoteTaskPayload(
                            taskId = UUID.randomUUID().toString(),
                            content = text
                        )
                        app.transportManager.sendMessage(
                            InkMessage(
                                type = MessageType.REMOTE_TASK_SEND.code,
                                fromDeviceId = app.deviceId,
                                targetDeviceId = targetDeviceId,
                                payload = gson.toJson(payload)
                            )
                        )
                        Toast.makeText(this, "任务已发送到受控端！", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnGift.setOnClickListener { showGiftPickerDialog() }

        btnGame.setOnClickListener { showGameInviteDialog() }

        // 远程逗弄（43 TEASE 子类型）：给孩子宠物挠一下，扣一点心情换欢笑
        val btnTease = findViewById<Button>(R.id.btnTeasePet)
        btnTease.setOnClickListener {
            val payload = PetBagInteractPayload(
                targetPetId = "",
                subType = "TEASE",
                visitorName = "爸爸/妈妈"
            )
            app.transportManager.sendMessage(
                InkMessage(
                    type = MessageType.PET_BAG_INTERACT.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = targetDeviceId,
                    payload = gson.toJson(payload)
                )
            )
            Toast.makeText(this, "已远程逗弄TA一下 😆", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 三类礼物选择面板（V1.1：44 零新增码位，giftType 判别）。
     * item=道具入库 / deco=解锁外观 / buff=即时增益 / coin=零花钱。
     */
    private fun showGiftPickerDialog() {
        val catalog = listOf(
            Triple("🎁 一键赠送全部资源", "all", "gift_all"),
            Triple("🍙 普通食物 x3", "item", "food_normal"),
            Triple("🍱 高级大餐 x2", "item", "food_premium"),
            Triple("🎾 玩具球 x2", "item", "toy"),
            Triple("📖 故事书 x2", "item", "book"),
            Triple("🧴 沐浴露 x2", "item", "shower_gel"),
            Triple("🧪 睡眠药水 x2", "item", "sleep_potion"),
            Triple("💊 治疗药剂 x1", "item", "potion_heal"),
            Triple("⚡ 精力Buff（+40，暂停衰减10min）", "buff", "buff_energy"),
            Triple("💖 心情Buff（+30，暂停衰减10min）", "buff", "buff_mood"),
            Triple("🧣 小头巾", "deco", "deco_bandana"),
            Triple("👓 圆眼镜", "deco", "deco_glasses"),
            Triple("🎀 蝴蝶结", "deco", "deco_bow"),
            Triple("👑 小皇冠", "deco", "deco_crown"),
            Triple("🪙 零花钱 +50", "coin", "coin_50")
        )
        val labels = catalog.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎁 赠送礼物（主控→受控）")
            .setItems(labels) { _, which ->
                val (_, type, itemId) = catalog[which]
                val count = if (type == "item") {
                    when (itemId) { "food_normal" -> 3; "potion_heal" -> 1; else -> 2 }
                } else 1
                sendGift(itemId, type, count)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendGift(itemId: String, giftType: String, count: Int) {
        if (targetDeviceId.isBlank()) {
            Toast.makeText(this, "请先在设备管理中选择目标设备", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = PetRemoteGiftPayload(itemId = itemId, count = count, giftType = giftType)
        app.transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_REMOTE_GIFT.code,
                fromDeviceId = app.deviceId,
                targetDeviceId = targetDeviceId,
                payload = gson.toJson(payload)
            )
        )
        Toast.makeText(this, "已赠送：$itemId（$giftType x$count）", Toast.LENGTH_SHORT).show()
    }

    /** 主控端统一宠物信息渲染（名字/阶段/四维/健康/状态 + 可选金币背包摘要）。 */
    private fun renderPetStats(
        pet: com.inklink.common.protocol.payload.PetItem,
        coin: Int? = null,
        petCount: Int? = null,
        pillCount: Int? = null
    ) {
        val stage = when (pet.lifeStage) {
            "EGG" -> "🥚蛋"
            "CUB" -> "🍼幼年"
            "JUVENILE" -> "🌱少年"
            "ADULT" -> "🌟成年"
            else -> "🌟成年"
        }
        val status = when {
            !pet.isAlive -> "🤒虚弱(需治疗)"
            pet.isSleeping -> "😴睡眠中"
            else -> ""
        }
        val formSuffix = when (pet.finalForm) {
            "BALANCED" -> " · ⚖️均衡"
            "PLAYFUL" -> " · 🎉爱玩"
            "STUDIOUS" -> " · 📚学霸"
            "DROOPY" -> " · 🌧️萎靡"
            else -> ""
        }
        // 性格标签（V1.1 9.10：playfulness/affection -> 游戏腔）
        val personality = when {
            pet.playfulness >= 40 && pet.affection >= 40 -> "🥳 黏人又活泼"
            pet.playfulness >= 40 -> "🎾 活泼好动"
            pet.learnCount >= 5 && pet.playfulness < 0 -> "📚 安静爱学习"
            pet.affection <= -20 -> "😾 有点怕生"
            pet.playfulness <= -20 -> "🌧️ 最近有点消沉"
            else -> "🙂 温和乖巧"
        }
        val sb = StringBuilder()
        sb.append("🐾 ${pet.name.ifBlank { pet.petType }} · $stage$formSuffix · Lv.${pet.level}\n")
        sb.append("🍖 ${pet.hunger}% · 💖 ${pet.happiness}% · ⚡ ${pet.energy}% · 🫧 ${pet.clean}%\n")
        sb.append("❤️ 健康 ${pet.health}%")
        if (status.isNotEmpty()) sb.append(" · $status")
        sb.append("\n🧠 性格：$personality（亲近 ${pet.affection} · 活泼 ${pet.playfulness}）")
        if (coin != null) {
            sb.append("\n🪙 金币 $coin · 伙伴 ${petCount ?: 0}")
            if (pillCount != null) sb.append(" · 治疗剂 $pillCount")
        }
        tvStats.text = sb.toString()
    }

    /** 上一快照等级缓存，用于升级时刻检测（阶段五）。 */
    private var lastSeenLevel = 0

    private fun notifyLevelUpIfRaised(petType: String, level: Int) {
        val previous = lastSeenLevel
        lastSeenLevel = level
        if (previous in 1 until level) {
            AlertDialog.Builder(this)
                .setTitle("🎉 升级时刻！")
                .setMessage("孩子的 $petType 升到 Lv.$level 啦！")
                .setPositiveButton("棒极了", null)
                .show()
        }
    }

    /**
     * 任务完成激励闭环（阶段五）：收到 PLAYED 回执时询问家长是否发放金币奖励。
     */
    private fun showTaskRewardDialog(taskId: String) {
        val rewardId = "coin_10"
        AlertDialog.Builder(this)
            .setTitle("✅ 任务完成！")
            .setMessage("孩子已完成语音任务，发放 10 金币奖励吗？")
            .setPositiveButton("发放奖励") { _, _ ->
                val payload = PetRemoteGiftPayload(itemId = rewardId, count = 1)
                app.transportManager.sendMessage(
                    InkMessage(
                        type = MessageType.PET_REMOTE_GIFT.code,
                        fromDeviceId = app.deviceId,
                        targetDeviceId = targetDeviceId,
                        payload = gson.toJson(payload)
                    )
                )
                Toast.makeText(this, "已发放 10 金币奖励", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    /**
     * 下发宠物行为事件 CMD_PET_EVENT(23)。
     *
     * 与 [sendInteractCmd]（31）的关系：23 是"事件 + 音效 + 可选播报"的一体化通道，受控端把两者
     * 汇入**同一条**结算核心，所以不存在双扣；31 保留给需要精确 count/foodType 的既有流程
     * （如长按 5 连投）。
     */
    private fun sendVoiceEvent(eventId: String, label: String, ttsText: String) {
        if (targetDeviceId.isBlank()) {
            Toast.makeText(this, "请先在设备管理中选择目标设备", Toast.LENGTH_SHORT).show()
            return
        }
        app.sendPetEvent(targetDeviceId, eventId, ttsText)
        val suffix = if (com.inklink.controller.audio.AudioSettings.isTtsEnabled(this) && ttsText.isNotBlank())
            "（含播报：$ttsText）" else "（仅音效）"
        Toast.makeText(this, "$label$suffix 已下发", Toast.LENGTH_SHORT).show()
    }

    /** 下发通用提示音 CMD_PLAY_SOUND(22)：不动宠物数值。 */
    private fun sendSystemSound(soundId: String, label: String, ttsText: String) {
        if (targetDeviceId.isBlank()) {
            Toast.makeText(this, "请先在设备管理中选择目标设备", Toast.LENGTH_SHORT).show()
            return
        }
        app.playHostSound(targetDeviceId, soundId, ttsText)
        Toast.makeText(this, "$label 已下发", Toast.LENGTH_SHORT).show()
    }

    /** 受控端饥饿上报弹窗：一键补喂，闭环到 event_feed。 */
    private fun showHungerAlert(ttsText: String) {
        AlertDialog.Builder(this)
            .setTitle("🍖 宠物饿了")
            .setMessage(if (ttsText.isBlank()) "受控端报告饥饿值过低，快去喂它。" else ttsText)
            .setPositiveButton("现在就喂") { _, _ -> sendVoiceEvent("event_feed", "🍖 喂养", "我吃饱啦") }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 下发远程互动指令 PET_INTERACT_CMD(31)，受控端将回 PET_INTERACT_ACK(32)。 */
    private fun sendInteractCmd(action: String, count: Int) {
        if (targetDeviceId.isBlank()) {
            Toast.makeText(this, "请先在设备管理中选择目标设备", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = com.inklink.common.protocol.payload.PetInteractCmdPayload(
            action = action,
            count = count
        )
        app.transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_INTERACT_CMD.code,
                fromDeviceId = app.deviceId,
                targetDeviceId = targetDeviceId,
                payload = gson.toJson(payload)
            )
        )
        Toast.makeText(this, "${actionLabel(action)}指令已下发", Toast.LENGTH_SHORT).show()
    }

    private fun actionLabel(action: String): String = when (action) {
        "FEED" -> "投喂"
        "PLAY" -> "玩耍"
        "CLEAN" -> "清洁"
        "SLEEP" -> "睡眠"
        "LEARN" -> "学习"
        "HEAL" -> "治疗"
        else -> "互动"
    }

    /** 家长在发起邀请时选定的出招（inviteId -> ROCK/PAPER/SCISSORS） */
    private val pendingGameChoices = mutableMapOf<String, String>()

    /** 发起远程猜拳对战：家长先选出招，邀请下发到受控端。 */
    private fun showGameInviteDialog() {
        val choices = arrayOf("石头 ✊", "剪刀 ✌️", "布 ✋")
        AlertDialog.Builder(this)
            .setTitle("🎮 请先选择你的出招")
            .setItems(choices) { _, which ->
                val myChoice = when (which) {
                    0 -> "ROCK"
                    1 -> "SCISSORS"
                    else -> "PAPER"
                }
                val inviteId = UUID.randomUUID().toString()
                pendingGameChoices[inviteId] = myChoice
                val payload = PetGameInvitePayload(inviteId = inviteId, gameType = "RPS")
                app.transportManager.sendMessage(
                    InkMessage(
                        type = MessageType.PET_GAME_INVITE.code,
                        fromDeviceId = app.deviceId,
                        targetDeviceId = targetDeviceId,
                        payload = gson.toJson(payload)
                    )
                )
                Toast.makeText(this, "对战邀请已下发，等待孩子出招…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 收到受控端出招后结算胜负。 */
    private fun settleGameResult(inviteId: String, childAction: String) {
        val myChoice = pendingGameChoices.remove(inviteId)
        val title = when {
            myChoice == null -> "🎮 对战结果揭晓！"
            myChoice == childAction -> "🤝 平局！"
            (myChoice == "ROCK" && childAction == "SCISSORS") ||
                (myChoice == "SCISSORS" && childAction == "PAPER") ||
                (myChoice == "PAPER" && childAction == "ROCK") -> "🏆 你赢了！"
            else -> "😆 孩子赢了！"
        }
        val msg = buildString {
            append("你的出招: ${myChoice ?: "未记录"}\n")
            append("孩子的出招: $childAction")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 恢复 Application 全局监听器，避免清空后 GPS/告警/聊天路由全部失效
        app.transportManager.setListener(prevListener)
    }
}
