package com.inklink.host.audio

import com.inklink.common.protocol.payload.SoundProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * res/raw 预制音效资产契约（《音频系统 Final-Rev1》§二 的机械化落地）。
 *
 * 与 [com.inklink.host.state.PetSpriteAssetContractTest] 同一思路：合成器
 * `tools/pet_audio/generate_sfx.py` 自检是一层，CI 是第二层——两层用**同一份真值**
 * （`SoundProtocol.RAW_NAMES`），任何一侧偷偷加/删音效都会立刻红。
 *
 * 为什么用 `SoundProtocol` 而不是在本测试里再抄一遍清单：抄一份就意味着「协议白名单」和
 * 「磁盘资产」可以各自演化很久都不报错，直到某个 soundId 在设备上静默没声音。
 *
 * WAV 解析手写（android.jar 剥离了 javax.sound.*，平台限制不是偷懒）。
 */
class RawSoundContractTest {

    private fun rawDir(): File {
        val candidates = listOf(
            File("src/main/res/raw"),
            File("app-host/src/main/res/raw")
        )
        return candidates.firstOrNull { it.isDirectory } ?: candidates[0]
    }

    @Test
    fun `音效文件集合与协议白名单严格相等`() {
        val dir = rawDir()
        assertTrue("找不到 res/raw 目录（音效整目录缺失？）: ${dir.path}", dir.isDirectory)
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: emptyArray())
            .sortedBy { it.name }
        val got = files.map { it.nameWithoutExtension }.toSet()
        val want = SoundProtocol.RAW_NAMES
        val missing = want - got
        val extra = got - want
        assertTrue(
            "音效集合与契约不一致 缺=[${missing.joinToString()}] 多=[${extra.joinToString()}]" +
                "（多出来的是陈旧资产会白占 APK；缺了会静默没声音）",
            missing.isEmpty() && extra.isEmpty()
        )
        assertEquals("契约音效数应为 10（系统 4 + 宠物 6）", 10, got.size)
    }

    @Test
    fun `每个音效都是16bit单声道44100且时长与峰值合规`() {
        val dir = rawDir()
        val problems = StringBuilder()
        for (id in SoundProtocol.RAW_NAMES.sorted()) {
            val f = File(dir, "$id.wav")
            if (!f.isFile) {
                problems.append("\n$id: 文件不存在")
                continue
            }
            val parsed = runCatching { Wav(f.readBytes()) }
            if (parsed.isFailure) {
                problems.append("\n$id: 解析失败 ${parsed.exceptionOrNull()?.message}")
                continue
            }
            val w = parsed.getOrThrow()
            fun bad(msg: String) { problems.append("\n$id: $msg") }
            if (w.audioFormat != 1) bad("必须是未压缩 PCM(fmt=1)，实测 ${w.audioFormat}（Android 不认 ADPCM/float）")
            if (w.channels != 1) bad("必须单声道，实测 ${w.channels}")
            if (w.sampleRate != 44100) bad("必须 44100Hz，实测 ${w.sampleRate}")
            if (w.bitsPerSample != 16) bad("必须 16bit，实测 ${w.bitsPerSample}")
            val dur = w.seconds
            if (dur < 0.5 - 1e-3 || dur > 1.5 + 1e-3) bad("时长须 0.5~1.5s，实测 %.3fs".format(dur))
            if (abs(w.peak - 0.891) > 0.02) bad("峰值未统一标准化，实测 %.3f（目标 0.891）".format(w.peak))
            if (abs(w.dcv) > 0.01) bad("直流偏移 %.4f（会啃功放且推流有嗡声）".format(w.dcv))
            if (w.tailSilenceMs > 30.0) bad("尾部静音空白 %.0fms（违反无尾音空白）".format(w.tailSilenceMs))
            if (w.leadSilenceMs > 5.0) bad("首部静音垫 %.0fms".format(w.leadSilenceMs))
            if (w.clippedFrames > 0) bad("削顶样本 ${w.clippedFrames} 个（响度归一化过头）")
        }
        assertEquals("res/raw 音效不合规范：", "", problems.toString().trim())
    }

    @Test
    fun `白名单里的每个soundId都有编译期R_raw常量`() {
        // 防的是：加了协议 ID 忘了放文件，或文件名大小写打错。R.raw 字段是 aapt 按文件名生成的，
        // 反射查一遍就等于把"文档里的名字"和"代码里 when 的分支"钉在一起。
        val fields = com.inklink.host.R.raw::class.java.fields.associate { it.name to it.getInt(null) }
        val missing = SoundProtocol.RAW_NAMES.filter { fields[it] == null || fields[it] == 0 }
        assertTrue("R.raw 中缺失音效常量：${missing.joinToString()}", missing.isEmpty())
    }

    @Test
    fun `告警类音效必须落在系统提示音集合内`() {
        val alarm = SoundProtocol.ALARM_SOUND_IDS
        assertTrue("告警 ID 未白名单化：${alarm}", alarm.all { it in SoundProtocol.RAW_NAMES })
        assertNotNull("alert_warn 必须存在（强控告警可打断 TTS 的载体）", SoundProtocol.RAW_NAMES.firstOrNull { it == "alert_warn" })
    }

    /** 极简 RIFF/WAVE 解析：只取 fmt/data 两个 chunk，够做规范校验。 */
    private class Wav(bytes: ByteArray) {
        val audioFormat: Int
        val channels: Int
        val sampleRate: Int
        val bitsPerSample: Int
        val peak: Double
        val dcv: Double
        val seconds: Double
        val leadSilenceMs: Double
        val tailSilenceMs: Double
        val clippedFrames: Int

        init {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "非 RIFF/WAVE" }
            var pos = 12
            var fmtSeen = false
            var dataStart = -1
            var dataLen = 0
            var a = 0; var c = 0; var r = 0; var b = 0
            while (pos + 8 <= bb.limit()) {
                val id = String(bytes, pos, 4)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        a = bb.getShort(body).toInt() and 0xFFFF
                        c = bb.getShort(body + 2).toInt() and 0xFFFF
                        r = bb.getInt(body + 4)
                        b = bb.getShort(body + 14).toInt() and 0xFFFF
                        fmtSeen = true
                    }
                    "data" -> { dataStart = body; dataLen = size }
                }
                pos = body + size + (size % 2)   // chunk 按偶数字节对齐
            }
            require(fmtSeen) { "缺 fmt chunk" }
            require(dataStart > 0 && dataLen > 0) { "缺 data chunk（空音频？）" }
            audioFormat = a; channels = c; sampleRate = r; bitsPerSample = b

            val frameBytes = c * (b / 8)
            val frames = minOf(dataLen / frameBytes, (bytes.size - dataStart) / frameBytes)
            require(frames > 0) { "data 里没有完整帧" }
            var mx = 0.0
            var sum = 0.0
            var clipped = 0
            var firstLoud = -1
            var lastLoud = -1
            val floor = 0.002
            for (i in 0 until frames) {
                val v = bb.getShort(dataStart + i * frameBytes).toInt()   // 只看第一声道
                val d = v / 32768.0
                val ad = abs(d)
                if (ad > mx) mx = ad
                sum += d
                if (v == Short.MIN_VALUE.toInt() || v == 32767) clipped++
                if (ad > floor) {
                    if (firstLoud < 0) firstLoud = i
                    lastLoud = i
                }
            }
            peak = mx
            dcv = if (frames > 0) sum / frames else 0.0
            seconds = frames.toDouble() / r
            clippedFrames = clipped
            leadSilenceMs = if (firstLoud < 0) 0.0 else firstLoud * 1000.0 / r
            tailSilenceMs = if (lastLoud < 0) 0.0 else (frames - 1 - lastLoud) * 1000.0 / r
        }
    }
}
