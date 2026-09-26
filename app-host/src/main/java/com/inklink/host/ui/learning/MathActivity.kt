package com.inklink.host.ui.learning

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.audio.PetTtsGate
import com.inklink.host.learning.LearningManager
import com.inklink.host.util.SoundEffectManager

/**
 * 口算商店(M2,原生实现):一关 10 题,题型随级别(10以内加减/分与合/比大小/20以内进退位/数列)。
 * 答对金币音效、答错震动+错题入册;结束统一走 finishLesson 结算(module=MATH,type 为知识点)。
 */
class MathActivity : AppCompatActivity() {

    private lateinit var questions: List<LearningManager.MathQuestion>
    private var idx = 0
    private var correct = 0
    private val wrongTypes = LinkedHashSet<String>()
    private val seenTypes = LinkedHashSet<String>()
    private var startTs = 0L
    private lateinit var sfx: SoundEffectManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvProgress: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var btnSpeak: MaterialButton
    private lateinit var optionButtons: List<MaterialButton>
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_math)
        sfx = SoundEffectManager(this)

        val level = intent.getIntExtra(LessonActivity.EXTRA_LEVEL, 2)
        questions = LearningManager.get(applicationContext).generateMathQuestions(level)
        startTs = System.currentTimeMillis()

        tvProgress = findViewById(R.id.tvMathProgress)
        tvQuestion = findViewById(R.id.tvMathQuestion)
        btnSpeak = findViewById(R.id.btnMathSpeak)
        optionButtons = listOf(
            findViewById(R.id.btnOpt1), findViewById(R.id.btnOpt2),
            findViewById(R.id.btnOpt3), findViewById(R.id.btnOpt4)
        )
        btnSpeak.setOnClickListener { speakCurrent() }
        render()
    }

    private fun speakCurrent() {
        if (idx < questions.size) {
            runCatching { PetTtsGate.get(applicationContext).speak(questions[idx].speak) }
        }
    }

    private fun render() {
        if (idx >= questions.size) { finishLesson(); return }
        answered = false
        val q = questions[idx]
        tvProgress.text = "口算商店 · ${idx + 1}/${questions.size} · ⭐ $correct"
        tvQuestion.text = q.text
        optionButtons.forEachIndexed { i, btn ->
            val text = q.options.getOrNull(i) ?: ""
            btn.text = text
            btn.visibility = if (text.isEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
            btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            btn.isEnabled = true
            btn.setOnClickListener {
                if (answered) return@setOnClickListener
                answered = true
                seenTypes.add(q.type)
                val ok = i == q.answer
                if (ok) {
                    correct++
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_correct)
                    sfx.play(SoundEffectManager.Sfx.COIN)
                    PetTtsGate.get(applicationContext).speak("答对啦")
                    idx++
                    render()
                } else {
                    wrongTypes.add(q.type)
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_wrong)
                    btn.isEnabled = false
                    sfx.play(SoundEffectManager.Sfx.GROAN)
                    optionButtons[q.answer].backgroundTintList =
                        ContextCompat.getColorStateList(this, R.color.math_correct)
                    idx++
                    handler.postDelayed({ render() }, 1200)
                }
            }
        }
        handler.postDelayed({ speakCurrent() }, 350)
    }

    private fun finishLesson() {
        val total = questions.size
        val rate = if (total > 0) correct.toDouble() / total else 0.0
        val stars = if (rate >= 0.9) 3 else if (rate >= 0.6) 2 else 1
        val reward = LearningManager.get(applicationContext).finishLesson(
            LearningManager.LessonResult(
                module = LearningManager.MODULE_MATH,
                level = intent.getIntExtra(LessonActivity.EXTRA_LEVEL, 2),
                correct = correct,
                total = total,
                durationSec = ((System.currentTimeMillis() - startTs) / 1000).toInt(),
                chars = seenTypes.toList(),
                wrongChars = wrongTypes.toList()
            )
        )
        PetTtsGate.get(applicationContext).speak(
            if (reward.capped) "完成啦!今天金币存满啦" else "太棒啦,获得${reward.coins}个金币!"
        )
        Toast.makeText(
            this,
            "完成!答对 $correct/$total · ${"⭐".repeat(stars)} · 金币+${reward.coins}",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
