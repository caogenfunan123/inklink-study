package com.inklink.host.state

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.inklink.common.transport.LocalWsTransport
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 主控端推送的告警消息。
 */
data class HostAlert(
    val title: String,
    val content: String
)

/**
 * 受控端共享状态与画面自愈管理器。由 [com.inklink.host.service.InkForegroundService] 写入，
 * [com.inklink.host.ui.HostActivity] 监听刷新 UI。
 */
class HostScreenState {

    interface Listener {
        fun onStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val logDeque = ArrayDeque<String>()
    private val textLines = ArrayList<String>()
    private val cachedTextLines = ArrayList<String>()
    private var cachedImage: Bitmap? = null

    var connected = false
        private set
    var currentImage: Bitmap? = null
        private set
    var localIp: String? = null
        private set
    var wsPort: Int = LocalWsTransport.DEFAULT_PORT
        private set
    var inCall = false
        private set
    var isRinging = false
        private set
    var batteryPct = 100
        private set
    var isCharging = false
        private set

    /** 主控端推送的告警消息（悬浮横幅展示）。 */
    var pendingAlert: HostAlert? = null
        private set

    val logs: List<String> get() = synchronized(logDeque) { logDeque.toList() }
    val screenText: List<String> get() = synchronized(textLines) { textLines.toList() }

    fun setConnected(value: Boolean) {
        connected = value
        notifyChanged()
    }

    fun setImage(bitmap: Bitmap?) {
        currentImage = bitmap
        if (bitmap != null) {
            cachedImage = bitmap
        }
        notifyChanged()
    }

    fun setLocalIp(ip: String?) {
        localIp = ip
        notifyChanged()
    }

    fun setWsPort(port: Int) {
        wsPort = port
        notifyChanged()
    }

    fun setInCall(value: Boolean) {
        inCall = value
        notifyChanged()
    }

    fun setRinging(value: Boolean) {
        isRinging = value
        notifyChanged()
    }

    /** 展示主控端推送的告警横幅。 */
    fun showAlert(alert: HostAlert) {
        pendingAlert = alert
        appendLog("收到告警: ${alert.title}")
        notifyChanged()
    }

    /** 关闭当前告警横幅。 */
    fun dismissAlert() {
        if (pendingAlert != null) {
            pendingAlert = null
            notifyChanged()
        }
    }

    fun updateBattery(pct: Int, charging: Boolean) {
        batteryPct = pct
        isCharging = charging
        notifyChanged()
    }

    fun appendText(line: String) {
        synchronized(textLines) {
            if (textLines.size >= MAX_TEXT_LINES) textLines.removeAt(0)
            textLines.add(line)
            cachedTextLines.clear()
            cachedTextLines.addAll(textLines)
        }
        notifyChanged()
    }

    fun clearScreen() {
        synchronized(textLines) { textLines.clear() }
        currentImage = null
        notifyChanged()
    }

    /**
     * 画面缓存自愈：从最近一次有效画面/文字恢复并触发重绘。
     */
    fun restoreScreenCache(): Boolean {
        var restored = false
        if (cachedImage != null && currentImage == null) {
            currentImage = cachedImage
            restored = true
        }
        synchronized(textLines) {
            if (textLines.isEmpty() && cachedTextLines.isNotEmpty()) {
                textLines.addAll(cachedTextLines)
                restored = true
            }
        }
        if (restored) {
            appendLog("执行画面自愈恢复")
            notifyChanged()
        }
        return restored
    }

    fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(logDeque) {
            logDeque.addLast("$timestamp $message")
            while (logDeque.size > MAX_LOG_ENTRIES) logDeque.removeFirst()
        }
        notifyChanged()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        mainHandler.post {
            listeners.forEach { it.onStateChanged() }
        }
    }

    companion object {
        const val MAX_LOG_ENTRIES = 50
        const val MAX_TEXT_LINES = 50
    }
}
