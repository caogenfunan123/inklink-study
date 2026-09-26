package com.inklink.host.ui.learning

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.audio.PetTtsGate
import com.inklink.host.learning.LearningManager
import com.inklink.host.learning.LearningManager.PinyinLetter
import com.inklink.host.learning.LearningManager.PinyinQuestion

/** 拼音森林(纯原生):6 张字母卡 → 4 道声调配对 → 结算。TTS 用代表字代偿发音。 */
class PinyinActivity : AppCompatActivity() {

    private lateinit var letters: List<PinyinLetter>
    private lateinit var questions: List<PinyinQuestion>
    private var idx = 0
    private var qIdx = 0
    private var correct = 0
    private val wrongPinyin = LinkedHashSet<String>()
    private var startTs = 0L
    private lateinit var sfx: SoundEffectManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvProgress: TextView
    private lateinit var tvLetter: TextView
    private lateinit var tvTips: TextView
    private lateinit var tvProxy: TextView
    private lateinit var tvQuizChar: TextView
    private lateinit var tvQuizEmoji: TextView
    private lateinit var cardIntro: View
    private lateinit var cardQuiz: View
    private lateinit var btnSpeak: MaterialButton
    private lateinit var optionButtons: List<MaterialButton>
    private lateinit var tvError: TextView
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_pinyin)
        sfx = SoundEffectManager(this)

        tvProgress = findViewById(R.id.tvPinyinProgress)
        tvLetter = findViewById(R.id.tvPinyinLetter)
        tvTips = findViewById(R.id.tvPinyinTips)
        tvProxy = findViewById(R.id.tvPinyinProxy)
        tvQuizChar = findViewById(R.id.tvPinyinQuizChar)
        tvQuizEmoji = findViewById(R.id.tvPinyinQuizEmoji)
        cardIntro = findViewById(R.id.pinyinIntroCard)
        cardQuiz = findViewById(R.id.pinyinQuizCard)
        btnSpeak = findViewById(R.id.btnPinyinSpeak)
        tvError = findViewById(R.id.tvPinyinError)
        optionButtons = listOf(
            findViewById(R.id.btnPOpt1), findViewById(R.id.btnPOpt2),
            findViewById(R.id.btnPOpt3), findViewById(R.id.btnPOpt4)
        )
        btnSpeak.setOnClickListener { speakCurrent() }
        findViewById<MaterialButton>(R.id.btnPinyinLearned).setOnClickListener { startQuiz() }

        try {
            val mgr = LearningManager.get(applicationContext)
            val level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2)
            val reviewOnly = intent.getBooleanExtra(LessonRouter.EXTRA_REVIEW, false)
            val pair = mgr.buildPinyinData(level, reviewOnly)
            letters = pair.first
            questions = pair.second
            if (letters.isEmpty()) throw IllegalStateException("没有可学的拼音内容")
            startTs = System.currentTimeMillis()
            mgr.startSession()
            renderLetter()
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun showError(e: Exception) {
        tvError.visibility = View.VISIBLE
        tvError.text = "出错了:${e.message ?: e.javaClass.simpleName}\n请截图发给家长"
        cardIntro.visibility = View.GONE
        cardQuiz.visibility = View.GONE
        btnSpeak.visibility = View.GONE
    }

    private fun speakCurrent() {
        if (idx < letters.size) {
            val l = letters[idx]
            val text = if (l.proxyChar.isNullOrBlank()) l.pinyin else l.proxyChar
            runCatching { PetTtsGate.get(applicationContext).speak(text) }
        }
    }

    private fun renderLetter() {
        answered = false
        val l = letters[idx]
        tvProgress.text = "拼音森林 · $idx/${letters.size} · ⭐ $correct"
        tvLetter.text = l.pinyin
        tvTips.text = l.tips
        tvProxy.text = if (l.proxyChar.isNullOrBlank()) "" else "像读:${l.proxyChar}"
        cardIntro.visibility = View.VISIBLE
        cardQuiz.visibility = View.GONE
        handler.postDelayed({ speakCurrent() }, 350)
    }

    private fun startQuiz() {
        cardIntro.visibility = View.GONE
        cardQuiz.visibility = View.VISIBLE
        renderQuestion()
    }

    private fun renderQuestion() {
        if (qIdx >= questions.size) { finishLesson(); return }
        answered = false
        val q = questions[qIdx]
        tvProgress.text = "拼音森林 · 配对 ${qIdx + 1}/${questions.size} · ⭐ $correct"
        tvQuizChar.text = q.char
        tvQuizEmoji.text = q.image ?: "❓"
        val ans = markTone(q.pinyin, q.tone)
        val distractors = LearningManager.get(applicationContext).pinyinLibrary()
            .map { it.pinyin }.filter { it != q.pinyin }.shuffled().take(3)
            .map { markTone(it, (1..4).random()) }
        val opts = (listOf(ans) + distractors).shuffled()
        optionButtons.forEachIndexed { i, btn ->
            val text = opts.getOrNull(i) ?: ""
            btn.text = text
            btn.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
            btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            btn.isEnabled = true
            btn.setOnClickListener {
                if (answered) return@setOnClickListener
                answered = true
                if (text == ans) {
                    correct++
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_correct)
                    sfx.play(SoundEffectManager.Sfx.COIN)
                    PetTtsGate.get(applicationContext).speak("答对啦,${q.char}")
                    qIdx++
                    handler.postDelayed({ renderQuestion() }, 1100)
                } else {
                    wrongPinyin.add(q.pinyin)
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_wrong)
                    btn.isEnabled = false
                    sfx.play(SoundEffectManager.Sfx.GROAN)
                    PetTtsGate.get(applicationContext).speak("这是${q.char}")
                }
            }
        }
    }

    /** 声调标注:标在第一个元音上。 */
    private fun markTone(base: String, tone: Int): String {
        val marks = mapOf(1 to "\u0304", 2 to "\u0301", 3 to "\u030C", 4 to "\u0300")
        val m = marks[tone] ?: return base
        val vowel = "aoeiuvü"
        for (i in base.indices) {
            if (vowel.contains(base[i])) return base.substring(0, i) + base[i] + m + base.substring(i + 1)
        }
        return base + m
    }

    private fun finishLesson() {
        val total = letters.size + questions.size
        val score = letters.size + correct // 字母卡跟读计掌握
        val rate = if (total > 0) score.toDouble() / total else 0.0
        val stars = if (rate >= 0.9) 3 else if (rate >= 0.6) 2 else 1
        val reward = LearningManager.get(applicationContext).finishLesson(
            LearningManager.LessonResult(
                module = LearningManager.MODULE_PINYIN,
                level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2),
                correct = score,
                total = total,
                durationSec = ((System.currentTimeMillis() - startTs) / 1000).toInt(),
                chars = letters.map { it.pinyin } + questions.map { it.pinyin },
                wrongChars = wrongPinyin.toList()
            )
        )
        PetTtsGate.get(applicationContext).speak(
            if (reward.capped) "完成啦!今天金币存满啦" else "太棒啦,获得${reward.coins}个金币!"
        )
        Toast.makeText(this, "完成!${"⭐".repeat(stars)} · 金币+${reward.coins}", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
