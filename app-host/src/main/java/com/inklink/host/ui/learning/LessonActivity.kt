package com.inklink.host.ui.learning

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Vibrator
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.inklink.host.R
import com.inklink.host.audio.PetTtsGate
import com.inklink.host.learning.LearningManager
import com.inklink.host.util.SoundEffectManager

/**
 * 关卡容器:WebView 加载 assets/learning/index.html(全离线),
 * 通过 window.InkBridge 暴露 5 个能力:TTS 朗读、音效、震动、结算、关闭。
 * 安全:仅允许 file:///android_asset/ 来源,任何外链跳转一律拦截。
 */
class LessonActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sfx: SoundEffectManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_lesson)

        webView = findViewById(R.id.lessonWebView)
        sfx = SoundEffectManager(this)

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            // 只加载内置 assets:file 源之间 XHR(fetch strokes.json)必须放行
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // 阻断一切外链(零服务器铁律)
                return !url.startsWith("file:///android_asset/")
            }
        }
        webView.addJavascriptInterface(Bridge(), "InkBridge")
        webView.loadUrl("file:///android_asset/learning/index.html")
    }

    inner class Bridge {

        /** 页面启动时索取课程数据 → 回调 window.onLessonData(json) */
        @JavascriptInterface
        fun getLessonData() {
            val level = intent.getIntExtra(EXTRA_LEVEL, 2)
            val json = LearningManager.get(applicationContext).buildHanziLesson(level)
            runOnUiThread {
                webView.evaluateJavascript("window.onLessonData($json)", null)
            }
        }

        /** 朗读(解决 WebView 无 speechSynthesis 的坑) */
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                PetTtsGate.get(applicationContext).speak(text)
            }
        }

        @JavascriptInterface
        fun playSfx(name: String) {
            runOnUiThread {
                runCatching { sfx.play(SoundEffectManager.Sfx.valueOf(name)) }
            }
        }

        @JavascriptInterface
        fun haptic(ms: Long) {
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(ms.coerceIn(0, 1000))
        }

        /** 关卡结算:入账 + 落库 + 47 上报,由 [LearningManager] 统一处理 */
        @JavascriptInterface
        fun finishLesson(resultJson: String) {
            val reward = LearningManager.get(applicationContext).finishLesson(resultJson)
            runOnUiThread {
                val msg = if (reward.capped) {
                    "完成啦!今天金币存满啦,明天再来吧!"
                } else {
                    "太棒啦,获得${reward.coins}个金币!"
                }
                PetTtsGate.get(applicationContext).speak(msg)
                sfx.play(SoundEffectManager.Sfx.LEVEL_UP)
            }
        }

        @JavascriptInterface
        fun closeLesson() {
            runOnUiThread { finish() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    companion object {
        const val EXTRA_LEVEL = "extra_level"
    }
}
