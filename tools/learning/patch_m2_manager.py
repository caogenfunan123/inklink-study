# -*- coding: utf-8 -*-
"""一次性补丁:LearningManager 扩展 M2 能力(拼音/古诗/口算/复习/护眼)。运行后可删除。"""
import io

P = 'app-host/src/main/java/com/inklink/host/learning/LearningManager.kt'
s = io.open(P, encoding='utf-8').read()

# 1) buildHanziLesson 支持 reviewOnly
old1 = '''    /** 组一关课:优先插 2 个到期复习字,其余取本级未学字随机补齐。返回喂给 WebView 的 JSON。 */
    fun buildHanziLesson(level: Int, count: Int = 5): String {
        val now = System.currentTimeMillis()
        val lib = hanziLibrary().filter { it.level == level }
        val learned = dao.learnedItemIds(MODULE_HANZI).toSet()
        val byChar = lib.associateBy { it.char }

        val picked = LinkedHashMap<String, HanziEntry>()
        dao.dueReviews(MODULE_HANZI, now, 2)
            .mapNotNull { byChar[it.itemId] }
            .forEach { picked[it.char] = it }
        lib.filter { it.char !in learned && it.char !in picked }'''
new1 = '''    /** 组一关课:优先插 2 个到期复习字,其余取本级未学字随机补齐。返回喂给 WebView 的 JSON。 */
    fun buildHanziLesson(level: Int, count: Int = 5, reviewOnly: Boolean = false): String {
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
            return gson.toJson(
                LessonData(MODULE_HANZI, level, picked.values.toList(),
                    lib.map { it.char }.filter { it !in picked }.shuffled().take(24))
            )
        }
        dao.dueReviews(MODULE_HANZI, now, 2)
            .mapNotNull { byChar[it.itemId] }
            .forEach { picked[it.char] = it }
        lib.filter { it.char !in learned && it.char !in picked }'''
assert old1 in s, 'anchor1'
s = s.replace(old1, new1)

# 2) M2 扩展块插在 todaySummary 之后
anchor2 = '''    fun todaySummary(): Pair<Int, Int> =
        dao.todayMinutes(todayDate()) to dao.todayCoinsSum(todayDate())'''
block2 = anchor2 + '''

    // ==================== M2:拼音 / 古诗 / 口算 / 复习 / 护眼 ====================

    data class PinyinLetter(
        @SerializedName("pinyin") val pinyin: String,
        @SerializedName("category") val category: String,
        @SerializedName("tips") val tips: String,
        @SerializedName("char") val proxyChar: String
    )

    data class PinyinQuestion(
        @SerializedName("pinyin") val pinyin: String,
        @SerializedName("tone") val tone: Int,
        @SerializedName("char") val char: String,
        @SerializedName("image") val image: String
    )

    /** 拼音一关:6 张字母卡(声母/韵母/整体认读混抽) + 4 道声调配对题。 */
    fun buildPinyinLesson(level: Int, reviewOnly: Boolean = false): String {
        val all = pinyinLibrary()
        val learned = dao.learnedItemIds(MODULE_PINYIN).toSet()
        val due = dao.dueReviews(MODULE_PINYIN, System.currentTimeMillis(), 6).map { it.itemId }.toSet()
        val letters = if (reviewOnly) {
            val first = all.filter { it.pinyin in due }
            (if (first.size >= 6) first else (first + all.filter { it.pinyin in learned }))
                .distinctBy { it.pinyin }.shuffled().take(6)
        } else {
            val fresh = all.filter { it.pinyin !in learned }
            (fresh + all).distinctBy { it.pinyin }.shuffled().take(6)
        }
        val questions = pinyinQuestions().shuffled().take(4)
        val pool = all.map { it.pinyin }.shuffled().take(20)
        return gson.toJson(LessonDataPin(MODULE_PINYIN, level, letters, questions, pool))
    }

    private data class LessonDataPin(
        @SerializedName("module") val module: String,
        @SerializedName("level") val level: Int,
        @SerializedName("letters") val letters: List<PinyinLetter>,
        @SerializedName("questions") val questions: List<PinyinQuestion>,
        @SerializedName("pool") val pool: List<String>
    )

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
        @SerializedName("pinyins") val pinyins: List<List<String>>,
        @SerializedName("trans") val trans: String,
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

    /** 古诗一关:本级抽 3 首(朗读 + 背诵填空)。 */
    fun buildPoemLesson(level: Int): String {
        val pool = poemLibrary().filter { it.level == level }.ifEmpty { poemLibrary() }
        val poems = pool.shuffled().take(3)
        return gson.toJson(LessonDataPoem(MODULE_POEM, level, poems))
    }

    private data class LessonDataPoem(
        @SerializedName("module") val module: String,
        @SerializedName("level") val level: Int,
        @SerializedName("poems") val poems: List<Poem>
    )

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
                return shuffled to shuffled.indexOf(ans)
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

    data class WrongItem(val module: String, val itemId: String, val wrongCount: Int, val lastTs: Long)

    fun wrongItems(limit: Int = 50): List<WrongItem> =
        dao.wrongItems(limit).map { WrongItem(it.module, it.itemId, it.wrongCount, it.lastTs) }

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
    }'''
assert anchor2 in s, 'anchor2'
s = s.replace(anchor2, block2)
io.open(P, 'w', encoding='utf-8').write(s)
print('LearningManager M2 extension OK')
