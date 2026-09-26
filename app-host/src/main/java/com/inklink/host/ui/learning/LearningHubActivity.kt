package com.inklink.host.ui.learning

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.learning.LearningManager

/**
 * 学习乐园总图(M1:识字屋可玩,其余岛占位)。
 * 顶部今日汇总 + 复习入口,中部级别选择(L1-L4),下部课程岛网格。
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

        val mgr = LearningManager.get(this)

        val tvToday = findViewById<TextView>(R.id.tvTodayStats)
        val (minutes, coins) = mgr.todaySummary()
        tvToday.text = "今天学了 $minutes 分钟 · 赚了 $coins 金币"

        val due = mgr.dueReviewCount()
        val btnReview = findViewById<MaterialButton>(R.id.btnReview)
        if (due > 0) {
            btnReview.visibility = View.VISIBLE
            btnReview.text = "📖 今日复习 $due 个"
            btnReview.setOnClickListener { openLesson(selectedLevel) }
        }

        btnL1 = findViewById(R.id.btnLevel1)
        btnL2 = findViewById(R.id.btnLevel2)
        btnL3 = findViewById(R.id.btnLevel3)
        btnL4 = findViewById(R.id.btnLevel4)
        val chips = mapOf(
            btnL1 to 1, btnL2 to 2, btnL3 to 3, btnL4 to 4
        )
        chips.forEach { (btn, lv) ->
            btn.setOnClickListener {
                selectedLevel = lv
                chips.forEach { (b, l) ->
                    b.alpha = if (l == lv) 1f else 0.45f
                }
            }
        }
        chips.forEach { (b, l) -> b.alpha = if (l == selectedLevel) 1f else 0.45f }

        findViewById<View>(R.id.cardHanzi).setOnClickListener { openLesson(selectedLevel) }
        findViewById<View>(R.id.cardPinyin).setOnClickListener { comingSoon() }
        findViewById<View>(R.id.cardMath).setOnClickListener { comingSoon() }
        findViewById<View>(R.id.cardPoem).setOnClickListener { comingSoon() }
        findViewById<View>(R.id.cardEnglish).setOnClickListener { comingSoon() }
    }

    private fun openLesson(level: Int) {
        startActivity(
            Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_LEVEL, level)
        )
    }

    private fun comingSoon() {
        Toast.makeText(this, "建设中,敬请期待!", Toast.LENGTH_SHORT).show()
    }
}
