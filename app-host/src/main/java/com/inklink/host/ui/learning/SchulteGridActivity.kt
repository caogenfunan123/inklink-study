package com.inklink.host.ui.learning

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.inklink.host.R
import com.inklink.host.learning.LearningManager
import com.inklink.host.util.SoundEffectManager

/** 专注力训练(P1-3):舒尔特方格 5×5,按 1-25 顺序点完,计时入榜;每日首局有奖励。 */
class SchulteGridActivity : AppCompatActivity() {

    private var next = 1
    private var startElapsed = 0L
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schulte)
        tvStatus = findViewById(R.id.tvSchulteStatus)
        val grid = findViewById<GridLayout>(R.id.schulteGrid)
        val mgr = LearningManager.get(applicationContext)
        val numbers = (1..25).shuffled()
        numbers.forEach { n ->
            val btn = MaterialButton(this).apply {
                text = n.toString()
                textSize = 22f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (resources.displayMetrics.density * 56).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setMargins(4, 4, 4, 4)
            }
            btn.setOnClickListener { onPick(btn, n) }
            grid.addView(btn)
        }
        tvStatus.text = "按顺序点 1 → 25,开始!"
    }

    private fun onPick(btn: Button, n: Int) {
        if (n != next) {
            tvStatus.text = "顺序错啦,要找 $next 哦"
            return
        }
        if (next == 1) startElapsed = SystemClock.elapsedRealtime()
        btn.isEnabled = false
        btn.alpha = 0.25f
        if (next == 25) {
            val secs = (SystemClock.elapsedRealtime() - startElapsed) / 1000.0
            tvStatus.text = "完成!用时 %.1f 秒 🎉".format(secs)
            SoundEffectManager(this).play(SoundEffectManager.Sfx.LEVEL_UP)
            if (!rewarded) {
                LearningManager.get(applicationContext).finishLesson(
                    LearningManager.LessonResult(
                        module = "FOCUS", level = 2,
                        correct = 1, total = 1,
                        durationSec = secs.toInt(),
                        chars = listOf("SCHULTE25")
                    )
                )
            }
            findViewById<View>(R.id.btnSchulteAgain).visibility = View.VISIBLE
        } else {
            next++
            tvStatus.text = "下一个:$next"
        }
    }

    fun restart(@Suppress("UNUSED_PARAMETER") v: View) {
        recreate()
    }
}
