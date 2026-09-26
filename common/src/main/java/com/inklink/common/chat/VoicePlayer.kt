package com.inklink.common.chat

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * 聊天语音播放（AMR）。点击语音气泡时调用 [play]。
 */
object VoicePlayer {

    private var player: MediaPlayer? = null

    fun play(context: Context, data: ByteArray) {
        stop()
        runCatching {
            val f = File(context.cacheDir, "chat_play_${System.currentTimeMillis()}.amr")
            f.writeBytes(data)
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { mp ->
                    mp.release()
                    if (player === mp) player = null
                }
                start()
            }
        }.onFailure {
            player = null
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
