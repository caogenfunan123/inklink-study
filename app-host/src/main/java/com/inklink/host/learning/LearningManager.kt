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

    /** 组一关课(原生):优先插 2 个到期复习字,其余取本级未学字随机补齐。 */
    fun buildHanziEntries(level: Int, count: Int = 5, reviewOnly: Boolean = false): List<HanziEntry> {
        val now = System.currentTimeMillis()
        val lib = hanziLibrary().filter { it.level == level }
        val learned = dao.learnedItemIds(MODULE_HANZI).toSet()
        val byChar = lib.associateBy { it.char }

        val picked = LinkedHashMap<String, HanziEntry>()
        if (reviewOnly) {
            dao.dueReviews(MODULE_HANZI, now, count)
                .mapNotNull { byChar[it.itemId] }
                .forEach { picked[it.char] = it }
            if (picked.isEmpty()) {
                lib.filter { it.char in learned }.shuffled()
                    .take(count)
                    .forEach { picked[it.char] = it }
            }
            return picked.values.toList()
        }
        dao.dueReviews(MODULE_HANZI, now, 2)
            .mapNotNull { byChar[it.itemId] }
            .forEach { picked[it.char] = it }
        lib.filter { it.char !in learned && it.char !in picked }
            .shuffled()
            .take(count - picked.size)
            .forEach { picked[it.char] = it }
        if (picked.size < count) {
            lib.filter { it.char !in picked }.shuffled()
                .take(count - picked.size)
                .forEach { picked[it.char] = it }
        }
        return picked.values.toList()
    }

    // ==================== M2:拼音 / 古诗 / 口算 / 复习 / 护眼 ====================

    data class PinyinLetter(
        @SerializedName("pinyin") val pinyin: String,
        @SerializedName("category") val category: String,
        @SerializedName("tips") val tips: String = "",
        @SerializedName("char") val proxyChar: String? = null
    )

    data class PinyinQuestion(
        @SerializedName("pinyin") val pinyin: String,
        @SerializedName("tone") val tone: Int,
        @SerializedName("char") val char: String,
        @SerializedName("image") val image: String? = null
    )

    /** 拼音一关(原生):6 张字母卡(声母/韵母/整体认读混抽) + 4 道声调配对题。 */
    fun buildPinyinData(level: Int, reviewOnly: Boolean = false): Pair<List<PinyinLetter>, List<PinyinQuestion>> {
        val all = pinyinLibrary()
        val learned = dao.learnedItemIds(MODULE_PINYIN).toSet()
        val due = dao.dueReviews(MODULE_PINYIN, System.currentTimeMillis(), 6).map { it.itemId }.toSet()
        val rawLetters = if (reviewOnly) {
            val first = all.filter { it.pinyin in due }
            (if (first.size >= 6) first else (first + all.filter { it.pinyin in learned }))
                .distinctBy { it.pinyin }.shuffled().take(6)
        } else {
            val fresh = all.filter { it.pinyin !in learned }
            (fresh + all).distinctBy { it.pinyin }.shuffled().take(6)
        }
        // 声母/韵母补代表字,保证 TTS 有可读的整字
        val letters = rawLetters.map { l ->
            if (l.proxyChar.isNullOrBlank()) l.copy(proxyChar = proxyCharFor(l)) else l
        }
        val questions = pinyinQuestions().shuffled().take(4)
        return letters to questions
    }

    @Volatile
    private var pinyinCache: List<PinyinLetter>? = null

    @Volatile
    private var pinyinQuestionCache: List<PinyinQuestion>? = null

    fun pinyinLibrary(): List<PinyinLetter> {
        pinyinCache?.let { return it }
        synchronized(this) {
            pinyinCache?.let { return it }
            val json = appContext.assets.open("learning/pinyin.json").bufferedReader().use { it.readText() }
            val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java)
            fun readList(key: String): List<PinyinLetter> {
                val type = object : TypeToken<List<PinyinLetter>>() {}.type
                return gson.fromJson(obj.getAsJsonArray(key), type)
            }
            val list = readList("initials") + readList("vowels") + readList("wholeSyllables")
            pinyinCache = list
            return list
        }
    }

    data class CommonSyllable(
        @SerializedName("syllable") val syllable: String,
        @SerializedName("initial") val initial: String,
        @SerializedName("final") val final: String,
        @SerializedName("tone") val tone: Int,
        @SerializedName("char") val char: String
    )

    @Volatile
    private var commonSyllableCache: List<CommonSyllable>? = null

    fun commonSyllables(): List<CommonSyllable> {
        commonSyllableCache?.let { return it }
        synchronized(this) {
            commonSyllableCache?.let { return it }
            val json = appContext.assets.open("learning/pinyin.json").bufferedReader().use { it.readText() }
            val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java)
            val type = object : TypeToken<List<CommonSyllable>>() {}.type
            val list: List<CommonSyllable> = gson.fromJson(obj.getAsJsonArray("commonSyllables"), type)
            commonSyllableCache = list
            return list
        }
    }

    /** 拼音 TTS 代偿:声母/韵母没有整字发音,借常用音节例字代读(b→播)。 */
    fun proxyCharFor(letter: PinyinLetter): String? {
        val common = commonSyllables()
        return when {
            letter.category == "initial" -> common.firstOrNull { it.initial == letter.pinyin }?.char
            letter.category == "wholeSyllable" -> common.firstOrNull { it.syllable == letter.pinyin }?.char
            else -> common.firstOrNull { it.final == letter.pinyin }?.char
        }
    }

    fun pinyinQuestions(): List<PinyinQuestion> {
        pinyinQuestionCache?.let { return it }
        synchronized(this) {
            pinyinQuestionCache?.let { return it }
            val json = appContext.assets.open("learning/pinyin_questions.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<PinyinQuestion>>() {}.type
            val list: List<PinyinQuestion> = gson.fromJson(json, type)
            pinyinQuestionCache = list
            return list
        }
    }

    // ---------- 古诗 ----------

    data class Poem(
        @SerializedName("id") val id: String,
        @SerializedName("title") val title: String,
        @SerializedName("author") val author: String,
        @SerializedName("dynasty") val dynasty: String,
        @SerializedName("lines") val lines: List<String>,
        // 行级拼音（poems.json 每行一句拼音字符串），与 hanzi 的逐字嵌套数组形状不同——
        // 曾误声明为 List<List<String>> 导致 Gson 抛 Expected BEGIN_ARRAY，古诗功能整体失效
        @SerializedName("pinyins") val pinyins: List<String>? = null,
        @SerializedName("trans") val trans: String? = null,
        @SerializedName("level") val level: Int
    )

    @Volatile
    private var poemCache: List<Poem>? = null

    fun poemLibrary(): List<Poem> {
        poemCache?.let { return it }
        synchronized(this) {
            poemCache?.let { return it }
            val json = appContext.assets.open("learning/poems.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<Poem>>() {}.type
            val list: List<Poem> = gson.fromJson(json, type)
            poemCache = list
            return list
        }
    }

    /** 古诗一关(原生):本级抽 3 首(朗读 + 背诵填空)。 */
    fun buildPoems(level: Int, count: Int = 3): List<Poem> {
        val pool = poemLibrary().filter { it.level == level }.ifEmpty { poemLibrary() }
        return pool.shuffled().take(count)
    }

    // ---------- 口算(原生) ----------

    data class MathQuestion(
        val type: String,
        val text: String,
        val speak: String,
        val options: List<String>,
        val answer: Int
    )

    /** 口算生成器(L2:10以内加减/分与合/比大小;L1:5以内;L3:20以内进退位+数列)。 */
    fun generateMathQuestions(level: Int, count: Int = 10): List<MathQuestion> {
        val rnd = java.util.Random()
        val out = ArrayList<MathQuestion>(count)
        val types = when (level) {
            1 -> listOf("ADD5", "SUB5")
            3 -> listOf("ADD20", "SUB20", "SEQ")
            else -> listOf("ADD10", "SUB10", "SPLIT", "COMPARE")
        }
        repeat(count) {
            val type = types[rnd.nextInt(types.size)]
            fun opts(ans: Int): Pair<List<String>, Int> {
                val set = linkedSetOf(ans)
                var guard = 0
                while (set.size < 4 && guard < 50) {
                    val d = rnd.nextInt(5) - 2
                    if (ans + d >= 0) set.add(ans + d)
                    guard++
                }
                val shuffled = set.toList().shuffled()
                return shuffled.map { it.toString() } to shuffled.indexOf(ans)
            }
            when (type) {
                "ADD5", "ADD10", "ADD20" -> {
                    val max = if (type == "ADD5") 5 else if (type == "ADD10") 10 else 20
                    val a = rnd.nextInt(max - 1) + 1
                    val b = rnd.nextInt(max - a) + 1
                    val pair = opts(a + b)
                    out.add(MathQuestion(type, "$a + $b = ?", "$a 加 $b 等于几?", pair.first, pair.second))
                }
                "SUB5", "SUB10", "SUB20" -> {
                    val max = if (type == "SUB5") 5 else if (type == "SUB10") 10 else 20
                    val a = rnd.nextInt(max - 1) + 2
                    val b = rnd.nextInt(a - 1) + 1
                    val pair = opts(a - b)
                    out.add(MathQuestion(type, "$a - $b = ?", "$a 减 $b 等于几?", pair.first, pair.second))
                }
                "SPLIT" -> {
                    val total = rnd.nextInt(9) + 2
                    val a = rnd.nextInt(total - 1) + 1
                    val pair = opts(total - a)
                    out.add(MathQuestion("SPLIT", "$total 可以分成 $a 和 ?", "$total 可以分成 $a 和 几?", pair.first, pair.second))
                }
                "COMPARE" -> {
                    val a = rnd.nextInt(10) + 1
                    val b = rnd.nextInt(10) + 1
                    val ans = if (a > b) 0 else if (a < b) 1 else 2
                    out.add(MathQuestion("COMPARE", "$a ○ $b", "$a 和 $b 比一比", listOf(">", "<", "="), ans))
                }
                else -> {
                    val start = rnd.nextInt(5) + 1
                    val step = rnd.nextInt(3) + 2
                    val s0 = start
                    val s1 = start + step
                    val s2 = start + step * 2
                    val s3 = start + step * 3
                    val pair = opts(s3)
                    out.add(MathQuestion("SEQ", "$s0, $s1, $s2, ?", "找规律,下一个数是几?", pair.first, pair.second))
                }
            }
        }
        return out
    }

    // ---------- 复习谷 / 错题本 ----------

    data class DueSummary(val hanzi: Int, val pinyin: Int, val math: Int)

    fun dueSummary(): DueSummary {
        val now = System.currentTimeMillis()
        return DueSummary(
            dao.dueReviewCount(MODULE_HANZI, now),
            dao.dueReviewCount(MODULE_PINYIN, now),
            dao.dueReviewCount(MODULE_MATH, now)
        )
    }

    data class WrongItem(
        val module: String,
        val itemId: String,
        val wrongCount: Int,
        val lastTs: Long,
        val level: Int
    )

    fun wrongItems(limit: Int = 50): List<WrongItem> =
        dao.wrongItems(limit).map { WrongItem(it.module, it.itemId, it.wrongCount, it.lastTs, it.level) }

    // ---------- 护眼防沉迷 ----------

    /** 单次连续学习上限(分钟)。 */
    val sessionLimitMin = 20
    /** 强制休息时长(分钟)。 */
    val restMin = 5
    /** 每日总时长上限(分钟)。 */
    val dailyCapMin = 40

    @Volatile
    private var sessionStartElapsed = 0L

    fun startSession() {
        if (sessionStartElapsed == 0L) {
            sessionStartElapsed = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun resetSession() {
        sessionStartElapsed = 0L
    }

    /** 阻断原因:null=可学;"REST"=连续超时需休息;"CAP"=今日到量;"NIGHT"=夜间。 */
    fun guardBlock(): String? {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 21 || hour < 6) return "NIGHT"
        if (dao.todayMinutes(todayDate()) >= dailyCapMin) return "CAP"
        if (sessionStartElapsed != 0L) {
            val elapsedMin = (android.os.SystemClock.elapsedRealtime() - sessionStartElapsed) / 60000
            if (elapsedMin >= sessionLimitMin) return "REST"
        }
        return null
    }

    // ---------- 结算与上报 ----------

    data class RewardResult(val coins: Int, val exp: Int, val capped: Boolean, val mastered: Int)

    /** JS/原生共用的结算数据。[chars] 为本关全部知识点,wrongChars ⊆ chars。 */
    data class LessonResult(
        @SerializedName("module") val module: String,
        @SerializedName("level") val level: Int,
        @SerializedName("correct") val correct: Int,
        @SerializedName("total") val total: Int,
        @SerializedName("durationSec") val durationSec: Int,
        @SerializedName("chars") val chars: List<String> = emptyList(),
        @SerializedName("wrongChars") val wrongChars: List<String> = emptyList()
    )

    /** 对象入口:内部序列化后走统一结算。 */
    fun finishLesson(result: LessonResult): RewardResult = finishLesson(gson.toJson(result))

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
            coinsBase.coerceAtMost(allowance)
        }
        val capped = coinsGranted < coinsBase

        // 奖励入账 + 学习记录 + 日统计走同一事务：中途进程被杀时要么全部生效要么
        // 全部回滚（此前是三次独立写，杀在中间会"发了币但没记进度"）
        PetDatabase.get(appContext).runInTransaction {
            // 宠物入账(唯一入口,联动升级/事件日志/礼花)
            if (coinsGranted > 0 || exp > 0) {
                PetStateManager(appContext).addReward(coinsGranted, exp)
            }

            // 进度与复习调度:只结算本关出现过的知识点（同一事务内）
            val now = System.currentTimeMillis()
            var mastered = 0
            val existing = dao.allProgress(result.module).associateBy { it.itemId }
            fun upsertItem(itemId: String, wasCorrect: Boolean) {
                val row = existing[itemId] ?: LearnProgressRow(
                    module = result.module, itemId = itemId, level = result.level
                )
                if (wasCorrect) {
                    row.correctCount++
                    if (row.reviewStage < REVIEW_INTERVAL_DAYS.size) row.reviewStage++
                    if (row.reviewStage >= REVIEW_INTERVAL_DAYS.size) {
                        // 已掌握的知识点重复上课只刷新统计：无脑 mastered++ 会让掌握度
                        // 展示虚高，且 nextReviewTs=0 导致该条永不再复习
                        val alreadyMastered = row.status == "MASTERED"
                        row.status = "MASTERED"
                        if (!alreadyMastered) mastered++
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
            result.chars.forEach { itemId -> upsertItem(itemId, wasCorrect = itemId !in wrong) }
            result.wrongChars.forEach { dao.insertWrong(WrongBookRow(module = result.module, itemId = it)) }

            // 日统计
            val daily = dao.dailyRow(today, result.module) ?: DailyStatsRow(date = today, module = result.module)
            daily.minutes += result.durationSec / 60
            daily.itemsDone += result.total
            daily.correctSum += result.correct
            daily.coinsEarned += coinsGranted
            daily.expEarned += exp
            dao.upsertDaily(daily)
        }

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
