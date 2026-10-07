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
import com.inklink.host.util.SoundEffectManager
import com.inklink.host.learning.LearningManager
import com.inklink.host.learning.LearningManager.Poem

/** 古诗亭(纯原生):每首诗先朗读跟读,再关键字背诵填空;错诗进错题本。 */
class PoemActivity : AppCompatActivity() {

    private lateinit var poems: List<Poem>
    private var pIdx = 0
    private var correct = 0
    private var quizCount = 0
    private val wrongTitles = LinkedHashSet<String>()
    private var startTs = 0L
    private lateinit var sfx: SoundEffectManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvProgress: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvBy: TextView
    private lateinit var tvLines: TextView
    private lateinit var tvTrans: TextView
    private lateinit var tvQuizLine: TextView
    private lateinit var cardRead: View
    private lateinit var cardQuiz: View
    private lateinit var btnRead: MaterialButton
    private lateinit var optionButtons: List<MaterialButton>
    private lateinit var tvError: TextView
    private var quiz: Pair<Int, String>? = null // <空缺在净化行中的下标, 答案字>
    private var quizAnswered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_poem)
        sfx = SoundEffectManager(this)

        tvProgress = findViewById(R.id.tvPoemProgress)
        tvTitle = findViewById(R.id.tvPoemTitle)
        tvBy = findViewById(R.id.tvPoemBy)
        tvLines = findViewById(R.id.tvPoemLines)
        tvTrans = findViewById(R.id.tvPoemTrans)
        tvQuizLine = findViewById(R.id.tvPoemQuizLine)
        cardRead = findViewById(R.id.poemReadCard)
        cardQuiz = findViewById(R.id.poemQuizCard)
        btnRead = findViewById(R.id.btnPoemRead)
        tvError = findViewById(R.id.tvPoemError)
        optionButtons = listOf(
            findViewById(R.id.btnTmOpt1), findViewById(R.id.btnTmOpt2),
            findViewById(R.id.btnTmOpt3), findViewById(R.id.btnTmOpt4)
        )
        btnRead.setOnClickListener { speakPoem() }
        findViewById<MaterialButton>(R.id.btnPoemRecite).setOnClickListener { startQuiz() }

        try {
            val level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2)
            poems = LearningManager.get(applicationContext).buildPoems(level)
            if (poems.isEmpty()) throw IllegalStateException("没有可学的古诗")
            startTs = System.currentTimeMillis()
            LearningManager.get(applicationContext).startSession()
            renderPoem()
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun showError(e: Exception) {
        tvError.visibility = View.VISIBLE
        tvError.text = "出错了:${e.message ?: e.javaClass.simpleName}\n请截图发给家长"
        cardRead.visibility = View.GONE
        cardQuiz.visibility = View.GONE
        btnRead.visibility = View.GONE
    }

    private fun speakPoem() {
        if (pIdx >= poems.size) return
        val p = poems[pIdx]
        runCatching { PetTtsGate.get(applicationContext).speak("${p.title},${p.author}。${p.lines.joinToString("。")}") }
    }

    private fun renderPoem() {
        if (pIdx >= poems.size) { finishLesson(); return }
        quiz = null
        tvProgress.text = "古诗亭 · ${pIdx + 1}/${poems.size} · ⭐ $correct"
        val p = poems[pIdx]
        tvTitle.text = "《${p.title}》"
        tvBy.text = "${p.dynasty} · ${p.author}"
        tvLines.text = p.lines.joinToString("\n")
        tvTrans.text = p.trans ?: ""
        cardRead.visibility = View.VISIBLE
        cardQuiz.visibility = View.GONE
        handler.postDelayed({ speakPoem() }, 400)
    }

    private fun startQuiz() {
        val p = poems[pIdx]
        val lineIdx = p.lines.indices.random()
        val line = p.lines[lineIdx]
        val clean = line.replace(Regex("[，。!?!,]"), "")
        if (clean.isEmpty()) { renderPoem(); return }
        val blankIdx = clean.indices.random()
        val answer = clean[blankIdx].toString()
        quiz = blankIdx to answer
        // 还原挖空显示(标点保留)
        val sb = StringBuilder()
        var ci = 0
        for (c in line) {
            if (c in "，。!?!,") { sb.append(c); continue }
            sb.append(if (ci == blankIdx) "○" else c)
            ci++
        }
        tvQuizLine.text = sb.toString()
        cardRead.visibility = View.GONE
        cardQuiz.visibility = View.VISIBLE
        quizAnswered = false

        val pool = "春花秋月山水风雪云天日月大小高低远近来去不知白黄青绿红".map { it.toString() }
        val distractors = pool.filter { it != answer && !line.contains(it) }.shuffled().take(3)
        val opts = (listOf(answer) + distractors).shuffled()
        optionButtons.forEachIndexed { i, btn ->
            val text: String = opts.getOrElse(i) { "" }
            btn.text = text
            btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            btn.isEnabled = true
            btn.setOnClickListener {
                if (text.isEmpty() || quizAnswered) return@setOnClickListener
                quizAnswered = true
                quizCount++
                if (text == answer) {
                    correct++
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_correct)
                    sfx.play(SoundEffectManager.Sfx.COIN)
                    PetTtsGate.get(applicationContext).speak("答对啦")
                    pIdx++
                    handler.postDelayed({ renderPoem() }, 1100)
                } else {
                    wrongTitles.add(p.title)
                    btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_wrong)
                    sfx.play(SoundEffectManager.Sfx.GROAN)
                    // 高亮正解后前进，避免连点多个错项把准确率分母越扩越大
                    optionButtons.forEachIndexed { j, b ->
                        if (opts.getOrElse(j) { "" } == answer) {
                            b.backgroundTintList = ContextCompat.getColorStateList(this, R.color.math_correct)
                        }
                    }
                    pIdx++
                    handler.postDelayed({ renderPoem() }, 1200)
                }
            }
        }
        runCatching { PetTtsGate.get(applicationContext).speak("背一背,空缺处是哪个字?") }
    }

    private fun finishLesson() {
        val total = quizCount.coerceAtLeast(1)
        val reward = LearningManager.get(applicationContext).finishLesson(
            LearningManager.LessonResult(
                module = LearningManager.MODULE_POEM,
                level = intent.getIntExtra(LessonRouter.EXTRA_LEVEL, 2),
                correct = correct,
                total = total,
                durationSec = ((System.currentTimeMillis() - startTs) / 1000).toInt(),
                chars = poems.map { it.title },
                wrongChars = wrongTitles.toList()
            )
        )
        PetTtsGate.get(applicationContext).speak(
            if (reward.capped) "完成啦!今天金币存满啦" else "太棒啦,获得${reward.coins}个金币!"
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sfx.release()
        super.onDestroy()
    }
}
