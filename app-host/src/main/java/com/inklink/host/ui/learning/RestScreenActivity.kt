package com.inklink.host.ui.learning

import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inklink.host.R
import com.inklink.host.audio.PetTtsGate
import com.inklink.host.learning.LearningManager

/**
 * 护眼休息页(P0-5):连续学习到点后强制全屏休息 5 分钟,
 * 倒计时结束才能继续;宠物口吻引导远眺放松。
 */
class RestScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setContentView(R.layout.activity_rest)
        LearningManager.get(applicationContext).resetSession()

        val tvCountdown = findViewById<TextView>(R.id.tvRestCountdown)
        val minutes = LearningManager.get(applicationContext).restMin
        object : CountDownTimer(minutes * 60_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                tvCountdown.text = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
            }

            override fun onFinish() {
                PetTtsGate.get(applicationContext).speak("休息好啦,我们继续学习吧!")
                finish()
            }
        }.start()
        PetTtsGate.get(applicationContext).speak("小眼睛要看远处休息一会哦,眨眨眼,看一看窗外的风景。")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 休息未结束不允许退出(护眼硬规则)
    }
}
