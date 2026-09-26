package com.inklink.host.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.inklink.common.protocol.payload.PetItem
import com.inklink.host.R
import com.inklink.host.data.EventLogEntry
import com.inklink.host.pet.PetAiEngine
import com.inklink.host.security.PinSecurityManager
import com.inklink.host.service.InkForegroundService
import com.inklink.host.state.PetCatalog
import com.inklink.host.state.PetClock
import com.inklink.host.state.PetStateManager
import com.inklink.host.ui.view.PetSpriteView
import com.inklink.host.ui.view.PixelProgressBarView
import com.inklink.host.util.HapticUtil
import com.inklink.host.util.SoundEffectManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.xml.KonfettiView
import java.util.concurrent.TimeUnit

class PetMainActivity : AppCompatActivity() {

    private lateinit var petStateManager: PetStateManager
    private lateinit var pinSecurityManager: PinSecurityManager
    private lateinit var soundEffectManager: SoundEffectManager
    /**
     * 进程内共享播报门控（不各自 new LocalTtsManager）：每个 TextToSpeech 都是一条到系统
     * TTS 服务的 Binder 连接，UI + 服务 + 广播接收器各持一个会出现"两句同时在念、互相掐断"。
     */
    private val localTtsManager: com.inklink.host.audio.PetTtsGate by lazy {
        com.inklink.host.audio.PetTtsGate.get(this)
    }
    /**
     * 本地互动音效编排（音效先起 → 朗读串其后）。与 SoundEffectManager（无音频资产的
     * chiptune 系统音）并存：互动类音效走预制 wav，系统音/负面反馈走 chiptune。
     */
    private val petAudioFeedback: com.inklink.host.audio.PetAudioFeedback by lazy {
        com.inklink.host.audio.PetAudioFeedback(this)
    }
    private lateinit var petMiniGameManager: com.inklink.host.game.PetMiniGameManager
    private lateinit var taskQueueManager: com.inklink.host.task.TaskQueueManager
    private lateinit var petSpriteView: PetSpriteView
    private lateinit var konfettiView: KonfettiView
    private lateinit var progressHunger: PixelProgressBarView
    private lateinit var progressHappiness: PixelProgressBarView
    private lateinit var progressEnergy: PixelProgressBarView
    private lateinit var progressClean: PixelProgressBarView
    private lateinit var tvPetLevel: TextView
    private lateinit var tvPetName: TextView
    private lateinit var tvCoin: TextView
    private lateinit var btnFeed: MaterialButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnLearn: MaterialButton
    private lateinit var btnClean: MaterialButton
    private lateinit var btnSleep: MaterialButton
    private lateinit var btnTasks: MaterialButton
    private lateinit var btnBag: MaterialButton
    private lateinit var btnShop: MaterialButton
    private lateinit var btnGames: MaterialButton
    private lateinit var btnFriends: MaterialButton
    private lateinit var btnChat: MaterialButton
    private lateinit var btnAdmin: MaterialButton
    private lateinit var btnLearningHub: MaterialButton
    private lateinit var bannerLearning: View
    private lateinit var tvBannerStudy: TextView
    private lateinit var btnDiary: MaterialButton
    private lateinit var btnAchieve: MaterialButton
    private lateinit var btnFamily: MaterialButton
    private lateinit var btnAppearance: MaterialButton
    private lateinit var rootContainer: View

    private var aiEngine: PetAiEngine? = null
    private var petGestureDetector: android.view.GestureDetector? = null

    // 抚摸（长按）连击节流
    private var lastAffectionTs = 0L
    // 烦躁判定：2s 窗口内点击 >=8 次（design 阈值）
    private val tapWindow = ArrayDeque<Long>()
    // 首次连点仅警告、不扣心情（design：第一次仅警告气泡）
    private var firstAnnoyWarned = false

    // 分钟级循环：随机事件 roll + 每日目标提示
    private var minuteTickerJob: kotlinx.coroutines.Job? = null

    // 游戏化提示去抖
    private var lastDailyNoticeDate = ""
    private var lastMilestoneShown = 0

    /**
     * 功能介绍双通道：TTS 朗读 + 像素气泡同显。
     * TTS 不可用时气泡仍然可见，保证"点击功能有介绍"的体验闭环。
     */
    private fun speakGuide(text: String) {
        showAiBubble(text)
        localTtsManager.speak(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet_main)

        checkAndRequestPermissions()

        petStateManager = PetStateManager(this)
        pinSecurityManager = PinSecurityManager(this)
        soundEffectManager = SoundEffectManager(this)
        localTtsManager.addProbeListener { probe ->
            if (probe == com.inklink.host.audio.PetTtsGate.Probe.UNAVAILABLE) {
                // TTS 引擎全部不可用：降级为气泡文字 + 系统提示音，保证"点击有反馈"
                soundEffectManager.playFeedSound()
            }
        }
        petMiniGameManager = com.inklink.host.game.PetMiniGameManager(this)
        taskQueueManager = com.inklink.host.task.TaskQueueManager(this)

        initViews()
        setupListeners()
        observeServiceEvents()

        // 外观模式：家长端可切换，受控端主界面也可直接选择像素宠物
        petSpriteView.setRenderMode(com.inklink.host.state.PetRenderMode.get(this))

        // 启动后台前台服务
        val serviceIntent = Intent(this, InkForegroundService::class.java)
        startService(serviceIntent)

        // 启动宠物空闲 AI 引擎（V1.1：状态抱怨 + 性格加权自主行为 + 情绪台词气泡）
        aiEngine = PetAiEngine(lifecycleScope) { action ->
            runOnUiThread {
                petSpriteView.fireTrigger(action.trigger)
                if (action.line != null) {
                    showAiBubble(action.line)
                    localTtsManager.speak(action.line)
                }
            }
        }
        aiEngine?.snapshotProvider = {
            val p = petStateManager.getActivePet()
            PetAiEngine.Snapshot(
                hunger = p.hunger,
                energy = p.energy,
                clean = p.clean,
                happiness = p.happiness,
                alive = p.isAlive,
                sleeping = p.isSleeping,
                playfulness = p.playfulness,
                affection = p.affection,
                isEgg = p.lifeStage == "EGG"
            )
        }
        aiEngine?.start()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val ungranted = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), 1001)
        }

        // 引导忽略电池优化白名单
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        petSpriteView = findViewById(R.id.petSpriteView)
        konfettiView = findViewById(R.id.konfettiView)
        progressHunger = findViewById(R.id.progressHunger)
        progressHappiness = findViewById(R.id.progressHappiness)
        progressEnergy = findViewById(R.id.progressEnergy)
        progressClean = findViewById(R.id.progressClean)
        tvPetLevel = findViewById(R.id.tvPetLevel)
        tvPetName = findViewById(R.id.tvPetName)
        tvCoin = findViewById(R.id.tvCoin)
        btnFeed = findViewById(R.id.btnFeed)
        btnPlay = findViewById(R.id.btnPlay)
        btnLearn = findViewById(R.id.btnLearn)
        btnClean = findViewById(R.id.btnClean)
        btnSleep = findViewById(R.id.btnSleep)
        btnTasks = findViewById(R.id.btnTasks)
        btnBag = findViewById(R.id.btnBag)
        btnShop = findViewById(R.id.btnShop)
        btnGames = findViewById(R.id.btnGames)
        btnFriends = findViewById(R.id.btnFriends)
        btnChat = findViewById(R.id.btnChat)
        btnAdmin = findViewById(R.id.btnAdmin)
        btnLearningHub = findViewById(R.id.btnLearningHub)
        bannerLearning = findViewById(R.id.bannerLearning)
        tvBannerStudy = findViewById(R.id.tvBannerStudy)
        runCatching {
            val (m, _) = com.inklink.host.learning.LearningManager.get(this).todaySummary()
            tvBannerStudy.text = "识字 · 拼音 · 口算 · 古诗 | 今天 $m 分钟"
        }
        btnDiary = findViewById(R.id.btnDiary)
        btnAchieve = findViewById(R.id.btnAchieve)
        btnFamily = findViewById(R.id.btnFamily)
        btnAppearance = findViewById(R.id.btnAppearance)

        progressHunger.color = 0xFFFF7043.toInt()
        progressHappiness.color = 0xFFEC407A.toInt()
        progressEnergy.color = 0xFFFFA726.toInt()
        progressClean.color = 0xFF4FC3F7.toInt()

        setupSpriteGestures()

        updatePetUi(petStateManager.getActivePet())
        startMinuteTicker()
        // 回到界面补一次随机事件判定（内部含 5min 冷却与低数值保护）
        lifecycleScope.launch {
            kotlinx.coroutines.delay(4_000L)
            runCatching {
                if (!isFinishing && !isDestroyed) {
                    petStateManager.rollRandomEvent()?.let { msg ->
                        showAiBubble(msg)
                        playStarParticles()
                        updatePetUi(petStateManager.getActivePet())
                    }
                }
            }
        }
    }

    /**
     * V1.1 三手势（裁决：Rive 注视删除，部件 Rig 接管）：
     * 单击 -> 看向指尖回弹+抚摸结算；2s 内连点>=8 -> 烦躁；长按 -> 深度抚摸撒心。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSpriteGestures() {
        petGestureDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    handleSpriteTap(e.x)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    HapticUtil.tap(petSpriteView)
                    petSpriteView.onAffection()
                    val (_, deny) = petStateManager.longPressPet()
                    if (deny != null) {
                        PixelToast.show(rootContainer, deny)
                        localTtsManager.speak(deny)
                    } else {
                        floatText("+❤ 抚摸")
                        petAudioFeedback.feedback("pet_touch", "好舒服，喜欢被摸摸", true) {}
                    }
                    updatePetUi(petStateManager.getActivePet())
                }
            })
        petSpriteView.setOnTouchListener { _, event ->
            petGestureDetector?.onTouchEvent(event) ?: false
        }
    }

    private fun handleSpriteTap(x: Float) {
        HapticUtil.tap(petSpriteView)
        petSpriteView.onTapAt(petSpriteView.normalizedXof(x))
        val now = System.currentTimeMillis()
        tapWindow.addLast(now)
        // design 9.x：连点判定窗口 2s
        while (tapWindow.size > 8 || (tapWindow.isNotEmpty() && now - tapWindow.first() > 2_000L)) {
            tapWindow.removeFirst()
        }
        if (tapWindow.size >= 8) {
            // 快速连点(≥8/2s)=烦躁。第一次仅警告不扣心情（design：第一次仅警告气泡）。
            tapWindow.clear()
            petSpriteView.onAnnoy()
            if (!firstAnnoyWarned) {
                firstAnnoyWarned = true
                HapticUtil.tap(petSpriteView)
                PixelToast.show(rootContainer, "别一直戳啦，TA会烦的！")
                soundEffectManager.play(SoundEffectManager.Sfx.GROAN)
                localTtsManager.speak("别一直戳啦，我会烦的！")
            } else {
                val (_, line) = petStateManager.annoyPet()
                soundEffectManager.play(SoundEffectManager.Sfx.GROAN)
                PixelToast.show(rootContainer, line)
                localTtsManager.speak(line)
            }
            updatePetUi(petStateManager.getActivePet())
            return
        }
        val wasEgg = petStateManager.getActivePet().lifeStage == "EGG"
        val (delta, deny) = petStateManager.touchPet()
        if (deny != null) {
            PixelToast.show(rootContainer, deny)
            localTtsManager.speak(deny)
        } else if (delta > 0) {
            playHeartParticles()
            floatText("+❤ $delta")
            petAudioFeedback.feedback("pet_touch", "好开心呀！", true) {}
            if (didHatch(wasEgg)) showHatchCelebration()
        }
        updatePetUi(petStateManager.getActivePet())
    }

    /** 统一按压微缩反馈（规范 5.4：按下 0.92 缩放）。 */
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
            false
        }
    }

    private fun setupListeners() {
        pressScale(btnFeed)
        pressScale(btnPlay)
        pressScale(btnLearn)
        pressScale(btnClean)
        pressScale(btnSleep)

        btnFeed.setOnClickListener {
            HapticUtil.tap(it)
            val wasEgg = petStateManager.getActivePet().lifeStage == "EGG"
            val (deltaHunger, _, deny) = petStateManager.feed(1, "food_normal")
            if (deny != null) {
                PixelToast.show(rootContainer, deny)
                showBagSheet()
            } else {
                petAudioFeedback.feedback("pet_feed", "好吃！饱食度加了${deltaHunger}", true) {}
                flyFoodToPet("🍙")
                petSpriteView.fireTrigger("FEED")
                playHeartParticles()
                if (deltaHunger > 0) floatText("+$deltaHunger 饱食")
                if (didHatch(wasEgg)) showHatchCelebration()
            }
            updatePetUi(petStateManager.getActivePet())
        }
        btnFeed.setOnLongClickListener {
            speakGuide("投喂按钮：为宠物提供食物，增加饱食度与好感度。")
            true
        }

        btnPlay.setOnClickListener {
            HapticUtil.tap(it)
            val wasEgg = petStateManager.getActivePet().lifeStage == "EGG"  // 先抓快照再互动
            val (deltaHappy, energyCost, deny) = petStateManager.playWith()
            if (deny != null) {
                PixelToast.show(rootContainer, deny)
            } else {
                petSpriteView.fireTrigger("EXCITED")
                playStarParticles()
                petAudioFeedback.feedback("pet_happy", "太开心啦！心情加了${deltaHappy}", true) {}
                floatText("+$deltaHappy 心情 -$energyCost 精力")
                if (didHatch(wasEgg)) showHatchCelebration()
            }
            updatePetUi(petStateManager.getActivePet())
        }
        btnPlay.setOnLongClickListener {
            speakGuide("玩耍按钮：陪宠物做游戏，大幅提升心情，但会消耗精力。")
            true
        }

        btnLearn.setOnClickListener {
            HapticUtil.tap(it)
            val wasEgg = petStateManager.getActivePet().lifeStage == "EGG"  // 先抓快照再互动
            val (gainExp, deny) = petStateManager.learn()
            if (deny != null) {
                PixelToast.show(rootContainer, deny)
            } else {
                flyFoodToPet("📖")
                petSpriteView.fireTrigger("STUDY")
                soundEffectManager.play(SoundEffectManager.Sfx.READ)
                floatText("经验 +$gainExp")
                localTtsManager.speak("学到新知识啦，经验加了${gainExp}")
                if (didHatch(wasEgg)) showHatchCelebration()
            }
            updatePetUi(petStateManager.getActivePet())
        }
        btnLearn.setOnLongClickListener {
            speakGuide("学习按钮：宠物学习新知识，消耗精力获得大量成长经验。")
            true
        }

        btnClean.setOnClickListener {
            HapticUtil.tap(it)
            val wasEgg = petStateManager.getActivePet().lifeStage == "EGG"  // 先抓快照再互动
            val (deltaClean, deny) = petStateManager.cleanPet()
            if (deny != null) {
                PixelToast.show(rootContainer, deny)
            } else {
                flyFoodToPet("🫧")
                playBubbleParticles()
                soundEffectManager.play(SoundEffectManager.Sfx.WATER)
                floatText("清洁 +$deltaClean")
                localTtsManager.speak("洗香香啦，清洁度加了${deltaClean}")
                if (didHatch(wasEgg)) showHatchCelebration()
            }
            updatePetUi(petStateManager.getActivePet())
        }
        btnClean.setOnLongClickListener {
            speakGuide("清洁按钮：给宠物洗澡，恢复清洁度，脏兮兮会影响健康哦。")
            true
        }

        btnSleep.setOnClickListener {
            HapticUtil.tap(it)
            val (sleeping, deny) = petStateManager.toggleSleep()
            if (deny != null) {
                PixelToast.show(rootContainer, deny)
            } else {
                petSpriteView.fireTrigger(if (sleeping) "SLEEP" else "EXCITED")
                PixelToast.show(rootContainer, if (sleeping) "晚安，让TA安静休息～" else "TA睡饱啦，精神满满！")
                petAudioFeedback.feedback(
                    if (sleeping) "pet_sleep" else "pet_wakeup",
                    if (sleeping) "宠物睡着啦" else "宠物醒啦，精神满满",
                    true
                ) {}
            }
            updatePetUi(petStateManager.getActivePet())
        }
        btnSleep.setOnLongClickListener {
            speakGuide("睡觉按钮：宠物入睡后精力会慢慢恢复。")
            true
        }

        btnTasks.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("任务箱：查看家长下发的远程语音任务，支持点击回放。")
            showTaskSheet()
        }
        btnTasks.setOnLongClickListener {
            speakGuide("任务箱：查看家长下发的远程语音任务，支持点击回放。")
            true
        }

        btnBag.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("宠物背包：查看所有收集到的宠物伙伴，点选即可切换。")
            showBagSheet()
        }
        btnBag.setOnLongClickListener {
            speakGuide("宠物背包：查看所有收集到的宠物伙伴，点选即可切换。")
            true
        }

        btnShop.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("商店：购买新的宠物蛋、零食和治疗药丸。")
            showShopSheet()
        }
        btnShop.setOnLongClickListener {
            speakGuide("商店：购买新的宠物蛋、零食和治疗药丸。")
            true
        }

        // 游戏中心：保留猜拳/翻牌/转盘（阶段五，赚金币闭环）
        btnGames.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("游戏中心：包含猜拳、记忆翻牌与幸运转盘，每日通关赚取金币。")
            showMiniGameDialog()
        }
        btnGames.setOnLongClickListener {
            speakGuide("游戏中心：包含猜拳、记忆翻牌与幸运转盘，每日通关赚取金币。")
            true
        }

        // 好友圈：跨设备互玩对战与串门（阶段六）
        btnFriends.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("好友圈：添加好友，和对方的宠物玩游戏、串门。")
            startActivity(android.content.Intent(this, FriendActivity::class.java))
        }
        btnFriends.setOnLongClickListener {
            speakGuide("好友圈：添加好友，和对方的宠物玩游戏、串门。")
            true
        }

        // 与家长聊天（文字/图片/语音）
        btnChat.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("聊天：和爸爸妈妈发消息。")
            startActivity(android.content.Intent(this, ChatActivity::class.java))
        }
        btnChat.setOnLongClickListener {
            speakGuide("聊天：和爸爸妈妈发消息。")
            true
        }

        // 学习乐园一级横幅(与宠物并列)
        bannerLearning.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("学习乐园：上课赚金币，喂饱小宠物。")
            startActivity(Intent(this, com.inklink.host.ui.learning.LearningHubActivity::class.java))
        }

        // 学习乐园(M1 识字屋):学习赚金币 → PetStateManager.addReward 统一入账
        btnLearningHub.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("学习乐园：上课赚金币，喂饱小宠物。")
            startActivity(Intent(this, com.inklink.host.ui.learning.LearningHubActivity::class.java))
        }
        btnLearningHub.setOnLongClickListener {
            speakGuide("学习乐园：上课赚金币，喂饱小宠物。")
            true
        }

        // 游戏化系统入口：日记 / 成就（里程碑+每日目标） / 亲情（孩子→家长互动）
        btnDiary.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("冒险日记：记录宠物成长中的大事记。")
            showLogSheet()
        }
        btnDiary.setOnLongClickListener {
            speakGuide("冒险日记：记录宠物成长中的大事记。")
            true
        }
        btnAchieve.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("成就：查看里程碑与今日目标。")
            showAchieveSheet()
        }
        btnAchieve.setOnLongClickListener {
            speakGuide("成就：查看里程碑与今日目标。")
            true
        }
        btnFamily.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("亲情互动：给爸爸妈妈寄感谢礼包，或者打电话叫他们来看宠物。")
            showRemoteGiftDialog()
        }
        btnFamily.setOnLongClickListener {
            speakGuide("亲情互动：给爸爸妈妈寄感谢礼包，或者打电话叫他们来看宠物。")
            true
        }

        btnAppearance.setOnClickListener {
            HapticUtil.tap(it)
            speakGuide("外观：可以直接切换复古像素宠物，养成进度不会改变。")
            showAppearanceDialog()
        }
        btnAppearance.setOnLongClickListener {
            speakGuide("外观：可以直接切换复古像素宠物，养成进度不会改变。")
            true
        }

        // 管控后台：单击直达 PIN 验证（2026-08-31 由长按 3s 改为单击，PIN 仍防儿童误入）
        btnAdmin.setOnClickListener {
            HapticUtil.tap(it)
            showPinUnlockDialog()
        }
        btnAdmin.setOnLongClickListener {
            speakGuide("家长管控：查看宠物数据与设置，需要输入管理密码。")
            true
        }
    }

    /** 受控端直接选择宠物外观；仅改变渲染偏好，不触碰养成数据。 */
    private fun showAppearanceDialog() {
        val options = arrayOf("原版卡通（矢量）", "复古像素（掌机风）", "像素素材（PNG 宠物）", "街机像素（大屏素材）", "场景宠（云朵 LCD）")
        val current = com.inklink.host.state.PetRenderMode.get(this).ordinal
        var dialog: androidx.appcompat.app.AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择宠物外观")
            .setSingleChoiceItems(options, current) { _, which ->
                val mode = com.inklink.host.state.PetRenderMode.values()[which]
                com.inklink.host.state.PetRenderMode.set(this, mode)
                petSpriteView.setRenderMode(mode)
                localTtsManager.speak(
                    when (mode) {
                        com.inklink.host.state.PetRenderMode.PIXEL_PNG -> "像素素材已开启，缺帧自动回退程序化像素"
                        com.inklink.host.state.PetRenderMode.PIXEL_RETRO -> "像素模式已开启"
                        com.inklink.host.state.PetRenderMode.VECTOR_OLD -> "原版卡通已恢复"
                        com.inklink.host.state.PetRenderMode.PIXEL_ARCADE_SPRITE -> "街机像素素材已开启"
                        com.inklink.host.state.PetRenderMode.PIXEL_SCENE -> "云朵 LCD 场景宠已开启"
                    }
                )
                dialog?.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updatePetUi(pet: PetItem) {
        tvPetName.text = pet.name
        val form = com.inklink.host.state.PetMoodMapper.formLabel(pet.finalForm)
        val formSuffix = if (form != null) " · $form" else ""
        tvPetLevel.text = "Lv.${pet.level} · ${com.inklink.host.state.PetMoodMapper.stageLabel(pet.lifeStage)}$formSuffix · ❤${pet.health}"
        tvCoin.text = "🪙 ${petStateManager.petBag.coin}"
        // 像素分段进度条（V1.1：低值红闪）
        progressHunger.progress = pet.hunger
        progressHappiness.progress = pet.happiness
        progressEnergy.progress = pet.energy
        progressClean.progress = pet.clean
        // 外观随物种/情绪/生命周期/睡眠实时变化（多物种 Canvas 形态 + 表情）
        val weak = !pet.isAlive || pet.health <= 0
        val mood = com.inklink.host.state.PetMoodMapper.moodOf(
            pet.hunger, pet.happiness, pet.clean, pet.energy, pet.health, pet.isSleeping, weak
        )
        petSpriteView.setPetAppearance(pet.petType, mood, pet.lifeStage, pet.isSleeping)
        petSpriteView.setScene(pet.sceneId)
        petSpriteView.setDecoration(pet.decoId)
        petSpriteView.setFinalForm(pet.finalForm)
        if (pet.passedAtTs > 0) {
            showPassedBubble()
        } else {
            showMoodBubble(mood, weak || pet.health < 40)
        }
        btnSleep.text = if (pet.isSleeping) "⏰唤醒" else "😴睡觉"
        // 升级时刻：level 上升触发全屏庆祝（阶段五）
        checkLevelUpMoment(pet.level, pet.lifeStage)
        // 状态发生变化后按 3s 节流上报主控端
        throttledPetStateSync()
    }

    /** 状态提示气泡：饥饿/困倦/脏乱/生病时展示；虚弱或病重时点击可治疗。 */
    private fun showMoodBubble(mood: String, healable: Boolean) {
        val text = com.inklink.host.state.PetMoodMapper.bubbleText(mood)
        val bubble = findViewById<TextView?>(R.id.tvMoodBubble) ?: return
        if (text == null) {
            bubble.visibility = android.view.View.GONE
            bubble.setOnClickListener(null)
            return
        }
        bubble.text = if (healable) "$text｜点此治疗" else text
        bubble.visibility = android.view.View.VISIBLE
        if (healable) {
            bubble.setOnClickListener { HapticUtil.tap(it); healActivePet() }
        } else {
            bubble.setOnClickListener(null)
        }
    }

    /** 使用治疗药丸唤醒虚弱/生病的宠物。 */
    private fun healActivePet() {
        val (ok, msg) = petStateManager.heal()
        PixelToast.show(rootContainer, msg)
        if (ok) {
            playStarParticles()
            petSpriteView.fireTrigger("EXCITED")
            soundEffectManager.play(SoundEffectManager.Sfx.LEVEL_UP)
            floatText("💊 康复啦")
        }
        updatePetUi(petStateManager.getActivePet())
    }

    /** 蛋是否已破壳（动作前记录，因 PetItem 为同一可变实例）。 */
    private fun didHatch(wasEgg: Boolean): Boolean =
        wasEgg && petStateManager.getActivePet().lifeStage != "EGG"

    private fun showHatchCelebration() {
        playHeartParticles()
        playStarParticles()
        petSpriteView.fireTrigger("EXCITED")
        soundEffectManager.play(SoundEffectManager.Sfx.HATCH)
        floatText("🐣 破壳啦！")
        localTtsManager.speak("太棒了！宠物蛋孵化成功了")
        PixelToast.showLong(rootContainer, "🐣 蛋孵化成功，新伙伴诞生！")
    }

    /** UI 层升级/成长检测：等级上升庆祝并飘字，生命周期进阶提示。 */
    private fun checkLevelUpMoment(newLevel: Int, newStage: String) {
        val previous = lastShownLevel
        lastShownLevel = newLevel
        val stageChanged = lastShownStage.isNotEmpty() && lastShownStage != newStage
        lastShownStage = newStage
        if (previous in 1 until newLevel) {
            playHeartParticles()
            soundEffectManager.play(SoundEffectManager.Sfx.LEVEL_UP)
            floatText("Lv.$newLevel ⬆")
            localTtsManager.speak("恭喜！宠物升级到 ${newLevel} 级啦")
        }
        if (stageChanged && newLevel > 1) {
            val label = com.inklink.host.state.PetMoodMapper.stageLabel(newStage)
            playStarParticles()
            floatText("成长 $label")
            localTtsManager.speak("TA长大了一些，现在是${label}啦")
        }
    }

    /** 规范 5.2/5.3：道具图标从操作坞飞向宠物中心。 */
    private fun flyFoodToPet(emoji: String) {
        val root = findViewById<android.view.ViewGroup?>(R.id.rootContainer) ?: return
        val label = TextView(this).apply {
            text = emoji
            textSize = 30f
            elevation = 12f
        }
        root.addView(label)
        // 起点：操作坞中央；终点：宠物视图中心
        label.x = root.width / 2f - 30f
        label.y = root.height - 220f
        val targetX = petSpriteView.x + petSpriteView.width / 2f - 30f
        val targetY = petSpriteView.y + petSpriteView.height / 2f - 30f
        label.animate()
            .x(targetX)
            .y(targetY)
            .alpha(0f)
            .scaleX(1.6f)
            .scaleY(1.6f)
            .setDuration(560)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { root.removeView(label) }
            .start()
    }

    /** 规范 5.4：数值/等级飘字，上浮淡出。 */
    private fun floatText(content: String) {
        val root = findViewById<android.view.ViewGroup?>(R.id.rootContainer) ?: return
        val label = TextView(this).apply {
            setText(content)
            textSize = 16f
            setTextColor(Color.parseColor("#FFD54F"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = 16f
        }
        root.addView(label)
        label.x = petSpriteView.x + petSpriteView.width / 2f - 60f
        label.y = petSpriteView.y + 20f
        label.animate()
            .y(label.y - 140f)
            .alpha(0f)
            .setDuration(900)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { root.removeView(label) }
            .start()
    }

    private fun playStarParticles() {
        emitParty(spread = 360, count = 24, colors = listOf(0xFFFFD54F.toInt(), 0xFFFFA726.toInt(), 0xFFFFF176.toInt()), speed = 26f)
    }

    private fun playBubbleParticles() {
        emitParty(spread = 180, count = 22, colors = listOf(0xFF4FC3F7.toInt(), 0xFFB3E5FC.toInt(), 0xFFE1F5FE.toInt()), speed = 18f)
    }

    private fun emitParty(spread: Int, count: Int, colors: List<Int>, speed: Float) {
        val owner = this as androidx.lifecycle.LifecycleOwner
        if (owner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        konfettiView.start(
            Party(
                speed = 0f,
                maxSpeed = speed,
                damping = 0.9f,
                spread = spread,
                colors = colors,
                emitter = Emitter(duration = 120, TimeUnit.MILLISECONDS).max(count),
                position = Position.Relative(0.5, 0.42)
            )
        )
    }

    private var lastShownLevel = 0
    private var lastShownStage: String = ""

    private var lastStateSyncTs = 0L

    /**
     * 本地状态变化 3s 节流上报 PET_STATE_SYNC(30) 给主控端
     */
    private fun throttledPetStateSync() {
        val now = System.currentTimeMillis()
        if (now - lastStateSyncTs < 3_000L) return
        lastStateSyncTs = now
        runCatching {
            val app = application as com.inklink.host.InkHostApplication
            val pet = petStateManager.getActivePet()
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage(
                    type = com.inklink.common.protocol.MessageType.PET_STATE_SYNC.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = app.transportManager.defaultTargetDeviceId,
                    payload = com.google.gson.Gson().toJson(pet)
                )
            )
        }
    }

    /**
     * 切换活跃宠物后上报 PET_SWITCH_ACTIVE(41)，随后附带 PET_BAG_SYNC 全量刷新主控端视图
     */
    private fun notifyPetSwitched(petId: String) {
        runCatching {
            val app = application as com.inklink.host.InkHostApplication
            val gson = com.google.gson.Gson()
            val payload = org.json.JSONObject().apply {
                put("petId", petId)
                put("timestamp", System.currentTimeMillis())
            }
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage(
                    type = com.inklink.common.protocol.MessageType.PET_SWITCH_ACTIVE.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = app.transportManager.defaultTargetDeviceId,
                    payload = payload.toString()
                )
            )
            // 全量背包同步，保证主控端宠物列表一致
            val bag = petStateManager.petBag
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage(
                    type = com.inklink.common.protocol.MessageType.PET_BAG_SYNC.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = app.transportManager.defaultTargetDeviceId,
                    payload = gson.toJson(bag)
                )
            )
        }
    }

    /**
     * 商店购买结果上报 PET_SHOP_BUY(42)，主控端展示金币与购买结果
     */
    private fun notifyShopBuy(itemId: String, itemType: String, success: Boolean, newCoin: Int) {
        runCatching {
            val app = application as com.inklink.host.InkHostApplication
            val payload = com.inklink.common.protocol.payload.PetShopBuyPayload(
                itemId = itemId,
                itemType = itemType,
                success = success,
                newCoin = newCoin
            )
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage(
                    type = com.inklink.common.protocol.MessageType.PET_SHOP_BUY.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = app.transportManager.defaultTargetDeviceId,
                    payload = com.google.gson.Gson().toJson(payload)
                )
            )
        }
    }

    private fun playHeartParticles() {
        // 后台/销毁后禁止派发粒子，避免泄漏窗口
        val owner = this as androidx.lifecycle.LifecycleOwner
        if (owner.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        val party = Party(
            speed = 0f,
            maxSpeed = 30f,
            damping = 0.9f,
            spread = 360,
            colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
            emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(30),
            position = Position.Relative(0.5, 0.5)
        )
        konfettiView.start(party)
    }

    private fun showMiniGameDialog() {
        speakGuide("游戏中心有三种小游戏：石头剪刀布、记忆翻牌和幸运转盘，每天前8局赢金币，快来试试吧！")
        val options = arrayOf("石头剪刀布 ✊✋✌️", "4x4 记忆翻牌 🎴", "幸运转盘 🎡")
        MaterialAlertDialogBuilder(this)
            .setTitle("宠物小游戏")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playRpsGame()
                    1 -> playMemoryMatchGame()
                    2 -> playSpinWheelGame()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 弹层内通用弹簧入场（game-juice：Overshoot 弹出 + 触觉）。 */
    private fun bounceIn(v: View) {
        v.scaleX = 0.3f
        v.scaleY = 0.3f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
            .start()
    }

    /** 手部上下抖动（猜拳 suspense）：remaining 个完整起落周期，结束后回落原位。 */
    private fun shakeHand(v: View, remaining: Int, amp: Float = 56f) {
        if (remaining <= 0) {
            v.translationY = 0f
            return
        }
        v.animate().translationY(-amp).setDuration(110)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                v.animate().translationY(0f).setDuration(110)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { shakeHand(v, remaining - 1, amp * 0.85f) }
                    .start()
            }
            .start()
    }

    /** 猜拳揭晓：弹簧定格 + 结果横幅 + 获胜全屏礼花/金币音。 */
    private fun playRpsGame() {
        val (dialog, root) = buildSheet("✊ 石头剪刀布", "和TA出拳，获胜赚金币（每日前8局有奖）")
        val arena = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        val myHand = TextView(this).apply {
            text = "✊"; textSize = 64f; setPadding(40, 0, 40, 0)
        }
        val vs = TextView(this).apply {
            text = "⚡"; textSize = 28f; setTextColor(Color.parseColor("#FFA726"))
        }
        val petHand = TextView(this).apply {
            text = "✊"; textSize = 64f; setPadding(40, 0, 40, 0)
        }
        arena.addView(myHand); arena.addView(vs); arena.addView(petHand)
        root.addView(arena)
        val tvResult = TextView(this).apply {
            text = "选一个出手势！"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 8, 0, 20)
        }
        root.addView(tvResult)

        var playing = false
        val handEmoji = mapOf(
            com.inklink.host.game.PetMiniGameManager.Choice.ROCK to "✊",
            com.inklink.host.game.PetMiniGameManager.Choice.SCISSORS to "✌️",
            com.inklink.host.game.PetMiniGameManager.Choice.PAPER to "✋"
        )
        for ((choice, emoji) in handEmoji) {
            val btn = MaterialButton(this).apply {
                text = "$emoji ${choice.name}"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 6, 8, 6) }
                setOnClickListener {
                    if (playing) return@setOnClickListener
                    playing = true
                    HapticUtil.tap(this)
                    myHand.text = emoji
                    tvResult.text = "石头——剪刀——布！"
                    soundEffectManager.play(SoundEffectManager.Sfx.PLAY)
                    // 双方手抖动 3 次后定格
                    shakeHand(myHand, 3)
                    shakeHand(petHand, 3)
                    val (botChoice, result) = petMiniGameManager.playRockPaperScissors(choice)
                    petHand.postDelayed({
                        myHand.translationY = 0f
                        petHand.translationY = 0f
                        petHand.text = handEmoji[botChoice] ?: "✊"
                        bounceIn(myHand); bounceIn(petHand)
                        val (verdictText, color) = when (result) {
                            com.inklink.host.game.PetMiniGameManager.Result.WIN -> "🎉 你赢了！" to "#43A047"
                            com.inklink.host.game.PetMiniGameManager.Result.LOSE -> "😆 TA赢了！" to "#E53935"
                            else -> "🤝 平局！" to "#FB8C00"
                        }
                        tvResult.text = verdictText
                        tvResult.setTextColor(Color.parseColor(color))
                        if (result == com.inklink.host.game.PetMiniGameManager.Result.WIN) {
                            if (petMiniGameManager.canGainRewardToday()) {
                                petStateManager.addReward(coin = 10, exp = 15)
                                petMiniGameManager.recordGamePlayed()
                                soundEffectManager.play(SoundEffectManager.Sfx.COIN)
                                playStarParticles()
                                floatText("+10 🪙 +15 EXP")
                                localTtsManager.speak("你赢啦，获得10金币和15经验")
                            } else {
                                PixelToast.show(rootContainer, "获胜！(今日防刷上限，无奖励)")
                            }
                            petSpriteView.fireTrigger("EXCITED")
                            updatePetUi(petStateManager.getActivePet())
                        } else if (result == com.inklink.host.game.PetMiniGameManager.Result.LOSE) {
                            petSpriteView.fireTrigger("ANNOY")
                        }
                        playing = false
                    }, 700L)
                }
            }
            root.addView(btn)
        }
        dialog.show()
    }

    /** 4x4 记忆翻牌：真配对玩法，翻牌 3D 动画 + 配对弹簧 + 全对礼花。 */
    private fun playMemoryMatchGame() {
        val (dialog, root) = buildSheet("🎴 4x4 记忆翻牌", "翻开两张相同的即可配对，全部配对有奖")
        val faces = listOf("🐱", "🐶", "🐰", "🐧", "🐹", "🐼", "🦊", "🐲")
        val deck = (faces + faces).shuffled()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 4
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        val cards = ArrayList<TextView>()
        var firstIndex = -1
        var lockBoard = false
        var matchedPairs = 0
        val size = (resources.displayMetrics.widthPixels * 0.19f).toInt()
        val cardBack = (0..15).map { if (it % 2 == 0) Color.parseColor("#5C6BC0") else Color.parseColor("#7E57C2") }

        val tvTip = TextView(this).apply {
            text = "已配对 0/8"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 16, 0, 8)
        }

        val onCardTap: (Int) -> Unit = fun(index: Int) {
            val card = cards[index]
            if (lockBoard || card.text != "❓") return
            HapticUtil.tap(card)
            // 翻面：rotateX 90° 换字再翻回
            card.animate().rotationX(90f).setDuration(120).withEndAction {
                card.text = deck[index]
                card.setTextColor(Color.parseColor("#212121"))
                card.setBackgroundColor(Color.parseColor("#FFFFFF"))
                card.animate().rotationX(0f).setDuration(120).start()
            }.start()
            if (firstIndex < 0) {
                firstIndex = index
                return
            }
            lockBoard = true
            val a = firstIndex
            firstIndex = -1
            if (deck[a] == deck[index]) {
                matchedPairs++
                tvTip.text = "已配对 $matchedPairs/8"
                soundEffectManager.play(SoundEffectManager.Sfx.EAT)
                cards[index].postDelayed({
                    bounceIn(card); bounceIn(cards[a])
                    lockBoard = false
                    if (matchedPairs == 8) {
                        // 全对：结算
                        if (petMiniGameManager.canGainRewardToday()) {
                            petStateManager.addReward(coin = 15, exp = 20)
                            petMiniGameManager.recordGamePlayed()
                            soundEffectManager.play(SoundEffectManager.Sfx.COIN)
                            playStarParticles()
                            floatText("+15 🪙 +20 EXP")
                            PixelToast.showLong(rootContainer, "记忆翻牌大成功！获得 15 金币与 20 经验")
                            localTtsManager.speak("记忆翻牌全部配对成功，获得15金币和20经验")
                            petSpriteView.fireTrigger("EXCITED")
                        } else {
                            PixelToast.show(rootContainer, "通关！(今日奖励已达上限)")
                        }
                        updatePetUi(petStateManager.getActivePet())
                        tvTip.text = "🎉 全部配对完成！"
                    }
                }, 260L)
            } else {
                // 不匹配：短暂亮出后扣回
                cards[index].postDelayed({
                    for (j in intArrayOf(a, index)) {
                        cards[j].animate().rotationX(90f).setDuration(120).withEndAction {
                            cards[j].text = "❓"
                            cards[j].setTextColor(Color.parseColor("#ECEFF1"))
                            cards[j].setBackgroundColor(cardBack[j])
                            cards[j].animate().rotationX(0f).setDuration(120).start()
                        }.start()
                    }
                    lockBoard = false
                }, 650L)
            }
        }

        for (i in deck.indices) {
            val card = TextView(this).apply {
                text = "❓"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#ECEFF1"))
                setBackgroundColor(cardBack[i])
                val m = 10
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setMargins(m, m, m, m)
                }
                setOnClickListener { onCardTap(i) }
            }
            cards.add(card)
            grid.addView(card)
        }
        root.addView(grid)
        root.addView(tvTip)
        dialog.show()
    }

    /** 幸运转盘：弹簧按钮启动，转盘 Decelerate 长缓动落针，中奖金币音+礼花。 */
    private fun playSpinWheelGame() {
        val (dialog, root) = buildSheet("🎡 幸运转盘", "一转定金币，每日前8局有奖")
        val wheel = com.inklink.host.ui.view.SpinWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                (resources.displayMetrics.widthPixels * 0.72f).toInt()
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(wheel)
        val btnSpin = MaterialButton(this).apply {
            text = "🎯 开始转动！"
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 20, 8, 8) }
            setOnClickListener {
                HapticUtil.tap(this)
                isEnabled = false
                soundEffectManager.play(SoundEffectManager.Sfx.PLAY)
                val (coin, exp) = petMiniGameManager.spinWheel()
                val segment = when (coin) {
                    10 -> 0; 30 -> 1; 60 -> 2; else -> 3
                }
                wheel.spinTo(segment) {
                    isEnabled = true
                    val rewardable = petMiniGameManager.canGainRewardToday()
                    if (rewardable) {
                        petStateManager.addReward(coin = coin, exp = exp)
                        petMiniGameManager.recordGamePlayed()
                    }
                    soundEffectManager.play(if (coin >= 60) SoundEffectManager.Sfx.LEVEL_UP else SoundEffectManager.Sfx.COIN)
                    if (coin >= 60) {
                        playHeartParticles()
                        playStarParticles()
                        petSpriteView.fireTrigger("EXCITED")
                    }
                    floatText("+$coin 🪙 +$exp EXP")
                    localTtsManager.speak(if (rewardable) "转盘开奖，获得${coin}金币，${exp}经验" else "今天的小游戏奖励领完啦")
                    PixelToast.show(
                        rootContainer,
                        if (rewardable) "转盘开奖！获得 $coin 金币与 $exp 经验"
                        else "转盘抽中 $coin 金币 (今日奖励已达上限)"
                    )
                    updatePetUi(petStateManager.getActivePet())
                }
            }
        }
        root.addView(btnSpin)
        bounceIn(btnSpin)
        dialog.show()
    }

    /** 通用底部卡片弹层容器（规范：弹窗卡片化，参考 Linguin 商品网格）。 */
    private fun buildSheet(title: String, subtitle: String? = null): Pair<BottomSheetDialog, LinearLayout> {
        val dialog = BottomSheetDialog(this)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 48)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = MaterialButton(this).apply {
            text = "关闭"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(tvTitle)
        header.addView(btnClose)
        root.addView(header)
        if (subtitle != null) {
            root.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 4, 0, 16)
            })
        }
        scroll.addView(root)
        dialog.setContentView(scroll)
        return Pair(dialog, root)
    }

    /** 商品/宠物卡片：emoji 图标 + 标题 + 描述 + 操作按钮。 */
    private fun buildGridCard(emoji: String, title: String, desc: String, action: String, onClick: () -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 18f
            cardElevation = 3f
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 8, 8, 8)
            }
            setPadding(0, 0, 0, 0)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 20)
        }
        inner.addView(TextView(this).apply {
            text = emoji
            textSize = 34f
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = title
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#212121"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 2)
        })
        inner.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8D6E63"))
            setPadding(0, 0, 0, 12)
        })
        val btn = MaterialButton(this).apply {
            text = action
            textSize = 13f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        inner.addView(btn)
        card.addView(inner)
        return card
    }

    private fun rowOf(vararg cards: MaterialCardView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            cards.forEach { addView(it) }
        }

    private fun showTaskSheet() {
        val tasks = taskQueueManager.getTasks()
        if (tasks.isEmpty()) {
            PixelToast.show(rootContainer, "暂无远程任务")
            return
        }
        val (dialog, root) = buildSheet("📋 远程语音任务", "点击任务回放家长语音留言")
        for (task in tasks) {
            val status = if (task.isPlayed) "已播放" else "新任务"
            val statusColor = if (task.isPlayed) "#9E9E9E" else "#E65100"
            val card = MaterialCardView(this).apply {
                radius = 16f
                cardElevation = 2f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 10, 0, 0) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 20, 24)
            }
            row.addView(TextView(this).apply {
                text = "🎙️"
                textSize = 24f
                setPadding(0, 0, 20, 0)
            })
            row.addView(TextView(this).apply {
                text = task.content
                textSize = 14f
                setTextColor(Color.parseColor("#212121"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = status
                textSize = 11f
                setTextColor(Color.parseColor(statusColor))
                setPadding(12, 4, 12, 4)
            })
            val playBtn = MaterialButton(this).apply {
                text = if (task.isPlayed) "重播" else "播放"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    taskQueueManager.markPlayed(task.taskId)
                    localTtsManager.speak(task.content, maxLen = com.inklink.common.protocol.payload.SoundProtocol.TASK_TTS_MAX_LEN)
                    PixelToast.show(rootContainer, "正在语音朗读任务...")
                    dialog.dismiss()
                }
            }
            row.addView(playBtn)
            card.addView(row)
            root.addView(card)
        }
        dialog.show()
    }

    private fun showBagSheet() {
        val bag = petStateManager.petBag
        val (dialog, root) = buildSheet("🎒 背包", "🪙 ${bag.coin} 金币 · 点卡片切换出战 · 长按改名")

        // --- 伙伴列表 ---
        root.addView(sectionTitle("🐾 伙伴"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buffer = ArrayList<MaterialCardView>()
        for (pet in bag.petList) {
            val isActive = pet.petId == bag.activePetId
            val stage = com.inklink.host.state.PetMoodMapper.stageLabel(pet.lifeStage)
            val desc = "$stage Lv.${pet.level}｜🍖${pet.hunger} 💖${pet.happiness}"
            val card = buildGridCard(
                emoji = typeEmoji(pet.petType) + if (isActive) " ⭐" else "",
                title = pet.name,
                desc = desc,
                action = if (isActive) "出战中" else "切换"
            ) {
                if (!isActive) {
                    petStateManager.switchActivePet(pet.petId)
                    updatePetUi(petStateManager.getActivePet())
                    notifyPetSwitched(pet.petId)
                    PixelToast.show(rootContainer, "已切换出战伙伴: ${pet.name}")
                    localTtsManager.speak("切换出战伙伴：${pet.name}")
                    dialog.dismiss()
                }
            }
            card.setOnLongClickListener { showRenameDialog(pet); true }
            buffer.add(card)
            if (buffer.size == 2) {
                grid.addView(rowOf(buffer[0], buffer[1]))
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) grid.addView(rowOf(*buffer.toTypedArray()))
        root.addView(grid)

        // --- 道具库存 ---
        root.addView(sectionTitle("🧰 道具（点击使用）"))
        val itemGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val itemBuf = ArrayList<MaterialCardView>()
        for ((id, count) in bag.itemStock) {
            if (count <= 0) continue
            val def = PetCatalog.ITEMS[id] ?: continue
            itemBuf.add(buildGridCard(def.emoji, def.name, "x$count｜${def.desc}", "使用") {
                val (effect, deny) = petStateManager.useItem(id)
                PixelToast.show(rootContainer, deny ?: effect)
                localTtsManager.speak(deny ?: effect)
                updatePetUi(petStateManager.getActivePet())
                dialog.dismiss()
                showBagSheet()
            })
            if (itemBuf.size == 2) {
                itemGrid.addView(rowOf(*itemBuf.toTypedArray()))
                itemBuf.clear()
            }
        }
        if (itemBuf.isNotEmpty()) itemGrid.addView(rowOf(*itemBuf.toTypedArray()))
        if (itemGrid.childCount == 0) root.addView(emptyHint("道具空空如也，去商店逛逛吧"))
        else root.addView(itemGrid)

        // --- 装饰 ---
        if (bag.unlockedDecorations.isNotEmpty()) {
            root.addView(sectionTitle("🎀 装饰（头饰槽）"))
            val decoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val active = petStateManager.getActivePet()
            decoRow.addView(buildGridCard("🚫", "卸下", "恢复原貌", if (active.decoId.isEmpty()) "佩戴中" else "卸下") {
                petStateManager.equipDecoration("")
                updatePetUi(petStateManager.getActivePet())
                localTtsManager.speak("已卸下装饰")
                dialog.dismiss(); showBagSheet()
            })
            for (decoId in bag.unlockedDecorations) {
                val def = PetCatalog.decoOf(decoId) ?: continue
                val equipped = active.decoId == decoId
                decoRow.addView(buildGridCard(def.emoji, def.name, "家长赠送的装扮", if (equipped) "佩戴中" else "戴上") {
                    if (!equipped) {
                        val (_, msg) = petStateManager.equipDecoration(decoId)
                        PixelToast.show(rootContainer, msg)
                        localTtsManager.speak(msg)
                        updatePetUi(petStateManager.getActivePet())
                        dialog.dismiss(); showBagSheet()
                    }
                })
            }
            root.addView(decoRow)
        }

        // --- 场景 ---
        root.addView(sectionTitle("🏠 房间场景"))
        val sceneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (def in PetCatalog.SCENES) {
            val cur = petStateManager.getActivePet().sceneId == def.id
            sceneRow.addView(buildGridCard(def.emoji, def.name, "Lv.${def.unlockLevel} 解锁", if (cur) "入住中" else "搬去") {
                val (ok, msg) = petStateManager.switchScene(def.id)
                PixelToast.show(rootContainer, msg)
                localTtsManager.speak(msg)
                if (ok) updatePetUi(petStateManager.getActivePet())
                dialog.dismiss(); showBagSheet()
            })
        }
        root.addView(sceneRow)

        // --- 今日目标 ---
        val dp = petStateManager.dailyGoalProgress()
        root.addView(sectionTitle("📅 今日目标（达成可领 ${PetCatalog.DAILY_REWARD_COIN} 金币）"))
        root.addView(goalRow("🍙 投喂", dp.feed, PetCatalog.DAILY_FEED_GOAL))
        root.addView(goalRow("🎾 玩耍", dp.play, PetCatalog.DAILY_PLAY_GOAL))
        root.addView(goalRow("🫧 清洁", dp.clean, PetCatalog.DAILY_CLEAN_GOAL))
        if (dp.claimed) {
            root.addView(emptyHint("今日奖励已领取，明天再来～"))
        } else if (petStateManager.canClaimDailyGoal()) {
            root.addView(MaterialButton(this).apply {
                text = "🎁 领取奖励"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 12, 8, 0) }
                setOnClickListener {
                    val (ok, msg) = petStateManager.claimDailyReward()
                    PixelToast.show(rootContainer, msg)
                    if (ok) {
                        playStarParticles()
                        floatText("+${PetCatalog.DAILY_REWARD_COIN} 🪙")
                    }
                    updatePetUi(petStateManager.getActivePet())
                    dialog.dismiss()
                }
            })
        }

        val visitBtn = MaterialButton(this).apply {
            text = "🐾 带TA去串门"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 20, 8, 0) }
            setOnClickListener { dialog.dismiss(); localBagInteract() }
        }
        root.addView(visitBtn)
        dialog.show()
    }

    /** 宠物改名弹层（裁决 #7：≤8 字符）。 */
    private fun showRenameDialog(pet: PetItem) {
        val input = TextInputEditText(this).apply {
            hint = "新名字（最多 8 个字）"
            setText(pet.name)
            setSelection(pet.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("给 ${pet.name} 起个新名字")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                if (petStateManager.renamePet(pet.petId, input.text.toString())) {
                    PixelToast.show(rootContainer, "改名成功！")
                    updatePetUi(petStateManager.getActivePet())
                } else {
                    PixelToast.show(rootContainer, "名字不能为空哦")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#212121"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 28, 0, 8)
    }

    private fun emptyHint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#9E9E9E"))
        setPadding(0, 8, 0, 8)
    }

    // ---------------- 商店：道具 / 宠物蛋 / 装饰 ----------------

    private fun showShopSheet() {
        val bag = petStateManager.petBag
        val (dialog, root) = buildSheet("🛒 宠物商店", "🪙 ${bag.coin} 金币 · 一切行为消耗化")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("🍙 道具", "🥚 宠物蛋", "🎀 装饰").forEachIndexed { index, label ->
            tabRow.addView(MaterialButton(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { renderShopInto(body, index, dialog) }
            })
        }
        root.addView(tabRow)
        root.addView(body)
        renderShopInto(body, 0, dialog)
        dialog.show()
    }

    /** 商店页签渲染：0=道具 1=宠物蛋 2=装饰（装饰仅控制端赠送，本地只展示）。 */
    private fun renderShopInto(body: LinearLayout, tab: Int, dialog: BottomSheetDialog) {
        body.removeAllViews()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buffer = ArrayList<MaterialCardView>()
        when (tab) {
            1 -> for (def in PetCatalog.SPECIES.filter { it.eggPrice > 0 }) {
                buffer.pushCard(grid, buildGridCard("🥚" + def.emoji, "${def.name}蛋", "🪙 ${def.eggPrice}", "购买") {
                    buyEgg(def.code, def.eggPrice)
                    dialog.dismiss()
                    showShopSheet()
                })
            }
            2 -> for (def in PetCatalog.DECOS) {
                buffer.pushCard(grid, buildGridCard(def.emoji, def.name, "🪙 ${def.price}｜家长赠送解锁", "了解") {
                    val msg = "装饰礼包由家长通过控制端「送礼物」赠送"
                    PixelToast.show(rootContainer, msg)
                    localTtsManager.speak(msg)
                })
            }
            else -> for (def in PetCatalog.ITEMS.values) {
                val stock = petStateManager.stockOf(def.id)
                buffer.pushCard(grid, buildGridCard(def.emoji, def.name, "🪙 ${def.price}｜${def.desc}｜库存x$stock", "购买 x1") {
                    buyItem(def.id, def.price)
                    dialog.dismiss()
                    showShopSheet()
                })
            }
        }
        if (buffer.isNotEmpty()) grid.addView(rowOf(*buffer.toTypedArray()))
        body.addView(grid)
    }

    /** 两列卡片自动成行。 */
    private fun ArrayList<MaterialCardView>.pushCard(grid: LinearLayout, card: MaterialCardView) {
        add(card)
        if (size == 2) {
            grid.addView(rowOf(this[0], this[1]))
            clear()
        }
    }

    /** 购买消耗道具（无库存上限，鼓励囤积）。 */
    private fun buyItem(itemId: String, price: Int) {
        val bag = petStateManager.petBag
        if (bag.coin < price) {
            PixelToast.show(rootContainer, "金币不足！需要 $price 金币")
            localTtsManager.speak("金币不足，还差${price - bag.coin}个金币")
            notifyShopBuy(itemId = itemId, itemType = "ITEM", success = false, newCoin = bag.coin)
            return
        }
        bag.coin -= price
        petStateManager.addItem(itemId, 1)
        val name = PetCatalog.ITEMS[itemId]?.name ?: itemId
        PixelToast.show(rootContainer, "购买$name x1（库存 ${petStateManager.stockOf(itemId)}）")
        localTtsManager.speak("购买成功，${name}加一")
        notifyShopBuy(itemId = itemId, itemType = "ITEM", success = true, newCoin = bag.coin)
        updatePetUi(petStateManager.getActivePet())
    }

    /** 购买宠物蛋。 */
    private fun buyEgg(type: String, price: Int) {
        val bag = petStateManager.petBag
        if (bag.coin < price) {
            PixelToast.show(rootContainer, "金币不足！需要 $price 金币")
            localTtsManager.speak("金币不足，还差${price - bag.coin}个金币")
            notifyShopBuy(itemId = type, itemType = "PET", success = false, newCoin = bag.coin)
            return
        }
        val now = System.currentTimeMillis()
        bag.coin -= price
        val newPet = PetItem(
            petId = java.util.UUID.randomUUID().toString(),
            petType = type,
            name = typeName(type),
            hunger = 80,
            happiness = 80,
            clean = 90,
            energy = 100,
            health = 100,
            level = 1,
            lifeStage = "EGG",
            interactions = 0,
            lastUpdateTs = now,
            birthTs = now,
            lastCareTs = now
        )
        bag.petList.add(newPet)
        petStateManager.persist(force = true)
        PixelToast.show(rootContainer, "获得宠物蛋：${typeName(type)}！多互动就能孵化哦")
        localTtsManager.speak("获得${typeName(type)}宠物蛋，多互动就能孵化")
        notifyShopBuy(itemId = type, itemType = "PET", success = true, newCoin = bag.coin)
        updatePetUi(petStateManager.getActivePet())
    }

    /** 物种 emoji（背包卡片展示），20 物种全覆盖。 */
    private fun typeEmoji(type: String): String = when (type) {
        "cat" -> "🐱"; "dog" -> "🐶"; "rabbit" -> "🐰"; "penguin" -> "🐧"; "hamster" -> "🐹"
        "panda" -> "🐼"; "fox" -> "🦊"; "dragon" -> "🐉"; "sheep" -> "🐑"; "hedgehog" -> "🦔"
        "bear" -> "🐻"; "tiger" -> "🐯"; "wolf" -> "🐺"; "turtle" -> "🐢"; "bird" -> "🐦"
        "mouse" -> "🐭"; "fish" -> "🐟"
        else -> "🐾"
    }

    /** 物种中文名（商店/背包/切换提示展示用），直接查 PetCatalog。 */
    private fun typeName(type: String): String = PetCatalog.speciesOf(type).name

    // ---------------- 事件日志 / 每日目标 / 里程碑 ----------------

    /** 成就面板：里程碑列表 + 每日目标入口。 */
    private fun showAchieveSheet() {
        val bag = petStateManager.petBag
        val (dialog, root) = buildSheet("🏆 成就", "里程碑 ${bag.milestones.size} 个 · 目标在背包页领取")
        val done = bag.milestones.keys.sorted()
        if (done.isEmpty()) root.addView(emptyHint("还没有成就，养大TA、解锁新场景吧"))
        for (id in done) {
            val label = when {
                id == "hatch" -> "🐣 初次破壳"
                id == "stage_juvenile" -> "🌱 长成幼年"
                id == "stage_adolescent" -> "🌿 长成青年"
                id == "stage_adult" -> "🌳 光荣成年"
                id.startsWith("scene_") -> "🏠 " + (PetCatalog.sceneOf(id.removePrefix("scene_"))?.name ?: id)
                id == "daily_first" -> "📅 首个每日目标"
                else -> "⭐ $id"
            }
            root.addView(TextView(this).apply {
                text = "✅ $label"
                textSize = 14f
                setTextColor(Color.parseColor("#2E7D32"))
                setPadding(0, 10, 0, 10)
            })
        }
        // 事件日志中里程碑行附带获得时间（近 500 条内检索）
        dialog.show()
    }

    /** 冒险日记：环形 500 条事件日志（Room event_log 表）。 */
    private fun showLogSheet() {
        val (dialog, root) = buildSheet("📔 冒险日记", "大事记与成长时间线")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("📜 大事记", "🌱 时间线").forEachIndexed { index, label ->
            tabRow.addView(MaterialButton(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { renderDiaryInto(body, index) }
            })
        }
        root.addView(tabRow)
        root.addView(body)
        renderDiaryInto(body, 0)
        dialog.show()
    }

    private fun renderDiaryInto(body: LinearLayout, tab: Int) {
        body.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        if (tab == 0) {
            val events = petStateManager.recentEvents(200)
            if (events.isEmpty()) body.addView(emptyHint("还没有记录，多陪陪TA吧"))
            val typeLabel = mapOf(
                "HATCH" to "🐣", "LEVEL_UP" to "⬆️", "FORM" to "✨", "RANDOM_GOOD" to "🍀",
                "RANDOM_BAD" to "🌧️", "VISIT" to "🐾", "GIFT" to "🎁", "MILESTONE" to "🏆",
                "DAILY" to "📅", "CARE" to "💗", "REVIVE" to "💊", "PASSED" to "🕯️"
            )
            for (e in events) {
                body.addView(TextView(this).apply {
                    text = "${typeLabel[e.type] ?: "•"} ${e.message}  ·  ${fmt.format(Date(e.ts))}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#424242"))
                    setPadding(0, 10, 0, 10)
                })
            }
        } else {
            val moments = petStateManager.listMoments()
            if (moments.isEmpty()) body.addView(emptyHint("还没有成长高光，一起创造回忆吧"))
            val typeLabel = mapOf(
                "HATCH" to "🐣", "LEVEL_UP" to "⬆️", "FORM" to "✨",
                "REVIVE" to "💊", "GIFT" to "🎁", "MILESTONE" to "🏆", "PASSED" to "🕯️"
            )
            for (mo in moments) {
                body.addView(TextView(this).apply {
                    text = "${typeLabel[mo.type] ?: "•"} ${mo.title}  ·  ${fmt.format(Date(mo.ts))}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#424242"))
                    setPadding(0, 10, 0, 10)
                })
            }
        }
    }

    /** 离世提示气泡：点击进入纪念册/重生。 */
    private fun showPassedBubble() {
        val bubble = findViewById<TextView?>(R.id.tvMoodBubble) ?: return
        bubble.text = "🕯️ TA去了宠物星球｜点此告别"
        bubble.visibility = View.VISIBLE
        bubble.setOnClickListener { HapticUtil.tap(it); showMemorialSheet() }
    }

    /** 纪念册：回看离世伙伴，并为离世中的当前宠物提供重生入口。 */
    private fun showMemorialSheet() {
        val (dialog, root) = buildSheet("🕯️ 纪念册", "感谢TA陪伴的每一天")
        val memorials = petStateManager.listMemorials()
        if (memorials.isEmpty()) {
            root.addView(emptyHint("还没有离世的伙伴"))
        }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        for (mem in memorials) {
            root.addView(sectionTitle("${typeEmoji(mem.petType)} ${mem.name}"))
            root.addView(TextView(this).apply {
                text = "陪伴了 ${formatLifespan(mem.lifespanMs)} · ${mem.momentCount} 个高光时刻\n" +
                    "${dayFmt.format(Date(mem.birthTs))} ~ ${dayFmt.format(Date(mem.passedTs))}"
                textSize = 13f
                setTextColor(Color.parseColor("#616161"))
                setPadding(0, 0, 0, 12)
            })
        }
        if (petStateManager.getActivePet().passedAtTs > 0) {
            root.addView(MaterialButton(this).apply {
                text = "🌱 让新生命延续（重生）"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 20, 8, 0) }
                setOnClickListener {
                    petStateManager.rebirth()
                    PixelToast.show(rootContainer, "新伙伴诞生了！")
                    localTtsManager.speak("新的伙伴诞生了，这一次要好好陪伴TA")
                    playStarParticles()
                    petSpriteView.fireTrigger("EXCITED")
                    updatePetUi(petStateManager.getActivePet())
                    dialog.dismiss()
                }
            })
        }
        dialog.show()
    }

    /** 毫秒时长 → "X天X小时"（不足 1 天只显示小时，不足 1 小时显示分钟）。 */
    private fun formatLifespan(ms: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(ms)
        val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes}分"
            else -> "${minutes}分钟"
        }
    }

    /** 每日目标面板。 */
    private fun showDailyGoalSheet() {
        val p = petStateManager.dailyGoalProgress()
        val (dialog, root) = buildSheet("📅 今日目标", "全部达成可领 ${PetCatalog.DAILY_REWARD_COIN} 金币")
        root.addView(goalRow("🍙 投喂", p.feed, PetCatalog.DAILY_FEED_GOAL))
        root.addView(goalRow("🎾 玩耍", p.play, PetCatalog.DAILY_PLAY_GOAL))
        root.addView(goalRow("🫧 清洁", p.clean, PetCatalog.DAILY_CLEAN_GOAL))
        if (p.claimed) {
            root.addView(emptyHint("今日奖励已领取，明天再来～"))
        } else if (petStateManager.canClaimDailyGoal()) {
            val claimBtn = MaterialButton(this).apply {
                text = "🎁 领取 ${PetCatalog.DAILY_REWARD_COIN} 金币"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 20, 8, 0) }
                setOnClickListener {
                    val (ok, msg) = petStateManager.claimDailyReward()
                    PixelToast.show(rootContainer, msg)
                    localTtsManager.speak(msg)
                    if (ok) {
                        playStarParticles()
                        floatText("+${PetCatalog.DAILY_REWARD_COIN} 🪙")
                    }
                    updatePetUi(petStateManager.getActivePet())
                    dialog.dismiss()
                }
            }
            root.addView(claimBtn)
        } else {
            root.addView(emptyHint("目标还未全部达成，继续加油！"))
        }
        dialog.show()
    }

    private fun goalRow(label: String, done: Int, goal: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 14)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = if (done >= goal) "✅ $done/$goal" else "$done/$goal"
            textSize = 14f
            setTextColor(if (done >= goal) Color.parseColor("#43A047") else Color.parseColor("#FB8C00"))
        })
        return row
    }

    // ---------------- 分钟级循环：随机事件 / 每日目标 / 里程碑 ----------------

    private fun startMinuteTicker() {
        minuteTickerJob?.cancel()
        minuteTickerJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000L)
                if (!PetClock.foreground) continue
                runCatching {
                    // 随机事件（内部 5min 冷却）
                    petStateManager.rollRandomEvent()?.let { msg ->
                        showAiBubble(msg)
                        playStarParticles()
                        updatePetUi(petStateManager.getActivePet())
                    }
                    // 每日目标达成未领取提示（每天一次）
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                    if (todayStr != lastDailyNoticeDate && petStateManager.canClaimDailyGoal()) {
                        lastDailyNoticeDate = todayStr
                        PixelToast.showLong(rootContainer, "📅 今日目标达成！打开背包即可领取奖励")
                        petSpriteView.fireTrigger("EXCITED")
                    }
                    // 新里程碑出现
                    val mc = petStateManager.milestoneCount()
                    if (lastMilestoneShown in 1 until mc) {
                        playHeartParticles()
                        floatText("🏆 新里程碑！")
                    }
                    lastMilestoneShown = mc
                }
            }
        }
    }

    /** AI 情绪台词气泡（复用 tvMoodBubble 通道，3s 自动收起）。 */
    private fun showAiBubble(line: String) {
        val bubble = findViewById<TextView?>(R.id.tvMoodBubble) ?: return
        bubble.text = line
        bubble.visibility = View.VISIBLE
        bubble.setOnClickListener(null)
        bubble.removeCallbacks(hideBubbleTask)
        bubble.postDelayed(hideBubbleTask, 3_000L)
    }

    private val hideBubbleTask = Runnable {
        findViewById<TextView?>(R.id.tvMoodBubble)?.visibility = View.GONE
    }

    /** 本地串门（阶段四）：活跃宠物与随机一只伙伴玩耍，心情+3，并上报 PET_BAG_INTERACT(43)。 */
    private fun localBagInteract() {
        val bag = petStateManager.petBag
        val others = bag.petList.filter { it.petId != bag.activePetId }
        if (others.isEmpty()) {
            PixelToast.show(rootContainer, "还没有其他宠物可以串门，去商店解锁新伙伴吧")
            return
        }
        val target = others.random()
        val (_, deny) = petStateManager.touchPet()
        if (deny != null) {
            PixelToast.show(rootContainer, deny)
            return
        }
        playHeartParticles()
        petSpriteView.startVisit(target.petType) // 访客伙伴滑入碰头
        petStateManager.logEvent("VISIT", "去${target.name}家串门了")
        updatePetUi(petStateManager.getActivePet())
        PixelToast.show(rootContainer, "🐾 和 ${target.name} 玩耍了一会，心情变好了")
        runCatching {
            val app = application as com.inklink.host.InkHostApplication
            val payload = com.inklink.common.protocol.payload.PetBagInteractPayload(
                targetPetId = target.petId,
                subType = "LOCAL_VISIT",
                visitorName = petStateManager.getActivePet().name,
                visitorPetType = petStateManager.getActivePet().petType
            )
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage(
                    type = com.inklink.common.protocol.MessageType.PET_BAG_INTERACT.code,
                    fromDeviceId = app.deviceId,
                    targetDeviceId = app.transportManager.defaultTargetDeviceId,
                    payload = com.google.gson.Gson().toJson(payload)
                )
            )
        }
    }

    /** 受控端给主控端宠物送三类礼物（44 giftType=item/deco/buff/coin，仅受控→主控）。 */
    private fun showRemoteGiftDialog() {
        val options = arrayOf(
            "🎁 给家长寄个感谢礼包（金币）",
            "📞 打电话叫爸爸妈妈来看看TA"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("和爸爸妈妈互动")
            .setItems(options) { _, which ->
                val app = application as com.inklink.host.InkHostApplication
                when (which) {
                    0 -> {
                        // 回赠家长金币的同时，把零花钱折 5 金币给宠物（经济闭环）
                        petStateManager.addReward(coin = 5, exp = 0)
                        val payload = com.inklink.common.protocol.payload.PetRemoteGiftPayload(
                            itemId = "coin_5",
                            count = 1,
                            giftType = "coin"
                        )
                        app.transportManager.sendMessage(
                            com.inklink.common.protocol.InkMessage(
                                type = com.inklink.common.protocol.MessageType.PET_REMOTE_GIFT.code,
                                fromDeviceId = app.deviceId,
                                targetDeviceId = app.transportManager.defaultTargetDeviceId,
                                payload = com.google.gson.Gson().toJson(payload)
                            )
                        )
                        PixelToast.show(rootContainer, "感谢礼包已寄出！")
                        localTtsManager.speak("感谢礼包已经寄给爸爸妈妈啦")
                    }
                    1 -> {
                        val activePet = petStateManager.getActivePet()
                        val payload = com.inklink.common.protocol.payload.PetBagInteractPayload(
                            targetPetId = activePet.petId,
                            subType = "CALL_VISIT",
                            visitorName = activePet.name,
                            visitorPetType = activePet.petType
                        )
                        app.transportManager.sendMessage(
                            com.inklink.common.protocol.InkMessage(
                                type = com.inklink.common.protocol.MessageType.PET_BAG_INTERACT.code,
                                fromDeviceId = app.deviceId,
                                targetDeviceId = app.transportManager.defaultTargetDeviceId,
                                payload = com.google.gson.Gson().toJson(payload)
                            )
                        )
                        PixelToast.show(rootContainer, "已经告诉爸爸妈妈啦，等一下来看TA～")
                        localTtsManager.speak("已经告诉爸爸妈妈啦，他们等一下就会来看你")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPinUnlockDialog() {
        if (!pinSecurityManager.hasPin()) {
            // 首次设置 PIN
            val input = TextInputEditText(this)
            input.hint = "设置 4-6 位数字 PIN"
            MaterialAlertDialogBuilder(this)
                .setTitle("初次设置管控 PIN")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val pin = input.text.toString().trim()
                    if (pinSecurityManager.setPin(pin)) {
                        startActivity(Intent(this, HostActivity::class.java))
                    } else {
                        Toast.makeText(this, "PIN 格式无效", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (pinSecurityManager.isLocked()) {
            val remain = pinSecurityManager.getRemainingLockSeconds()
            Toast.makeText(this, "PIN 已锁定，请等待 ${remain} 秒后重试", Toast.LENGTH_LONG).show()
            return
        }

        val input = TextInputEditText(this)
        input.hint = "输入 PIN 码"
        MaterialAlertDialogBuilder(this)
            .setTitle("身份验证")
            .setView(input)
            .setPositiveButton("验证") { _, _ ->
                val pin = input.text.toString().trim()
                if (pinSecurityManager.verifyPin(pin)) {
                    startActivity(Intent(this, HostActivity::class.java))
                } else {
                    HapticUtil.heavyClick(this)
                    val remain = pinSecurityManager.getRemainingLockSeconds()
                    if (remain > 0) {
                        Toast.makeText(this, "连续错误过多，锁定 ${remain} 秒", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "PIN 错误", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun observeServiceEvents() {
        lifecycleScope.launch {
            InkForegroundService.petEventFlow.collectLatest { event ->
                when (event) {
                    is InkForegroundService.PetUiEvent.FeedRemote -> {
                        // Service 已完成 feed() 结算，UI 仅播放反馈（避免双重投喂）
                        flyFoodToPet("🍖")
                        petSpriteView.fireTrigger("FEED")
                        playHeartParticles()
                        PixelToast.show(rootContainer, "爸爸妈妈远程投喂了 x${event.count}")
                        updatePetUi(petStateManager.getActivePet())
                    }
                    is InkForegroundService.PetUiEvent.RemoteInteractDone -> {
                        if (event.success) {
                            when (event.action) {
                                "PLAY" -> { playStarParticles(); flyFoodToPet("🎾") }
                                "CLEAN" -> { playBubbleParticles(); flyFoodToPet("🫧") }
                                "LEARN" -> flyFoodToPet("📖")
                                "SLEEP" -> flyFoodToPet("😴")
                                "HEAL" -> flyFoodToPet("💊")
                            }
                            petSpriteView.fireTrigger(event.trigger)
                        }
                        val emojiName = when (event.action) {
                            "PLAY" -> "陪TA玩耍"
                            "CLEAN" -> "给TA清洁"
                            "LEARN" -> "陪TA学习"
                            "SLEEP" -> "哄TA睡觉"
                            "HEAL" -> "为TA治疗"
                            else -> "与TA互动"
                        }
                        PixelToast.show(
                            rootContainer,
                            "爸爸妈妈$emojiName${event.note?.let { "（$it）" } ?: ""}"
                        )
                        updatePetUi(petStateManager.getActivePet())
                    }
                    is InkForegroundService.PetUiEvent.NewRemoteTask -> {
                        localTtsManager.speak("收到新任务：${event.task.content}", maxLen = com.inklink.common.protocol.payload.SoundProtocol.TASK_TTS_MAX_LEN)
                        petSpriteView.fireTrigger("EXCITED")
                    }
                    is InkForegroundService.PetUiEvent.GiftReceived -> {
                        localTtsManager.speak("收到家长远程赠送的礼包！")
                        // 10.2：部件动画(EXCITED) + 飞入 + 礼物音效 + 像素气泡 四位一体
                        petSpriteView.fireTrigger("EXCITED")
                        playHeartParticles()
                        flyFoodToPet("🎁")
                        soundEffectManager.play(
                            if (event.giftType == "coin") SoundEffectManager.Sfx.COIN else SoundEffectManager.Sfx.GIFT
                        )
                        val label = when (event.giftType) {
                            "buff" -> "能量增益"
                            "deco" -> "新装饰"
                            "coin" -> "金币"
                            else -> PetCatalog.ITEMS[event.itemId]?.name ?: "道具 x${event.count}"
                        }
                        PixelToast.showLong(rootContainer, "🎁 收到${event.note ?: label}")
                        updatePetUi(petStateManager.getActivePet())
                    }
                    is InkForegroundService.PetUiEvent.GameInviteReceived -> {
                        showRemoteGameInviteDialog(event.inviteId, event.fromDeviceId)
                    }
                    is InkForegroundService.PetUiEvent.GameActionReceived -> {
                        // 动作同步由对战弹窗或状态机处理
                    }
                    is InkForegroundService.PetUiEvent.GameResult -> {
                        val title = when (event.verdict) {
                            "WIN" -> "🎉 你赢了！"
                            "LOSE" -> "😆 对方赢了！"
                            else -> "🤝 平局！"
                        }
                        if (event.verdict == "WIN") playHeartParticles()
                        MaterialAlertDialogBuilder(this@PetMainActivity)
                            .setTitle(title)
                            .setMessage("你的出招: ${event.myChoice}\n对方的出招: ${event.opponentAction}")
                            .setPositiveButton("再来一局", null)
                            .show()
                    }
                    is InkForegroundService.PetUiEvent.FriendVisit -> {
                        localTtsManager.speak("${event.visitorName} 来串门啦！")
                        playHeartParticles()
                        if (event.visitorPetType.isNotBlank()) {
                            petSpriteView.startVisit(event.visitorPetType)
                        }
                        PixelToast.showLong(rootContainer, "🐾 ${event.visitorName} 来串门啦")
                        updatePetUi(petStateManager.getActivePet())
                    }
                    is InkForegroundService.PetUiEvent.TreasureReward -> {
                        localTtsManager.speak("移动探索发现宝藏！获得 ${event.coinGain} 金币")
                        playHeartParticles()
                        updatePetUi(petStateManager.getActivePet())
                    }
                    is InkForegroundService.PetUiEvent.AlertTriggered -> {
                        aiEngine?.isSuppressed = true
                        petSpriteView.fireTrigger("SCARED")
                        soundEffectManager.playAlarm {
                            HapticUtil.alarmImpact(this@PetMainActivity)
                        }
                    }
                    is InkForegroundService.PetUiEvent.AlertDismissed -> {
                        aiEngine?.isSuppressed = false
                        petSpriteView.fireTrigger("IDLE")
                    }
                }
            }
        }
    }

    private fun showRemoteGameInviteDialog(inviteId: String, fromDeviceId: String) {
        val choices = arrayOf("石头 ✊", "剪刀 ✌️", "布 ✋")
        MaterialAlertDialogBuilder(this)
            .setTitle("🎮 家长发来了远程猜拳对战！")
            .setItems(choices) { _, which ->
                val action = when (which) {
                    0 -> "ROCK"
                    1 -> "SCISSORS"
                    else -> "PAPER"
                }
                val app = application as com.inklink.host.InkHostApplication
                val payload = com.inklink.common.protocol.payload.PetGameActionPayload(
                    inviteId = inviteId,
                    actionData = action
                )
                app.transportManager.sendMessage(
                    com.inklink.common.protocol.InkMessage(
                        type = com.inklink.common.protocol.MessageType.PET_GAME_ACTION.code,
                        fromDeviceId = app.deviceId,
                        targetDeviceId = fromDeviceId,
                        payload = com.google.gson.Gson().toJson(payload)
                    )
                )
                PixelToast.show(rootContainer, "出招成功！等待家长揭晓")
            }
            .setNegativeButton("拒绝", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 外观模式可能已在管控页切换，回到宠物页时重新应用
        petSpriteView.setRenderMode(com.inklink.host.state.PetRenderMode.get(this))
        // 双时钟：后台段按 ÷20+地板 settle 后，才允许翻转为前台全速
        PetClock.foreground = true
        updatePetUi(petStateManager.recalculateState())
        aiEngine?.start()
        startMinuteTicker()
        // 离线期间里程碑可能变化，重置基线避免回到界面误报庆祝
        lastMilestoneShown = petStateManager.milestoneCount()
        // 权限授予后返回时，重新触发前台服务以升级 foregroundServiceType（幂等）
        runCatching {
            startService(Intent(this, InkForegroundService::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        // 混叠区间必须按"离开前台之前"的倍率结算（裁决 #1）
        runCatching { petStateManager.recalculateState() }
        PetClock.foreground = false
        minuteTickerJob?.cancel()
        minuteTickerJob = null
        aiEngine?.stop()
        localTtsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        minuteTickerJob?.cancel()
        aiEngine?.stop()
        // 不 release：门控是进程级单例，服务与广播接收器共用（Activity 划掉不得影响后台播报）
        soundEffectManager.release()
        runCatching { petStateManager.flush() }
    }
}
