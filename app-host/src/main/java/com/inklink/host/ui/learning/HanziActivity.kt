package com.inklink.host.ui.learning

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.audio.PetTtsGate
import com.inklink.host.learning.LearningManager
import com.inklink.host.learning.LearningManager.HanziEntry
import com.inklink.host.util.SoundEffectManager

/**
 * 识字屋(纯原生,与口算商店同一模式):5 张认读卡 → 5 道听音选字 → 结算。
 * 错字进错题本与艾宾浩斯复习队列;奖励走 addReward 唯一入口。
 */
class HanziActivity : AppCompatActivity() {

    private lateinit var items: List<HanziEntry>
    private var idx = 0
    private var correct = 0
    private val wrongChars = LinkedHashSet<String>()
    private var startTs = 0L
    private lateinit var sfx: SoundEffectManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvProgress: TextView
    private lateinit var tvChar: TextView
    private lateinit var tvPinyin: TextView
    private lateinit var tvPhrase: TextView
    private lateinit var tvSentence: TextView
    private lateinit var btnSpeak: MaterialButton
    private lateinit var optionButtons: List<MaterialButton>
    private lateinit var tvError: TextView
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_hanzi)
        sfx = SoundEffectManager(this)

        tvProgress = findViewById(R.id.tvHanziProgress)
        tvChar = findViewById(R.id.tvHanziChar)
        tvPinyin = findViewById(R.id.tvHanziPinyin)
        tvPhrase = findViewById(R.id.tvHanziPhrase)
        tvSentence = findViewById(R.id.tvHanziSentence)
        btnSpeak = findViewById(R.id.btnHanziSpeak)
        tvError = findViewById(R.id.tvHanziError)
        optionButtons = listOf(
            findViewById(R.id.btnHOpt1), findViewById(R.id.btnHOpt2),
            findViewById(R.id.btnHOpt3), findViewById(R.id.btnHOpt4)
        )
        btnSpeak.setOnClickListener { speakCurrent() }

        try {
            val level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2)
            val reviewOnly = intent.getBooleanExtra(LessonRouter.EXTRA_REVIEW, false)
            items = LearningManager.get(applicationContext).buildHanziEntries(level, reviewOnly = reviewOnly)
            if (items.isEmpty()) throw IllegalStateException("本级没有可学的汉字")
            startTs = System.currentTimeMillis()
            LearningManager.get(applicationContext).startSession()
            render()
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun showError(e: Exception) {
        tvError.visibility = View.VISIBLE
        tvError.text = "出错了:${e.message ?: e.javaClass.simpleName}\n请截图发给家长"
        tvChar.text = "⚠️"
        tvPinyin.text = ""
        tvPhrase.text = ""
        tvSentence.text = ""
        optionButtons.forEach { it.visibility = View.GONE }
        btnSpeak.visibility = View.GONE
    }

    private fun speakCurrent() {
        if (idx < items.size) {
            runCatching { PetTtsGate.get(applicationContext).speak(items[idx].char) }
        }
    }

    private fun render() {
        if (idx >= items.size) { finishLesson(); return }
        answered = false
        val entry = items[idx]
        tvProgress.text = "识字屋 · $idx/${items.size} · ⭐ $correct"
        tvChar.text = entry.char
        tvPinyin.text = entry.pinyin
        tvPhrase.text = if (entry.phrase.isBlank()) "" else "词语:${entry.phrase}"
        tvSentence.text = entry.sentence
        // 干扰字从同级字库随机,排除正确字与重复
        val distractors = LearningManager.get(applicationContext).hanziLibrary()
            .filter { h -> h.level == entry.level && h.char != entry.char }
            .shuffled().take(3).map { h -> h.char }
        val opts = (listOf(entry.char) + distractors).shuffled()
        optionButtons.forEachIndexed { i, btn ->
            val text = opts.getOrNull(i) ?: ""
            btn.text = text
            btn.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
            btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            btn.isEnabled = true
            btn.setOnClickListener {
                if (answered) return@setOnClickListener
                answered = true
                if (text == entry.char) {
                    correct++
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_correct)
                    sfx.play(SoundEffectManager.Sfx.COIN)
                    PetTtsGate.get(applicationContext).speak("答对啦,${entry.char}")
                    idx++
                    handler.postDelayed({ render() }, 1100)
                } else {
                    wrongChars.add(entry.char)
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_wrong)
                    btn.isEnabled = false
                    sfx.play(SoundEffectManager.Sfx.GROAN)
                    PetTtsGate.get(applicationContext).speak("再看一看,这是${entry.char}")
                }
            }
        }
        handler.postDelayed({ speakCurrent() }, 350)
    }

    private fun finishLesson() {
        val total = items.size
        val rate = if (total > 0) correct.toDouble() / total else 0.0
        val stars = if (rate >= 0.9) 3 else if (rate >= 0.6) 2 else 1
        val reward = LearningManager.get(applicationContext).finishLesson(
            LearningManager.LessonResult(
                module = LearningManager.MODULE_HANZI,
                level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2),
                correct = correct,
                total = total,
                durationSec = ((System.currentTimeMillis() - startTs) / 1000).toInt(),
                chars = items.map { it.char },
                wrongChars = wrongChars.toList()
            )
        )
        PetTtsGate.get(applicationContext).speak(
            if (reward.capped) "完成啦!今天金币存满啦" else "太棒啦,获得${reward.coins}个金币!"
        )
        Toast.makeText(this, "完成!答对 $correct/$total · ${"⭐".repeat(stars)} · 金币+${reward.coins}", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
