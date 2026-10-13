package com.inklink.host.qqpet

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * QQPet 小游戏全屏播放页（移植自 third_party/qqpet/android 的 GameActivity.kt，
 * 去掉 PetService 悬浮窗显隐；保留 gamePlayed 分钟上报——游戏厅内每日奖励靠它结算）。
 *
 * 横屏沉浸式加载 game.html?swf=<编码后的 SWF 路径>，Ruffle 播放；
 * 页面「退出」调 window.close()，WebView 对非脚本打开的页面会忽略，
 * 故注入 QQPetGame.close() 桥并重写 window.close。
 */
class QQPetGameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SWF = "swf"
    }

    private lateinit var web: WebView
    private val start = SystemClock.elapsedRealtime()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // The page's 退出 calls window.close(), which a WebView ignores for a page it did not open by script.
            addJavascriptInterface(object {
                @JavascriptInterface
                fun close() = runOnUiThread { finish() }
            }, "QQPetGame")
            webViewClient = object : QQPetAssetClient(this@QQPetGameActivity) {
                override fun onPageFinished(view: WebView, url: String) =
                    view.evaluateJavascript("window.close = () => QQPetGame.close()", null)
            }
        }
        setContentView(web)
        WindowInsetsControllerCompat(window, web).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        web.loadUrl("$QQPET_ORIGIN/game.html?swf=${Uri.encode(intent.getStringExtra(EXTRA_SWF))}")
    }

    override fun onDestroy() {
        web.destroy()
        // 分钟数上报给大厅页（onGamePlayed → 元宝/心情/成长结算）；返回大厅（非 finish）不上报
        if (isFinishing) QQPetActivity.gamePlayedListener?.invoke((SystemClock.elapsedRealtime() - start) / 60000.0)
        super.onDestroy()
    }
}
