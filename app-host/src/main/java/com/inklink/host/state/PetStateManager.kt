package com.inklink.host.state

import android.content.Context
import com.google.gson.Gson
import com.inklink.common.protocol.payload.Memorial
import com.inklink.common.protocol.payload.Moment
import com.inklink.common.protocol.payload.PetBag
import com.inklink.common.protocol.payload.PetItem
import com.inklink.host.data.EventLogEntry
import com.inklink.host.data.PetBagRow
import com.inklink.host.data.PetDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * 宠物状态机与数据持久化管理器（Service / UI / Receiver 唯一业务计算源）。
 *
 * V1.1（阶段八）升级：
 *  - 持久化从 pet_bag.json 迁入 **Room**（整包 JSON 行 + 事件日志表）；首启自动导入旧档，
 *    pillCount 并入 itemStock["potion_heal"]。
 *  - 双时钟衰减（[PetDecayEngine]/[PetClock]）：前台全速、后台÷20+地板30、Buff 暂停豁免。
 *  - 工程纪律：内存秒级运算，**10s 节流落盘**；事件日志**环形 500 条**自动裁剪。
 *  - 行为消耗化：六动作消费对应道具；新增睡眠药水/装饰/场景/Buff/每日目标/里程碑/随机事件。
 *
 * 进程级共享：所有实例读写同一 [cachedBag] 与同一 Room 单例，Service/UI/Receiver 不互相覆盖。
 */
class PetStateManager(context: Context) {

    private val appContext = context.applicationContext
    private val dao by lazy { PetDatabase.get(appContext).petDao() }

    companion object {
        private val gson = Gson()
        private val lock = Any()

        /** 落盘节流间隔（V1.1 工程纪律：10-30s） */
        private const val PERSIST_THROTTLE_MS = 10_000L

        /** 随机事件最小间隔 */
        private const val EVENT_COOLDOWN_MS = 5 * 60_000L

        /** 成长时间线时刻上限（V1.2，防 JSON 单行膨胀） */
        private const val MAX_MOMENTS = 2000

        /** 离世宽限期：虚弱沉睡超过此时长未治疗即离世（V1.2） */
        private const val LIFE_GRACE_MS = 24 * 60 * 60_000L

        /** 沉淀为「时刻」的高光事件类型集合 */
        private val HIGHLIGHT_TYPES =
            setOf("HATCH", "LEVEL_UP", "FORM", "REVIVE", "GIFT", "MILESTONE", "PASSED")

        @Volatile
        private var cachedBag: PetBag? = null
        private var lastPersistTs = 0L
        private var pendingDirty = false
    }

    /** 全局共享的背包状态。 */
    val petBag: PetBag
        get() = synchronized(lock) { cachedBag ?: loadOrCreateLocked().also { cachedBag = it } }

    /** 强制从存储重载（调试/外部进程改写后用）。 */
    /** 单测专用：清空 Room 两张表与内存缓存，保证用例间确定性。 */
    @androidx.annotation.VisibleForTesting
    fun wipeForTest() {
        synchronized(lock) {
            dao.clearBag()
            dao.clearLogs()
            cachedBag = null
            pendingDirty = false
            lastPersistTs = 0L
        }
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            cachedBag?.let { if (pendingDirty) writeLocked(it) }
            pendingDirty = false
            cachedBag = null
        }
    }

    // ================= 载入 / 迁移 =================

    private fun loadOrCreateLocked(): PetBag {
        dao.loadBagJson()?.let { json ->
            val parsed = runCatching { gson.fromJson(json, PetBag::class.java) }.getOrNull()
            if (parsed != null && parsed.petList.isNotEmpty()) {
                migrate(parsed)
                return parsed
            }
        }
        // Room 为空：尝试导入旧版 pet_bag.json（一次性迁移）
        val legacy = File(appContext.filesDir, "pet_bag.json")
        if (legacy.exists()) {
            val old = runCatching {
                gson.fromJson(legacy.readText(), PetBag::class.java)
            }.getOrNull()
            if (old != null && old.petList.isNotEmpty()) {
                migrate(old)
                writeLocked(old)
                runCatching { legacy.renameTo(File(legacy.parentFile, "pet_bag.json.bak")) }
                return old
            }
        }
        // 全新档：初始伙伴 + 新手道具包
        val firstPetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val initialPet = PetItem(
            petId = firstPetId,
            petType = "cat",
            name = "小可爱",
            hunger = 80,
            happiness = 80,
            clean = 90,
            energy = 100,
            health = 100,
            lastUpdateTs = now,
            birthTs = now,
            lastCareTs = now,
            lifeStage = "CUB"
        )
        val newBag = PetBag(
            activePetId = firstPetId,
            petList = mutableListOf(initialPet),
            coin = 1000,
            schemaVer = 2,
            unlockedScenes = mutableListOf("bedroom")
        )
        PetCatalog.STARTER_STOCK.forEach { (id, n) -> newBag.itemStock[id] = n }
        writeLocked(newBag)
        logEventLocked(initialPet.petId, "CARE", "新的冒险开始了！")
        return newBag
    }

    /**
     * 旧档迁移：Gson Unsafe 绕过 Kotlin 默认值——旧 JSON 的新增集合字段会是 null、
     * 数值字段 0。统一修补，并把 pillCount 并入 itemStock["potion_heal"]（裁决 #7）。
     */
    private fun migrate(bag: PetBag) {
        var dirty = false
        // Gson null 修补：声明非空但运行时可能为 null（Unsafe 绕过 Kotlin 默认值），
        // 用 healNull 判空后以 ?: 兜底替换，null 时才标脏落盘。
        bag.itemStock = healNull(bag.itemStock) ?: mutableMapOf<String, Int>().also { dirty = true }
        bag.unlockedDecorations = healNull(bag.unlockedDecorations) ?: mutableListOf<String>().also { dirty = true }
        bag.unlockedScenes = (healNull(bag.unlockedScenes) ?: mutableListOf("bedroom").also { dirty = true })
            .ifEmpty { mutableListOf("bedroom") }
        bag.milestones = healNull(bag.milestones) ?: mutableMapOf<String, Boolean>().also { dirty = true }
        bag.moments = healNull(bag.moments) ?: mutableListOf<Moment>().also { dirty = true }
        bag.memorials = healNull(bag.memorials) ?: mutableListOf<Memorial>().also { dirty = true }

        if (bag.schemaVer < 2) {
            // pillCount -> potion_heal 库存合并
            if (bag.pillCount > 0 && (bag.itemStock["potion_heal"] ?: 0) == 0) {
                bag.itemStock["potion_heal"] = bag.pillCount
            }
            bag.schemaVer = 2
            dirty = true
        }
        bag.petList.forEach { pet ->
            if (!pet.isAlive && pet.health <= 0 && pet.lifeStage == "EGG") {
                pet.isAlive = true; pet.health = 100; dirty = true   // 蛋不可能虚弱
            }
            if (pet.health < 0) { pet.health = 0; dirty = true }
            if (pet.health > 100) { pet.health = 100; dirty = true }
            if (pet.name.isNullOrEmpty()) { pet.name = "小可爱"; dirty = true }
            if (pet.petType.isNullOrEmpty()) { pet.petType = "cat"; dirty = true }
            if (pet.lifeStage.isNullOrEmpty()) { pet.lifeStage = lifeStageFor(pet.level); dirty = true }
            if (pet.birthTs <= 0) { pet.birthTs = System.currentTimeMillis(); dirty = true }
            if (pet.sceneId.isNullOrEmpty()) { pet.sceneId = "bedroom"; dirty = true }
            if (pet.playfulness < -100) { pet.playfulness = -100; dirty = true }
            if (pet.affection > 100) { pet.affection = 100; dirty = true }
        }
        if (dirty) writeLocked(bag)
    }

    /**
     * 将（可能被 Gson 置 null 的）非空声明字段经可空局部中转检测。
     * Gson 用 Unsafe 绕过 Kotlin 默认值，旧 JSON 缺失的集合字段运行时会真是 null；
     * 通过返回可空值让调用方判空替换，编译期的"恒非空短路"无法优化掉本次检测。
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun <T> healNull(field: T): T? = if (field == null) null else field

    // ================= 生命周期 =================

    /** 等级 -> 生命周期阶段（EGG 仅新购蛋；青年/成年按等级推进，破壳由互动累计触发） */
    fun lifeStageFor(level: Int): String = when {
        level >= 5 -> "ADULT"
        level >= 4 -> "ADOLESCENT"
        level >= 2 -> "JUVENILE"
        else -> "CUB"
    }

    fun getActivePet(): PetItem {
        synchronized(lock) {
            val bag = petBag
            return bag.petList.find { it.petId == bag.activePetId }
                ?: bag.petList.firstOrNull()
                ?: PetItem(petId = UUID.randomUUID().toString(), lifeStage = "CUB").also {
                    bag.petList.add(it)
                    bag.activePetId = it.petId
                    persist(force = true)
                }
        }
    }

    /** 宠物改名（背包长按，裁决 #7）。 */
    fun renamePet(petId: String, newName: String): Boolean {
        synchronized(lock) {
            val pet = petBag.petList.find { it.petId == petId } ?: return false
            val trimmed = newName.trim().take(8)
            if (trimmed.isEmpty()) return false
            if (pet.name == trimmed) return true
            pet.name = trimmed
            logEventLocked(petId, "CARE", "现在叫「$trimmed」啦")
            persist()
            return true
        }
    }

    // ================= 双时钟结算 =================

    /**
     * 时间增量结算（幂等，按 lastUpdateTs + 分属性余量）。
     * 睡眠：精力恢复、健康恢复、其余豁免；清醒：四向衰减；
     * hunger<20||clean<20：健康侵蚀；健康归零：虚弱沉睡（无死亡）。
     * 长期冷落（>1h 无照料）：性格每小时缓降（参考 GooseDroid onIgnored）。
     */
    fun recalculateState(): PetItem {
        synchronized(lock) {
            val pet = getActivePet()
            val now = System.currentTimeMillis()
            PetDecayEngine.settle(pet, now, PetClock.foreground)
            applyNeglectDrift(pet, now)
            checkLifeClosure(pet, now)
            if (now - pet.lastUpdateTs >= 0) persist()
            return pet
        }
    }

    /** 冷落性格漂移：每小时 affection-2 / playfulness-1，时间戳前进保持幂等。
     *  2026-08-31 修订：后台自动休眠期（非前台）不漂移性格——"不玩不掉状态"，
     *  但 lastCareTs 仍前进到 now，避免回到前台时一次性补扣历史冷落。 */
    private fun applyNeglectDrift(pet: PetItem, now: Long) {
        if (!pet.isAlive || pet.lastCareTs <= 0) return
        if (!PetClock.foreground) {
            pet.lastCareTs = now
            return
        }
        val hour = 3_600_000L
        var drift = 0
        while (now - pet.lastCareTs >= hour && drift < 24) {
            pet.lastCareTs += hour
            pet.affection = (pet.affection - 2).coerceIn(-100, 100)
            pet.playfulness = (pet.playfulness - 1).coerceIn(-100, 100)
            drift++
        }
    }

    // ================= 道具库存 =================

    fun stockOf(itemId: String): Int = synchronized(lock) { petBag.itemStock[itemId] ?: 0 }

    fun addItem(itemId: String, count: Int) {
        synchronized(lock) {
            petBag.itemStock[itemId] = stockOf(itemId) + count.coerceAtLeast(0)
            persist()
        }
    }

    private fun consumeItemLocked(bag: PetBag, itemId: String, count: Int): Boolean {
        val have = bag.itemStock[itemId] ?: 0
        if (have < count) return false
        bag.itemStock[itemId] = have - count
        return true
    }

    /** 通用使用道具（商店购买后主动使用：sleep_potion 等）。返回 (效果说明, 拒绝原因)。 */
    fun useItem(itemId: String): Pair<String, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            val def = PetCatalog.ITEMS[itemId] ?: return Pair("", "未知道具")
            if (!pet.isAlive && itemId != "potion_heal") return Pair("", "TA很虚弱，先治疗吧")
            if ((petBag.itemStock[itemId] ?: 0) < 1) return Pair("", defOutOfStock(itemId))
            when (itemId) {
                "sleep_potion" -> {
                    consumeLocked(itemId)
                    val before = pet.energy
                    pet.energy = (pet.energy + 60).coerceIn(0, 100)
                    logEventLocked(pet.petId, "CARE", "喝了睡眠药水，精神+${pet.energy - before}")
                    persist()
                    return Pair("精力 +${pet.energy - before}", null)
                }
                else -> return Pair("", "该道具通过对应按钮使用")
            }
        }
    }

    private fun consumeLocked(itemId: String, count: Int = 1): Boolean =
        consumeItemLocked(petBag, itemId, count)

    private fun defOutOfStock(itemId: String): String =
        "背包里没有${PetCatalog.ITEMS[itemId]?.name ?: itemId}了，去商店买一些吧"

    // ================= 互动动作（消耗化） =================

    /** 互动前置校验：返回 null 表示可互动，否则为拒绝原因。 */
    private fun checkDeny(pet: PetItem): String? {
        if (!pet.isAlive) return "TA已经虚弱得睁不开眼了，快用治疗药剂唤醒！"
        if (pet.isSleeping) return "TA正在睡觉，先唤醒TA吧"
        return null
    }

    /**
     * 喂食：消耗 [count] 份 [itemId] 食物。
     * 普通食物 饥饿+25/份；高级大餐 饥饿+50 开心+10/份。
     * 返回 (饥饿增量, 心情增量, 拒绝原因或null)。
     */
    fun feed(count: Int = 1, itemId: String = "food_normal"): Triple<Int, Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            checkDeny(pet)?.let { return Triple(0, 0, it) }
            if (itemId != "food_normal" && itemId != "food_premium") {
                return Triple(0, 0, "这不是食物哦")
            }
            val safeCount = count.coerceIn(1, 5)
            if (!consumeLocked(itemId, safeCount)) return Triple(0, 0, defOutOfStock(itemId))
            val oldHunger = pet.hunger
            val oldHappy = pet.happiness
            if (itemId == "food_normal") {
                pet.hunger = (pet.hunger + 25 * safeCount).coerceIn(0, 100)
                pet.happiness = (pet.happiness + 2 * safeCount).coerceIn(0, 100)
            } else {
                pet.hunger = (pet.hunger + 50 * safeCount).coerceIn(0, 100)
                pet.happiness = (pet.happiness + 10 * safeCount).coerceIn(0, 100)
            }
            pet.exp += 8 * safeCount
            pet.lastCareTs = System.currentTimeMillis()
            pet.affection = (pet.affection + 1 * safeCount).coerceIn(-100, 100)
            recordInteraction(pet)
            bumpDailyLocked("feed")
            checkLevelUp(pet)
            logEventLocked(pet.petId, "CARE", "美美地吃了${PetCatalog.ITEMS[itemId]?.name}（x$safeCount）")
            persist()
            return Triple(pet.hunger - oldHunger, pet.happiness - oldHappy, null)
        }
    }

    /** 抚摸互动（单击宠物）。返回 (心情增量, 拒绝原因或null)。 */
    fun touchPet(): Pair<Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            checkDeny(pet)?.let { return Pair(0, it) }
            val oldHappy = pet.happiness
            pet.happiness = (pet.happiness + 2).coerceIn(0, 100)
            pet.exp += 2
            pet.affection = (pet.affection + 1).coerceIn(-100, 100)
            pet.lastCareTs = System.currentTimeMillis()
            recordInteraction(pet)
            checkLevelUp(pet)
            persist()
            return Pair(pet.happiness - oldHappy, null)
        }
    }

    /** 长按抚摸（阶段八手势）：亲密与心情双升，单次上限内递减收益。返回 (心情增量, 拒绝原因)。 */
    fun longPressPet(): Pair<Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            checkDeny(pet)?.let { return Pair(0, it) }
            val oldHappy = pet.happiness
            pet.happiness = (pet.happiness + 4).coerceIn(0, 100)
            pet.affection = (pet.affection + 3).coerceIn(-100, 100)
            pet.exp += 3
            pet.lastCareTs = System.currentTimeMillis()
            recordInteraction(pet)
            checkLevelUp(pet)
            logEventLocked(pet.petId, "CARE", "被长按抚摸，开心地蹭了蹭")
            persist()
            return Pair(pet.happiness - oldHappy, null)
        }
    }

    /** 连续快速点击的烦躁结算：心情小幅下降。返回 (心情增量, 提示)。 */
    fun annoyPet(): Pair<Int, String> {
        synchronized(lock) {
            val pet = recalculateState()
            if (!pet.isAlive) return Pair(0, "TA虚弱得没有反应…")
            val oldHappy = pet.happiness
            pet.happiness = (pet.happiness - 3).coerceIn(0, 100)
            pet.affection = (pet.affection - 1).coerceIn(-100, 100)
            logEventLocked(pet.petId, "CARE", "被连续戳弄烦了，生气地甩甩头")
            persist()
            return Pair(pet.happiness - oldHappy, "别戳啦，TA有点烦！")
        }
    }

    /** 玩耍：消耗玩具，开心+30 精力-15。返回 (心情增量, 精力消耗, 拒绝原因)。 */
    fun playWith(): Triple<Int, Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            val deny = checkDeny(pet)
                ?: if (pet.energy < 15) "精力不足，先让TA睡一觉吧" else null
                ?: if ((petBag.itemStock["toy"] ?: 0) < 1) defOutOfStock("toy") else null
            if (deny != null) return Triple(0, 0, deny)
            consumeLocked("toy")
            val oldHappy = pet.happiness
            val oldEnergy = pet.energy
            pet.happiness = (pet.happiness + 30).coerceIn(0, 100)
            pet.energy = (pet.energy - 15).coerceIn(0, 100)
            pet.hunger = (pet.hunger - 3).coerceIn(0, 100)
            pet.exp += 10
            pet.playfulness = (pet.playfulness + 5).coerceIn(-100, 100)
            pet.playCount += 1
            pet.lastCareTs = System.currentTimeMillis()
            recordInteraction(pet)
            bumpDailyLocked("play")
            checkLevelUp(pet)
            logEventLocked(pet.petId, "CARE", "玩玩具玩得好开心！")
            persist()
            return Triple(pet.happiness - oldHappy, oldEnergy - pet.energy, null)
        }
    }

    /** 学习：消耗书本，成长+35 精力-20 开心-8。返回 (经验增量, 拒绝原因)。 */
    fun learn(): Pair<Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            val deny = checkDeny(pet)
                ?: if (pet.energy < 20) "精力不足，学不进去了" else null
                ?: if ((petBag.itemStock["book"] ?: 0) < 1) defOutOfStock("book") else null
            if (deny != null) return Pair(0, deny)
            consumeLocked("book")
            val oldExp = pet.exp
            pet.energy = (pet.energy - 20).coerceIn(0, 100)
            pet.happiness = (pet.happiness - 8).coerceIn(0, 100)
            pet.exp += 35
            pet.learnCount += 1
            pet.lastCareTs = System.currentTimeMillis()
            recordInteraction(pet)
            checkLevelUp(pet)
            logEventLocked(pet.petId, "CARE", "认真读完了故事书，成长+35")
            persist()
            return Pair(pet.exp - oldExp, null)
        }
    }

    /** 清洁：消耗沐浴露，清洁+45。返回 (清洁增量, 拒绝原因)。 */
    fun cleanPet(): Pair<Int, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            val deny = checkDeny(pet)
                ?: if ((petBag.itemStock["shower_gel"] ?: 0) < 1) defOutOfStock("shower_gel") else null
            if (deny != null) return Pair(0, deny)
            consumeLocked("shower_gel")
            val oldClean = pet.clean
            pet.clean = (pet.clean + 45).coerceIn(0, 100)
            pet.happiness = (pet.happiness + 2).coerceIn(0, 100)
            pet.exp += 5
            pet.lastCareTs = System.currentTimeMillis()
            recordInteraction(pet)
            bumpDailyLocked("clean")
            checkLevelUp(pet)
            logEventLocked(pet.petId, "CARE", "洗了香香的泡泡浴")
            persist()
            return Pair(pet.clean - oldClean, null)
        }
    }

    /** 睡觉/唤醒开关。返回 (是否入睡, 拒绝原因)。 */
    fun toggleSleep(): Pair<Boolean, String?> {
        synchronized(lock) {
            val pet = recalculateState()
            if (!pet.isAlive) return Pair(false, "TA已经虚弱得睁不开眼了，快用治疗药剂唤醒！")
            pet.isSleeping = !pet.isSleeping
            logEventLocked(pet.petId, "CARE", if (pet.isSleeping) "进入了甜甜的梦乡" else "睡醒啦，精神满满")
            persist()
            return Pair(pet.isSleeping, null)
        }
    }

    /** 使用治疗药剂：健康回满并唤醒。返回 (是否成功, 提示)。 */
    fun heal(): Pair<Boolean, String> {
        synchronized(lock) {
            val pet = recalculateState()
            if (pet.isAlive && pet.health >= 100) return Pair(false, "TA很健康，不需要治疗")
            if (pet.passedAtTs > 0) return Pair(false, "TA已经永远离开了，去纪念册看看，让新生命延续吧")
            if (!consumeLocked("potion_heal")) return Pair(false, "没有治疗药剂了，去商店买一瓶吧")
            val revived = !pet.isAlive
            pet.isAlive = true
            pet.health = 100
            pet.remHealth = 0.0
            pet.lastHealthZeroTs = 0L
            pet.isSleeping = false
            pet.happiness = (pet.happiness + 5).coerceIn(0, 100)
            logEventLocked(pet.petId, "REVIVE", if (revived) "喝下治疗药剂，重新睁开了眼睛！" else "治疗完成，生龙活虎")
            persist()
            return Pair(true, if (revived) "TA慢慢睁开了眼睛，重新活蹦乱跳了！" else "治疗完成，健康满格")
        }
    }

    // ================= Buff 赠送（远程，裁决 #2） =================

    /** 应用远程 Buff：即时改数值 + 对应属性 10 分钟暂停衰减。返回 (效果说明, 拒绝原因)。 */
    fun applyBuff(buffId: String, count: Int = 1): Pair<String, String?> {
        synchronized(lock) {
            val pet = getActivePet()
            val now = System.currentTimeMillis()
            val n = count.coerceIn(1, 3)
            return when (buffId) {
                PetCatalog.BUFF_ENERGY -> {
                    val before = pet.energy
                    pet.energy = (pet.energy + 40 * n).coerceIn(0, 100)
                    pet.energyPauseUntilTs = now + PetClock.BUFF_PAUSE_MS
                    logEventLocked(pet.petId, "GIFT", "收到精力能量包！精力+${pet.energy - before}，10分钟内不再下滑")
                    persist()
                    Pair("精力 +${pet.energy - before}", null)
                }
                PetCatalog.BUFF_MOOD -> {
                    val before = pet.happiness
                    pet.happiness = (pet.happiness + 30 * n).coerceIn(0, 100)
                    pet.moodPauseUntilTs = now + PetClock.BUFF_PAUSE_MS
                    logEventLocked(pet.petId, "GIFT", "收到快乐魔法包！开心+${pet.happiness - before}，10分钟内不再下滑")
                    persist()
                    Pair("开心 +${pet.happiness - before}", null)
                }
                else -> Pair("", "未知增益")
            }
        }
    }

    // ================= 装饰 / 场景 =================

    /** 解锁装饰（购买或赠送）。 */
    fun unlockDecoration(decoId: String) {
        synchronized(lock) {
            if (PetCatalog.decoOf(decoId) == null) return
            if (!petBag.unlockedDecorations.contains(decoId)) {
                petBag.unlockedDecorations.add(decoId)
                val pet = getActivePet()
                logEventLocked(pet.petId, "GIFT", "解锁了新装饰：${PetCatalog.decoOf(decoId)?.name}")
                persist()
            }
        }
    }

    /** 给活跃宠物佩戴装饰（须已解锁；decoId 传空=卸下）。返回 (是否成功, 提示)。 */
    fun equipDecoration(decoId: String): Pair<Boolean, String> {
        synchronized(lock) {
            val pet = getActivePet()
            if (decoId.isEmpty()) {
                pet.decoId = ""
                persist()
                return Pair(true, "已摘下装饰")
            }
            if (!petBag.unlockedDecorations.contains(decoId)) {
                return Pair(false, "还没有解锁这个装饰")
            }
            pet.decoId = decoId
            logEventLocked(pet.petId, "CARE", "戴上了${PetCatalog.decoOf(decoId)?.name}")
            persist()
            return Pair(true, "戴上啦！")
        }
    }

    /**
     * 一键赠送全部资源（主控端"赠送全部"，2026-08-31）：
     * 全部道具库存补足 + 两个 Buff + 全部装饰解锁 + 金币补到 1000。
     * 返回聚合提示。幂等，重复赠送只是补足。
     */
    fun applyGiftAll(): String {
        synchronized(lock) {
            val pet = recalculateState()
            val notes = mutableListOf<String>()

            // 道具：每个在售道具库存补到 10
            for (def in PetCatalog.ITEMS.values) {
                val have = petBag.itemStock[def.id] ?: 0
                if (have < 10) petBag.itemStock[def.id] = 10
            }

            // Buff：即时生效 + 暂停衰减
            applyBuff(PetCatalog.BUFF_ENERGY, 3)
            applyBuff(PetCatalog.BUFF_MOOD, 3)

            // 装饰：全部解锁并自动佩戴第一个
            for (deco in PetCatalog.DECOS) unlockDecoration(deco.id)
            if (pet.decoId.isNullOrEmpty() && PetCatalog.DECOS.isNotEmpty()) {
                pet.decoId = PetCatalog.DECOS.first().id
            }

            // 金币：补到 1000
            if (petBag.coin < 1000) petBag.coin = 1000

            logEventLocked(pet.petId, "GIFT", "家长一键赠送了全部资源！")
            persist()
            notes += "道具补足到10 · 精力/心情+满 · 装饰全解锁 · 金币补到1000"
            return notes.joinToString("\n")
        }
    }

    /** 切换像素场景（须已解锁）。返回 (是否成功, 提示)。 */
    fun switchScene(sceneId: String): Pair<Boolean, String> {        synchronized(lock) {
            val def = PetCatalog.sceneOf(sceneId) ?: return Pair(false, "未知场景")
            val pet = getActivePet()
            if (pet.level < def.unlockLevel) {
                return Pair(false, "Lv.${def.unlockLevel} 解锁「${def.name}」，继续成长吧")
            }
            if (!petBag.unlockedScenes.contains(sceneId)) {
                petBag.unlockedScenes.add(sceneId)
                grantMilestoneLocked("scene_$sceneId", 20, "解锁新场景：${def.name}")
            }
            pet.sceneId = sceneId
            logEventLocked(pet.petId, "MILESTONE", "搬进了${def.name}")
            persist()
            return Pair(true, "换到${def.name}啦")
        }
    }

    // ================= 每日目标 =================

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 推进每日目标计数（调用方需持锁）。跨日自动重置。 */
    private fun bumpDailyLocked(kind: String) {
        val bag = petBag
        val todayStr = today()
        if (bag.dailyGoalDate != todayStr) {
            bag.dailyGoalDate = todayStr
            bag.dailyGoalFeed = 0
            bag.dailyGoalPlay = 0
            bag.dailyGoalClean = 0
            bag.dailyGoalClaimed = false
        }
        when (kind) {
            "feed" -> bag.dailyGoalFeed += 1
            "play" -> bag.dailyGoalPlay += 1
            "clean" -> bag.dailyGoalClean += 1
        }
    }

    /** 每日目标进度：(feed, play, clean) + 是否已领取。 */
    data class DailyGoalProgress(val feed: Int, val play: Int, val clean: Int, val claimed: Boolean)

    fun dailyGoalProgress(): DailyGoalProgress {
        synchronized(lock) {
            val bag = petBag
            if (bag.dailyGoalDate != today()) {
                return DailyGoalProgress(0, 0, 0, false)
            }
            return DailyGoalProgress(bag.dailyGoalFeed, bag.dailyGoalPlay, bag.dailyGoalClean, bag.dailyGoalClaimed)
        }
    }

    fun canClaimDailyGoal(): Boolean {
        synchronized(lock) {
            val bag = petBag
            return bag.dailyGoalDate == today() && !bag.dailyGoalClaimed &&
                bag.dailyGoalFeed >= PetCatalog.DAILY_FEED_GOAL &&
                bag.dailyGoalPlay >= PetCatalog.DAILY_PLAY_GOAL &&
                bag.dailyGoalClean >= PetCatalog.DAILY_CLEAN_GOAL
        }
    }

    /** 领取每日目标奖励。返回 (是否成功, 提示)。 */
    fun claimDailyReward(): Pair<Boolean, String> {
        synchronized(lock) {
            if (!canClaimDailyGoal()) return Pair(false, "目标还没完成或已领取过了")
            val bag = petBag
            bag.dailyGoalClaimed = true
            bag.coin += PetCatalog.DAILY_REWARD_COIN
            logEventLocked(getActivePet().petId, "DAILY", "完成每日目标，奖励 ${PetCatalog.DAILY_REWARD_COIN} 金币！")
            persist()
            return Pair(true, "获得 ${PetCatalog.DAILY_REWARD_COIN} 金币！")
        }
    }

    // ================= 里程碑 =================

    /** 达成里程碑（去重 + 金币奖励，调用方需持锁）。 */
    private fun grantMilestoneLocked(milestoneId: String, coin: Int, message: String) {
        val bag = petBag
        if (bag.milestones[milestoneId] == true) return
        bag.milestones[milestoneId] = true
        bag.coin += coin
        logEventLocked(getActivePet().petId, "MILESTONE", "$message（奖励 $coin 金币）")
    }

    fun milestoneCount(): Int = synchronized(lock) { petBag.milestones.size }

    // ================= 随机事件 =================

    /**
     * 尝试触发随机事件（UI 每 ~60s 调用一次；内部 5 分钟冷却）。
     * 正向 55%：捡到金币/道具；负向 45%：弄脏/小不适。均写入事件日志。
     * 返回气泡文案；null=冷却中或不适用。
     */
    fun rollRandomEvent(): String? {
        synchronized(lock) {
            val pet = getActivePet()
            if (!pet.isAlive || pet.isSleeping || pet.lifeStage == "EGG") return null
            val now = System.currentTimeMillis()
            if (now - pet.lastEventTs < EVENT_COOLDOWN_MS) return null
            // 只在数值尚可时触发，低数值时不再雪上加霜
            if (pet.hunger < 25 || pet.clean < 25) return null
            pet.lastEventTs = now
            val good = Random.nextFloat() < 0.55f
            val msg = if (good) {
                if (Random.nextBoolean()) {
                    val coin = Random.nextInt(10, 31)
                    petBag.coin += coin
                    logEventLocked(pet.petId, "RANDOM_GOOD", "玩耍时捡到 $coin 金币！")
                    "我捡到 $coin 金币啦！✨"
                } else {
                    val item = PetCatalog.ITEMS.values.random()
                    petBag.itemStock[item.id] = (petBag.itemStock[item.id] ?: 0) + 1
                    logEventLocked(pet.petId, "RANDOM_GOOD", "捡到了${item.name}！")
                    "我捡到${item.name}啦！🎁"
                }
            } else {
                if (Random.nextBoolean()) {
                    pet.clean = (pet.clean - 15).coerceIn(0, 100)
                    pet.remClean = 0.0
                    logEventLocked(pet.petId, "RANDOM_BAD", "滚泥坑把身子弄脏了")
                    "呜呜，不小心滚进泥坑了…🫠"
                } else {
                    pet.health = (pet.health - 10).coerceIn(1, 100)
                    logEventLocked(pet.petId, "RANDOM_BAD", "有点着凉，不太舒服")
                    "阿嚏…好像有点着凉了🤧"
                }
            }
            persist()
            return msg
        }
    }

    // ================= 事件日志（Room 表，环形 500） =================

    private fun logEventLocked(petId: String, type: String, message: String) {
        runCatching {
            dao.insertLog(EventLogEntry(petId = petId, type = type, message = message, ts = System.currentTimeMillis()))
            if (dao.logCount() > PetDatabase.MAX_LOG) dao.trimLogTo(PetDatabase.MAX_LOG)
        }
        recordMomentLocked(petId, type, message)
    }

    fun logEvent(type: String, message: String) {
        synchronized(lock) { logEventLocked(getActivePet().petId, type, message) }
    }

    fun recentEvents(limit: Int = 200): List<EventLogEntry> =
        runCatching { dao.recentLogs(limit) }.getOrDefault(emptyList())

    // ================= 成长时间线（V1.2 情感记忆） =================

    /** 高光事件沉淀为「时刻」，独立于事件日志环形淘汰。调用方需持锁。 */
    private fun recordMomentLocked(petId: String, type: String, title: String) {
        if (type !in HIGHLIGHT_TYPES) return
        val bag = cachedBag ?: return
        bag.moments.add(
            Moment(
                momentId = UUID.randomUUID().toString(),
                petId = petId,
                type = type,
                title = title,
                ts = System.currentTimeMillis()
            )
        )
        if (bag.moments.size > MAX_MOMENTS) {
            bag.moments.subList(0, bag.moments.size - MAX_MOMENTS).clear()
        }
    }

    /** 时间倒序返回某宠物（null=全部）的成长时刻。 */
    fun listMoments(petId: String? = null): List<Moment> = synchronized(lock) {
        petBag.moments
            .filter { petId == null || it.petId == petId }
            .sortedByDescending { it.ts }
    }

    /** 返回全部离世纪念册（新近在前）。 */
    fun listMemorials(): List<Memorial> = synchronized(lock) {
        petBag.memorials.sortedByDescending { it.passedTs }
    }

    // ================= 生命闭环（V1.2：死亡 → 告别 → 重生） =================

    /**
     * 离世判定（幂等，调用方需持锁）。
     * 宠物 health 归零后进入虚弱沉睡（isAlive=false，可治疗唤醒）；若持续超过宽限期
     * [LIFE_GRACE_MS] 未治疗，则标记离世并归档纪念册。离世后不再结算、不可治疗。
     */
    private fun checkLifeClosure(pet: PetItem, now: Long) {
        if (pet.isAlive || pet.passedAtTs > 0) return
        if (pet.lastHealthZeroTs == 0L) {
            pet.lastHealthZeroTs = now
            return
        }
        if (now - pet.lastHealthZeroTs >= LIFE_GRACE_MS) {
            pet.passedAtTs = now
            archiveMemorialLocked(pet)
            logEventLocked(pet.petId, "PASSED", "TA永远地睡着了……")
            persist(force = true)
        }
    }

    /** 归档离世宠物纪念册（调用方需持锁）。 */
    private fun archiveMemorialLocked(pet: PetItem) {
        val bag = cachedBag ?: return
        bag.memorials.add(
            Memorial(
                memorialId = UUID.randomUUID().toString(),
                petId = pet.petId,
                name = pet.name,
                petType = pet.petType,
                birthTs = pet.birthTs,
                passedTs = pet.passedAtTs,
                lifespanMs = pet.passedAtTs - pet.birthTs,
                finalForm = pet.finalForm,
                playfulness = pet.playfulness,
                affection = pet.affection,
                momentCount = bag.moments.count { it.petId == pet.petId }
            )
        )
    }

    /** 重生：移除离世宠物，生成一只全新初代宠物（成长归零，纪念册保留）。 */
    fun rebirth(): PetItem {
        synchronized(lock) {
            val bag = petBag
            val passed = bag.petList.firstOrNull { it.passedAtTs > 0 } ?: return getActivePet()
            val now = System.currentTimeMillis()
            val newPet = PetItem(
                petId = UUID.randomUUID().toString(),
                petType = passed.petType,
                name = "小可爱",
                hunger = 80,
                happiness = 80,
                clean = 90,
                energy = 100,
                health = 100,
                lastUpdateTs = now,
                birthTs = now,
                lastCareTs = now,
                lifeStage = "CUB"
            )
            bag.petList.remove(passed)
            bag.petList.add(newPet)
            bag.activePetId = newPet.petId
            logEventLocked(newPet.petId, "HATCH", "新生命诞生了！")
            persist(force = true)
            return newPet
        }
    }

    // ================= 奖励 / 升级 =================

    /** 互动计数；蛋累计满 3 次破壳。 */
    private fun recordInteraction(pet: PetItem) {
        pet.interactions += 1
        if (pet.lifeStage == "EGG" && pet.interactions >= 3) {
            pet.lifeStage = lifeStageFor(pet.level)
            grantMilestoneLocked("hatch", 30, "🐣 破壳啦，新伙伴诞生！")
        }
    }

    /** 切换活跃宠物（先结算旧宠物衰减，再激活新宠物）。 */
    fun switchActivePet(newPetId: String): Boolean {
        synchronized(lock) {
            val bag = petBag
            val target = bag.petList.find { it.petId == newPetId } ?: return false
            recalculateState()
            bag.activePetId = target.petId
            target.lastUpdateTs = System.currentTimeMillis()
            target.remHunger = 0.0; target.remMood = 0.0
            target.remEnergy = 0.0; target.remClean = 0.0; target.remHealth = 0.0
            persist(force = true)
            return true
        }
    }

    /** 奖励统一入账：金币 + 活跃宠物经验，并推进升级/生命周期。 */
    fun addReward(coin: Int, exp: Int) {
        synchronized(lock) {
            val bag = petBag
            bag.coin += coin
            val pet = getActivePet()
            pet.exp += exp
            checkLevelUp(pet)
            persist()
        }
    }

    /**
     * 内部升级结算：可能连续升多级；成年时按 性格+照料质量 定型 finalForm（裁决 #5）。
     * 判定：学习多→STUDIOUS；爱玩高→PLAYFUL；健康差→DROOPY；其余→BALANCED。
     * 调用方需持有 lock。
     */
    private fun checkLevelUp(pet: PetItem): Boolean {
        var up = false
        var requiredExp = pet.level * 100
        while (pet.exp >= requiredExp) {
            pet.level += 1
            pet.exp -= requiredExp
            up = true
            if (pet.lifeStage != "EGG") {
                val newStage = lifeStageFor(pet.level)
                val oldStage = pet.lifeStage
                pet.lifeStage = newStage
                if (newStage != oldStage) {
                    when (newStage) {
                        "JUVENILE" -> grantMilestoneLocked("stage_juvenile", 20, "长成少年啦")
                        "ADOLESCENT" -> grantMilestoneLocked("stage_adolescent", 30, "长成青年啦")
                        "ADULT" -> {
                            pet.finalForm = when {
                                pet.learnCount >= 8 && pet.learnCount > pet.playCount -> "STUDIOUS"
                                pet.playfulness >= 30 || pet.playCount >= 8 -> "PLAYFUL"
                                pet.health < 50 -> "DROOPY"
                                else -> "BALANCED"
                            }
                            val formName = when (pet.finalForm) {
                                "STUDIOUS" -> "文静学霸形态"
                                "PLAYFUL" -> "活泼爱玩形态"
                                "DROOPY" -> "低落萎靡形态"
                                else -> "均衡形态"
                            }
                            grantMilestoneLocked("stage_adult", 50, "成年啦！定型为$formName")
                        }
                    }
                }
            }
            requiredExp = pet.level * 100
        }
        if (up) logEventLocked(pet.petId, "LEVEL_UP", "升到了 Lv.${pet.level}")
        return up
    }

    // ================= 落盘（10s 节流） =================

    /**
     * UI/Service 改动 bag 字段后调用。[force]=true 立即写（购买/切换等关键节点），
     * 否则 [PERSIST_THROTTLE_MS] 节流，窗口内仅标脏，由下一次到期写统一落盘。
     */
    fun persist(force: Boolean = false) {
        synchronized(lock) {
            val bag = cachedBag ?: return
            // 展示兼容：pillCount 恒等于治疗药剂库存（主控端旧版展示零改动）
            bag.pillCount = bag.itemStock["potion_heal"] ?: 0
            val now = System.currentTimeMillis()
            if (force || now - lastPersistTs >= PERSIST_THROTTLE_MS) {
                writeLocked(bag)
            } else {
                pendingDirty = true
            }
        }
    }

    /** 立即落盘所有挂起修改（Activity.onPause / Service 停止时调用，防丢档）。 */
    fun flush() {
        synchronized(lock) {
            cachedBag?.takeIf { pendingDirty }?.let { writeLocked(it) }
            pendingDirty = false
        }
    }

    private fun writeLocked(bag: PetBag) {
        runCatching {
            dao.upsertBag(PetBagRow(bagJson = gson.toJson(bag)))
            lastPersistTs = System.currentTimeMillis()
            pendingDirty = false
        }
    }
}
