package com.inklink.host.qqpet

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * QQPet 游戏厅主页面（移植自 third_party/qqpet 的 index.html 桌面宠页面）。
 *
 * 全屏 WebView 加载 vite 产物 index.html，素材与 Ruffle 经 [QQPetAssetClient] 从
 * APK assets Serve。存档 save.json 落在本应用 filesDir，与 InkLink 自身宠物状态
 * （SharedPreferences/Room）完全隔离——QQPet 宠物是「游戏厅宠物」，正主仍是 InkLink 宠物。
 *
 * 刻意不移植上游的悬浮窗生态（PetService 悬浮窗 / BootReceiver 自启 /
 * MainActivity 权限中转 / TextActivity 剪贴板监听）：InkLink 已有自己的
 * InkForegroundService 与宠物，双悬浮窗会互相打架。
 *
 * window.QQPetNative 桥接口契约见 third_party/qqpet/src/android/bridge.ts。
 */
class QQPetActivity : AppCompatActivity() {

    companion object {
        /**
         * 小游戏游玩分钟数上报（移植上游 GameActivity 的 gamePlayed emit）：
         * 大厅页 games.ts/community.ts 的 onGamePlayed 靠它结算游戏厅内每日奖励
         * （元宝/心情/成长，日上限 60 分钟）。由 QQPetGameActivity 结束时经此分发，
         * 对大厅页 WebView 执行 __qqpet.emit；大厅已销毁时为 null，静默丢弃。
         */
        var gamePlayedListener: ((Double) -> Unit)? = null
    }

    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()

    /** exportSave/importSave 的文件选择器回调，按 JS 侧 id 挂起。 */
    private val pendingPicks = ConcurrentHashMap<Int, (Uri?) -> Unit>()
    private var pickId = -1

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingPicks.remove(pickId)
        cb?.invoke(if (result.resultCode == RESULT_OK) result.data?.data else null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = QQPetAssetClient(this@QQPetActivity)
            addJavascriptInterface(Bridge(), "QQPetNative")
        }
        setContentView(web)
        WindowInsetsControllerCompat(window, web).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        web.loadUrl("$QQPET_ORIGIN/index.html")
        gamePlayedListener = { minutes ->
            web.evaluateJavascript("__qqpet.emit('gamePlayed', $minutes)", null)
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        io.shutdown()
        gamePlayedListener = null
        web.destroy()
        super.onDestroy()
    }

    /** 回填 JS 侧挂起的 Promise；evaluateJavascript 必须在 UI 线程调用。 */
    private fun resolve(id: Int, value: String?, error: String? = null) = runOnUiThread {
        web.evaluateJavascript("__qqpet.resolve($id, ${quote(value)}, ${quote(error)})", null)
    }

    private fun quote(s: String?) = if (s == null) "null" else JSONObject.quote(s)

    /** 注册一次文件选择：先存回调再 launch，result 回来后按 id 取回。 */
    private fun pick(id: Int, cb: (Uri?) -> Unit) {
        pickId = id
        pendingPicks[id] = cb
    }

    /** window.QQPetNative；契约见 third_party/qqpet/src/android/bridge.ts。方法运行在 WebView 线程。 */
    private inner class Bridge {

        @JavascriptInterface
        fun read(name: String): String? = File(filesDir, name).takeIf { it.exists() }?.readText()

        @JavascriptInterface
        fun write(name: String, text: String) {
            val tmp = File(filesDir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(filesDir, name))
        }

        /** JS fetch 代理：天气/日历/AI/检查更新走原生 http，绕过 WebView 混合内容限制。 */
        @JavascriptInterface
        fun http(id: Int, method: String, url: String, headers: String, body: String?) = io.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = 60_000
                c.readTimeout = 60_000
                val h = JSONObject(headers)
                for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                resolve(id, JSONObject().put("status", c.responseCode).put("body", text).toString())
            } catch (e: Exception) {
                // Runs on a pool thread: anything uncaught here kills the app.
                resolve(id, null, e.toString())
            }
        }

        /** 导出存档：ACTION_CREATE_DOCUMENT 选落点，取消时 resolve null。 */
        @JavascriptInterface
        fun exportSave(id: Int, name: String, text: String) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, name)
            }
            pick(id) { uri ->
                try {
                    if (uri != null) contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    resolve(id, uri?.let { "true" })
                } catch (e: Exception) {
                    resolve(id, null, e.toString())
                }
            }
            pickLauncher.launch(intent)
        }

        /** 导入存档：ACTION_OPEN_DOCUMENT 选文件读文本，取消时 resolve null。 */
        @JavascriptInterface
        fun importSave(id: Int) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            pick(id) { uri ->
                try {
                    resolve(id, uri?.let { u -> contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() } })
                } catch (e: Exception) {
                    resolve(id, null, e.toString())
                }
            }
            pickLauncher.launch(intent)
        }

        /** 打开小游戏全屏页（game.html?swf=…，Ruffle 播放）。 */
        @JavascriptInterface
        fun openGame(swf: String) = startActivity(
            Intent(this@QQPetActivity, QQPetGameActivity::class.java).putExtra(QQPetGameActivity.EXTRA_SWF, swf)
        )

        /** 上游语义是「退出桌面宠」；游戏厅内即关闭本页。 */
        @JavascriptInterface
        fun quit() = finish()

        @JavascriptInterface
        fun copyText(text: String) = getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("QQ宠物", text))

        @JavascriptInterface
        fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

        // ---- 以下为上游悬浮窗/通知生态专用，全屏 Activity 下无操作 ----

        @JavascriptInterface
        fun setFocusable(on: Boolean) = Unit

        @JavascriptInterface
        fun setAutoStart(on: Boolean) = Unit

        @JavascriptInterface
        fun setTray(icon: String, tip: String?) = Unit

        @JavascriptInterface
        fun setBounds(x: Double, y: Double, w: Double, h: Double, viewport: Double) = Unit

        /** "left,top,right,bottom" of the system bars and cutout, in CSS pixels of a page [viewport] pixels wide. */
        @JavascriptInterface
        fun safeArea(viewport: Double): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "0,0,0,0"
            val m = windowManager.maximumWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val scale = m.bounds.width() / viewport
            return listOf(i.left, i.top, i.right, i.bottom).joinToString(",") { "${it / scale}" }
        }
    }
}
