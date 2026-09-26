package com.inklink.host.ui.learning

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.learning.LearningManager

/**
 * 学习乐园(M2):五大岛全部开放 + 复习谷 + 错题本 + 专注力训练。
 * 护眼硬规则在入口统一拦截:夜间 21:00-6:30 / 每日 40 分钟 / 连续 20 分钟强制休息。
 */
class LearningHubActivity : AppCompatActivity() {

    private var selectedLevel = 2

    private lateinit var btnL1: MaterialButton
    private lateinit var btnL2: MaterialButton
    private lateinit var btnL3: MaterialButton
    private lateinit var btnL4: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learning_hub)

        val mgr = LearningManager.get(applicationContext)
        mgr.startSession()

        val tvToday = findViewById<TextView>(R.id.tvTodayStats)
        val (minutes, coins) = mgr.todaySummary()
        tvToday.text = "今天学了 $minutes 分钟 · 赚了 $coins 金币"

        val due = mgr.dueSummary()
        val btnReview = findViewById<MaterialButton>(R.id.btnReview)
        val dueText = listOfNotNull(
            if (due.hanzi > 0) "识字${due.hanzi}" else null,
            if (due.pinyin > 0) "拼音${due.pinyin}" else null,
            if (due.math > 0) "口算${due.math}" else null
        ).joinToString(" · ")
        if (dueText.isNotEmpty()) {
            btnReview.visibility = View.VISIBLE
            btnReview.text = "📖 今日复习:$dueText"
        }

        btnL1 = findViewById(R.id.btnLevel1)
        btnL2 = findViewById(R.id.btnLevel2)
        btnL3 = findViewById(R.id.btnLevel3)
        btnL4 = findViewById(R.id.btnLevel4)
        val chips = mapOf(btnL1 to 1, btnL2 to 2, btnL3 to 3, btnL4 to 4)
        chips.forEach { (btn, lv) ->
            btn.setOnClickListener {
                selectedLevel = lv
                chips.forEach { (b, l) -> b.alpha = if (l == lv) 1f else 0.45f }
            }
        }
        chips.forEach { (b, l) -> b.alpha = if (l == selectedLevel) 1f else 0.45f }

        findViewById<View>(R.id.cardHanzi).setOnClickListener { openLesson(LearningManager.MODULE_HANZI) }
        findViewById<View>(R.id.cardPinyin).setOnClickListener { openLesson(LearningManager.MODULE_PINYIN) }
        findViewById<View>(R.id.cardMath).setOnClickListener { openMath() }
        findViewById<View>(R.id.cardPoem).setOnClickListener { openLesson(LearningManager.MODULE_POEM) }
        findViewById<View>(R.id.cardReview).setOnClickListener {
            val due = mgr.dueSummary()
            val module = when {
                due.pinyin >= due.hanzi && due.pinyin >= due.math -> LearningManager.MODULE_PINYIN
                due.math >= due.hanzi -> LearningManager.MODULE_MATH
                else -> LearningManager.MODULE_HANZI
            }
            if (module == LearningManager.MODULE_MATH) openMath()
            else openLesson(module, review = true)
        }
        findViewById<View>(R.id.cardWrong).setOnClickListener { startActivity(Intent(this, WrongBookActivity::class.java)) }
        findViewById<View>(R.id.cardFocus).setOnClickListener { startActivity(Intent(this, SchulteGridActivity::class.java)) }
        findViewById<View>(R.id.cardEnglish).setOnClickListener { comingSoon() }
    }

    private fun openLesson(module: String, review: Boolean = false) {
        if (!guardPass()) return
        startActivity(
            Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_MODULE, module)
                .putExtra(LessonActivity.EXTRA_LEVEL, selectedLevel)
                .putExtra(LessonActivity.EXTRA_REVIEW, review)
        )
    }

    private fun openMath() {
        if (!guardPass()) return
        startActivity(
            Intent(this, MathActivity::class.java)
                .putExtra(LessonActivity.EXTRA_LEVEL, selectedLevel)
        )
    }

    /** 护眼三规则拦截,阻断原因用宠物口吻 TTS 播报。 */
    private fun guardPass(): Boolean {
        val mgr = LearningManager.get(applicationContext)
        val block = mgr.guardBlock()
        if (block != null) {
            val (msg, rest) = when (block) {
                "NIGHT" -> "夜深啦,小宠物要睡觉了,明早再来学吧!" to false
                "CAP" -> "今天学习时间用完啦,去玩会儿或陪小宠物吧!" to false
                else -> "学了很久啦,先休息 ${mgr.restMin} 分钟保护小眼睛!" to true
            }
            if (rest) startActivity(Intent(this, RestScreenActivity::class.java))
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show()
            return false
        }
        return true
    }

    private fun comingSoon() {
        Toast.makeText(this, "建设中,敬请期待!", Toast.LENGTH_SHORT).show()
    }
}
