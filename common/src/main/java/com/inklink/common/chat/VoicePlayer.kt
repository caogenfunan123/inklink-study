package com.inklink.common.chat

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 聊天语音播放（AMR）。点击语音气泡时调用 [play]。
 *
 * 两条线程纪律（都是踩过的坑）：
 * 1. **落盘走单线程 IO 执行器**，避免主线程同步写文件卡顿；
 * 2. **MediaPlayer 必须创建/操作在有 Looper 的线程上**——其构造函数内部 new Handler()
 *    取当前线程 Looper，工作线程上直接抛 "Can't create handler inside thread that has
 *    not called Looper.prepare()"（同 RawSoundPlayer 的约束），故 IO 完成后切回主线程创建。
 * 临时文件随下一次播放/停止立即删除，防止 cacheDir 无限增长。
 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun play(context: Context, data: ByteArray) {
        io.execute {
            val f = File(context.cacheDir, "chat_play_${System.currentTimeMillis()}.amr")
            val written = runCatching { f.writeBytes(data) }.isSuccess
            main.post {
                releasePlayer()
                if (!written) {
                    player = null
                    currentFile = null
                    return@post
                }
                runCatching {
                    player = MediaPlayer().apply {
                        setDataSource(f.absolutePath)
                        prepare()
                        setOnCompletionListener { mp ->
                            mp.release()
                            if (player === mp) player = null
                            runCatching { f.delete() }
                            if (currentFile === f) currentFile = null
                        }
                        start()
                    }
                    currentFile = f
                }.onFailure {
                    player = null
                    currentFile = null
                }
            }
        }
    }

    fun stop() {
        main.post { releasePlayer() }
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
    }
}
