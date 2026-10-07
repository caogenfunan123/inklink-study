package com.inklink.controller.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.inklink.common.chat.ChatStore
import com.inklink.common.chat.VoicePlayer
import com.inklink.common.chat.VoiceRecorder
import com.inklink.common.protocol.ChatMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.utils.ImageUtil
import com.inklink.controller.InkControllerApplication
import com.inklink.controller.R
import java.util.concurrent.Executors

/**
 * 双端聊天页面：发送/接收文字、图片、语音消息。
 */
class ChatActivity : AppCompatActivity() {

    private val app get() = application as InkControllerApplication

    private lateinit var chatList: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var etInput: EditText
    private lateinit var recorder: VoiceRecorder

    private val listener = object : ChatStore.Listener {
        override fun onChatChanged() {
            // 异步出口双检：Activity 已销毁时再进渲染会操作游离 View
            runOnUiThread {
                if (!isDestroyed && !isFinishing) renderMessages()
            }
        }
    }

    /** 已渲染过的消息 id：增量追加，避免每条消息都全量 removeAllViews 重建。 */
    private val rendered = LinkedHashSet<Long>()
    private var emptyView: TextView? = null
    private val io = Executors.newSingleThreadExecutor()

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { sendImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        chatList = findViewById(R.id.chat_list)
        scrollView = findViewById(R.id.chat_scroll)
        etInput = findViewById(R.id.et_input)
        val btnSend = findViewById<Button>(R.id.btn_chat_send)
        val btnImage = findViewById<Button>(R.id.btn_chat_image)
        val btnVoice = findViewById<Button>(R.id.btn_chat_voice)

        recorder = VoiceRecorder(this)

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                // 未选中设备时 sendChatText 拒绝发送（旧实现会广播到全部设备）
                if (app.sendChatText(text)) etInput.setText("")
                else Toast.makeText(this, R.string.chat_no_device, Toast.LENGTH_SHORT).show()
            }
        }
        btnImage.setOnClickListener { pickImage.launch("image/*") }
        btnVoice.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startRecording()
                MotionEvent.ACTION_UP -> stopRecordingAndSend()
                MotionEvent.ACTION_CANCEL -> cancelRecording()
            }
            true
        }

        renderMessages()
    }

    override fun onResume() {
        super.onResume()
        app.chatStore.addListener(listener)
    }

    override fun onPause() {
        super.onPause()
        app.chatStore.removeListener(listener)
        // 按住录音键时被切后台：不收尾泄漏 MediaRecorder 与麦克风占用
        if (recorder.isRecording()) recorder.stop()
        VoicePlayer.stop()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD)
            return
        }
        if (recorder.start()) {
            Toast.makeText(this, R.string.chat_recording, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndSend() {
        if (!recorder.isRecording()) return
        val data = recorder.stop() ?: return
        val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
        if (base64.length > 60_000) {
            Toast.makeText(this, R.string.chat_voice_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        if (!app.sendChatAudio(base64)) {
            Toast.makeText(this, R.string.chat_no_device, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelRecording() {
        if (recorder.isRecording()) recorder.stop()
    }

    private fun sendImage(uri: Uri) {
        // 压缩是位图解码+缩放重编码（几十 ms 级）：留在主线程会让点击后掉帧
        io.execute {
            val base64 = ImageUtil.compressToBase64(this, uri)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (base64 == null || !app.sendChatImage(base64)) {
                    val msg = if (base64 == null) "图片过大，无法压缩后发送" else getString(R.string.chat_no_device)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderMessages() {
        // 时间线按当前选中设备过滤：多受控设备下不再混入其他孩子的会话
        val peer = app.transportManager.defaultTargetDeviceId
        val messages = app.chatStore.all(peer)
        val mine = app.deviceId
        val hint = when {
            peer == null -> getString(R.string.chat_no_device)
            messages.isEmpty() -> getString(R.string.chat_empty)
            else -> null
        }
        if (hint != null) {
            chatList.removeAllViews()
            rendered.clear()
            val empty = TextView(this).apply {
                text = hint
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            }
            emptyView = empty
            chatList.addView(empty)
            return
        }
        emptyView?.let { chatList.removeView(it) }
        emptyView = null
        for (msg in messages) {
            if (rendered.add(msg.id)) {
                chatList.addView(buildBubble(msg, msg.fromDeviceId == mine))
            }
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun buildBubble(msg: ChatMessage, mine: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
        }
        val bubbleBg = if (mine) R.drawable.bubble_mine else R.drawable.bubble_other
        val textColor = if (mine) Color.WHITE else Color.BLACK

        when (msg.type) {
            MessageType.CHAT_TEXT -> {
                val tv = TextView(this).apply {
                    text = msg.payload
                    textSize = 15f
                    setTextColor(textColor)
                    setBackgroundResource(bubbleBg)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    maxWidth = dp(240)
                }
                container.addView(tv)
            }
            MessageType.CHAT_IMAGE -> {
                // 占位先入列，位图解码放后台：主线程同步 decode 大图会卡渲染
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(bubbleBg)
                }
                val lp = LinearLayout.LayoutParams(dp(160), dp(160))
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                iv.layoutParams = lp
                container.addView(iv)
                io.execute {
                    val bitmap = ImageUtil.decodeBase64(msg.payload)
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        if (bitmap != null) iv.setImageBitmap(bitmap)
                    }
                }
            }
            MessageType.CHAT_AUDIO -> {
                val tv = TextView(this).apply {
                    text = "🔊 " + getString(R.string.chat_voice_msg)
                    textSize = 15f
                    setTextColor(textColor)
                    setBackgroundResource(bubbleBg)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        runCatching {
                            val bytes = Base64.decode(msg.payload, Base64.NO_WRAP)
                            VoicePlayer.play(this@ChatActivity, bytes)
                        }
                    }
                }
                container.addView(tv)
            }
            else -> Unit
        }
        return container
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_RECORD = 100
    }
}
