package com.inklink.host.learning

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.LearnProgressPayload
import com.inklink.host.InkHostApplication
import com.inklink.host.data.DailyStatsRow
import com.inklink.host.data.LearnDao
import com.inklink.host.data.LearnProgressRow
import com.inklink.host.data.PetDatabase
import com.inklink.host.data.WrongBookRow
import com.inklink.host.state.PetStateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 学习系统唯一业务入口(M1:识字屋)。职责对齐 PetStateManager 单例纪律:
 * 出题选字、结算入账(唯一走 PetStateManager.addReward)、进度/错题/日统计落库、
 * 47 码进度上报。UI 不直接写三张学习表。
 */
class LearningManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val dao: LearnDao = PetDatabase.get(appContext).learnDao()

    // ---------- 字库 ----------

    data class HanziEntry(
        @SerializedName("char") val char: String,
        @SerializedName("pinyin") val pinyin: String,
        @SerializedName("phrase") val phrase: String,
        @SerializedName("sentence") val sentence: String,
        @SerializedName("level") val level: Int
    )

    @Volatile
    private var hanziCache: List<HanziEntry>? = null

    fun hanziLibrary(): List<HanziEntry> {
        hanziCache?.let { return it }
        synchronized(this) {
            hanziCache?.let { return it }
            val json = appContext.assets.open("learning/hanzi.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<HanziEntry>>() {}.type
            val list: List<HanziEntry> = gson.fromJson(json, type)
            hanziCache = list
            return list
        }
    }

    // ---------- 出题 ----------

    /** 组一关课:优先插 2 个到期复习字,其余取本级未学字随机补齐。返回喂给 WebView 的 JSON。 */
    fun buildHanziLesson(level: Int, count: Int = 5): String {
        val now = System.currentTimeMillis()
        val lib = hanziLibrary().filter { it.level == level }
        val learned = dao.learnedItemIds(MODULE_HANZI).toSet()
        val byChar = lib.associateBy { it.char }

        val picked = LinkedHashMap<String, HanziEntry>()
        dao.dueReviews(MODULE_HANZI, now, 2)
            .mapNotNull { byChar[it.itemId] }
            .forEach { picked[it.char] = it }
        lib.filter { it.char !in learned && it.char !in picked }
            .shuffled()
            .take(count - picked.size)
            .forEach { picked[it.char] = it }
        if (picked.size < count) {
            // 本级全学完:从未到期已学字里随机抽,保证关卡可玩
            lib.filter { it.char !in picked }.shuffled()
                .take(count - picked.size)
                .forEach { picked[it.char] = it }
        }

        val pool = lib.map { it.char }.filter { it !in picked }.shuffled().take(24)
        val payload = LessonData(
            module = MODULE_HANZI, level = level,
            items = picked.values.toList(), pool = pool
        )
        return gson.toJson(payload)
    }

    private data class LessonData(
        @SerializedName("module") val module: String,
        @SerializedName("level") val level: Int,
        @SerializedName("items") val items: List<HanziEntry>,
        @SerializedName("pool") val pool: List<String>
    )

    // ---------- 结算 ----------

    data class RewardResult(val coins: Int, val exp: Int, val capped: Boolean, val mastered: Int)

    /** JS finishLesson 回传的结算数据。[chars] 为本关全部字,wrongChars ⊆ chars。 */
    data class LessonResult(
        @SerializedName("module") val module: String,
        @SerializedName("level") val level: Int,
        @SerializedName("correct") val correct: Int,
        @SerializedName("total") val total: Int,
        @SerializedName("durationSec") val durationSec: Int,
        @SerializedName("chars") val chars: List<String> = emptyList(),
        @SerializedName("wrongChars") val wrongChars: List<String> = emptyList()
    )

    fun finishLesson(resultJson: String): RewardResult {
        val result = runCatching {
            gson.fromJson(resultJson, LessonResult::class.java)
        }.getOrNull() ?: return RewardResult(0, 0, capped = false, mastered = 0)

        val rate = if (result.total > 0) result.correct.toDouble() / result.total else 0.0
        val coinsBase = 5 + (10 * rate).roundToInt()      // 5-15
        val exp = 10 + (25 * rate).roundToInt()           // 10-35
        val today = todayDate()

        val coinsGranted = run {
            val used = dao.todayCoinsSum(today)
            val allowance = (DAILY_COIN_CAP - used).coerceAtLeast(0)
            val granted = coinsBase.coerceAtMost(allowance)
            granted
        }
        val capped = coinsGranted < coinsBase

        // 宠物入账(唯一入口,联动升级/事件日志/礼花)
        if (coinsGranted > 0 || exp > 0) {
            PetStateManager(appContext).addReward(coinsGranted, exp)
        }

        // 进度与复习调度:只结算本关出现过的字(chars),错字优先处理
        val now = System.currentTimeMillis()
        var mastered = 0
        val existing = dao.allProgress(result.module).associateBy { it.itemId }
        fun upsertItem(char: String, wasCorrect: Boolean) {
            val row = existing[char] ?: LearnProgressRow(
                module = result.module, itemId = char, level = result.level
            )
            if (wasCorrect) {
                row.correctCount++
                if (row.reviewStage < REVIEW_INTERVAL_DAYS.size) {
                    row.reviewStage++
                }
                if (row.reviewStage >= REVIEW_INTERVAL_DAYS.size) {
                    row.status = "MASTERED"
                    mastered++
                    row.nextReviewTs = 0
                } else {
                    row.nextReviewTs = now + REVIEW_INTERVAL_DAYS[row.reviewStage] * DAY_MS
                }
            } else {
                row.wrongCount++
                row.reviewStage = 0
                row.status = "LEARNING"
                row.nextReviewTs = now + DAY_MS
            }
            row.lastTs = now
            dao.upsertProgress(row)
        }
        val wrong = result.wrongChars.toSet()
        result.chars.forEach { char -> upsertItem(char, wasCorrect = char !in wrong) }
        result.wrongChars.forEach {
            dao.insertWrong(WrongBookRow(module = result.module, itemId = it))
        }

        // 日统计
        val key = DailyStatsRow(date = today, module = result.module)
        val daily = dao.dailyRow(today, result.module) ?: key
        daily.minutes += result.durationSec / 60
        daily.itemsDone += result.total
        daily.correctSum += result.correct
        daily.coinsEarned += coinsGranted
        daily.expEarned += exp
        dao.upsertDaily(daily)

        reportProgress(result, rate)

        return RewardResult(coinsGranted, exp, capped, mastered)
    }

    private fun reportProgress(result: LessonResult, rate: Double) {
        runCatching {
            val app = appContext as InkHostApplication
            val payload = LearnProgressPayload(
                scope = "LESSON",
                module = result.module,
                level = result.level,
                itemsDone = result.total,
                correctRate = "%.2f".format(Locale.US, rate).toDouble(),
                minutesToday = dao.todayMinutes(todayDate()),
                coinsToday = dao.todayCoinsSum(todayDate()),
                weakTop5 = result.wrongChars.take(5)
            )
            app.transportManager.sendMessage(
                com.inklink.common.protocol.InkMessage.text(
                    MessageType.LEARN_PROGRESS, gson.toJson(payload), from = app.deviceId
                )
            )
        }
    }

    fun dueReviewCount(): Int = dao.dueReviewCount(MODULE_HANZI, System.currentTimeMillis())

    fun todaySummary(): Pair<Int, Int> =
        dao.todayMinutes(todayDate()) to dao.todayCoinsSum(todayDate())

    private fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        const val MODULE_HANZI = "HANZI"
        const val MODULE_PINYIN = "PINYIN"
        const val MODULE_MATH = "MATH"
        const val MODULE_POEM = "POEM"
        const val MODULE_ENGLISH = "ENGLISH"
        const val MODULE_REVIEW = "REVIEW"

        /** 每日学习金币上限(实现文档 6.2) */
        const val DAILY_COIN_CAP = 150

        /** 艾宾浩斯间隔(天):1/2/4/7/15 */
        val REVIEW_INTERVAL_DAYS = intArrayOf(1, 2, 4, 7, 15)
        private const val DAY_MS = 24 * 60 * 60_000L

        @Volatile
        private var instance: LearningManager? = null

        fun get(context: Context): LearningManager =
            instance ?: synchronized(this) {
                instance ?: LearningManager(context.applicationContext).also { instance = it }
            }
    }
}
