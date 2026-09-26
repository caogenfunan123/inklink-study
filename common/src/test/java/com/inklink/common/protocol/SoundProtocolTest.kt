package com.inklink.common.protocol

import com.inklink.common.protocol.payload.PetEventPayload
import com.inklink.common.protocol.payload.PlaySoundPayload
import com.inklink.common.protocol.payload.SoundProtocol
import com.inklink.common.protocol.payload.SoundProtocol.EVENT_IDS
import com.inklink.common.protocol.payload.SoundProtocol.RAW_NAMES
import com.inklink.common.protocol.payload.SoundProtocol.SOUND_IDS
import com.inklink.common.protocol.payload.SoundProtocol.bindingFor
import com.inklink.common.protocol.payload.SoundProtocol.clamp
import com.inklink.common.protocol.payload.SoundProtocol.sanitizeTts
import com.inklink.common.protocol.payload.SoundProtocol.shouldSpeak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 互动音频协议契约（《音频系统 Final-Rev1》§二/§四/§八）。
 *
 * 这里守的是"双端共用的那张表"：白名单、ID→业务绑定、零信任文本截断。
 * 三条最容易被改坏的路径都有断言：
 *  - 有人加 eventId 但忘了放 wav → `RAW_NAMES` 与受控端 res/raw 契约测试（另一个模块）同时红；
 *  - 有人把 eventId 直接映射到一套新数值结算 → `action` 取值域断言红；
 *  - 有人拿 emoji 文案测截断 → 代理对被切半的断言红。
 */
class SoundProtocolTest {

    @Test
    fun `码位 22 23 已登记且不与历史协议冲突`() {
        assertEquals(MessageType.CMD_PLAY_SOUND, MessageType.fromCode(22))
        assertEquals(MessageType.CMD_PET_EVENT, MessageType.fromCode(23))
        // 21=PUSH_ALERT、30=PET_STATE_SYNC，22/23 必须落在两者之间的空档
        assertEquals(21, MessageType.PUSH_ALERT.code)
        assertEquals(30, MessageType.PET_STATE_SYNC.code)
    }

    @Test
    fun `系统提示音白名单为文档 §二 的 4 个`() {
        assertEquals(setOf("alert_call", "alert_notify", "alert_warn", "sound_ack"), SOUND_IDS)
    }

    @Test
    fun `事件白名单为文档 §二 的 6 个`() {
        assertEquals(
            setOf(
                "event_feed", "event_touch", "event_happy",
                "event_hungry_alert", "event_sleep", "event_wakeup"
            ),
            EVENT_IDS
        )
    }

    @Test
    fun `raw 名集合等于系统音效加事件音效`() {
        assertEquals(10, RAW_NAMES.size)
        assertEquals(SOUND_IDS + setOf(
            "pet_feed", "pet_touch", "pet_happy", "pet_hungry", "pet_sleep", "pet_wakeup"
        ), RAW_NAMES)
    }

    @Test
    fun `每个事件绑定的音效都在 raw 契约内`() {
        EVENT_IDS.forEach { id ->
            val b = bindingFor(id)
            assertTrue("$id 无绑定", b != null)
            assertTrue("$id 绑定的音效 ${b?.soundId} 不在 RAW_NAMES", b!!.soundId in RAW_NAMES)
        }
    }

    @Test
    fun `事件的 action 只能复用 31 号指令的既有取值域`() {
        // 23 的结算走 31 的同一核心，所以 action 必须落在受控端已认识的集合里，
        // 否则新事件会在受控端被静默当成"未知动作 → 抚摸"（when 的 else 分支）。
        val allowed = setOf("FEED", "PLAY", "CLEAN", "SLEEP", "LEARN", "HEAL", "TOUCH")
        EVENT_IDS.forEach { id ->
            val act = bindingFor(id)?.action
            if (act != null) {
                assertTrue("$id 的 action=$act 不在 31 号指令取值域", act in allowed)
            }
        }
    }

    @Test
    fun `sleeping 目标态只对 SLEEP 类事件有意义`() {
        EVENT_IDS.forEach { id ->
            val b = bindingFor(id)!!
            if (b.sleeping != null) {
                assertEquals("$id 设置了 sleeping 但 action 不是 SLEEP", "SLEEP", b.action)
            }
        }
        assertEquals(true, bindingFor("event_sleep")?.sleeping)
        assertEquals(false, bindingFor("event_wakeup")?.sleeping)
    }

    @Test
    fun `受控端上报型事件不得携带状态结算`() {
        // 否则主控端一次弹窗误点就会把孩子的饥饿值改两次
        val reported = listOf("event_hungry_alert")
        reported.forEach { id ->
            val b = bindingFor(id)
            assertTrue("$id 必须是 hostReported", b!!.hostReported)
            assertNull("$id 不该改变数值", b.action)
        }
        EVENT_IDS.filter { it !in reported }.forEach {
            assertTrue("$it 不该标成 hostReported（会被受控端当回声丢弃）", !bindingFor(it)!!.hostReported)
        }
    }

    @Test
    fun `告警音效集合是系统音效子集`() {
        assertTrue(SoundProtocol.ALARM_SOUND_IDS.all { it in SOUND_IDS })
        assertTrue(SoundProtocol.isAlarmSound("alert_warn"))
        assertFalse("宠物音效不得抢告警通道", SoundProtocol.isAlarmSound("pet_feed"))
    }

    @Test
    fun `未知ID一律判非法`() {
        assertFalse(SoundProtocol.isKnownSound(""))
        assertFalse(SoundProtocol.isKnownSound("ALERT_CALL"))   // 大小写敏感：文件名就是契约
        assertFalse(SoundProtocol.isKnownSound("alert_call/../pet_feed"))
        assertNull(bindingFor("event_hack"))
        assertNull(bindingFor(""))
    }

    @Test
    fun `播报开关与文本共同决定是否朗读`() {
        assertTrue(shouldSpeak(true, "我饿了"))
        assertFalse("开关关 → 不朗读", shouldSpeak(false, "我饿了"))
        assertFalse("空文本 → 不朗读", shouldSpeak(true, "   "))
        assertFalse(shouldSpeak(true, null))
        assertFalse("全是控制字符 → 清洗后为空", shouldSpeak(true, "\u0000\u0001\n"))
    }

    @Test
    fun `网络文本清洗与长度截断`() {
        assertEquals("", sanitizeTts(null))
        assertEquals("你好", sanitizeTts("  你好 \n"))
        assertEquals("a b", sanitizeTts("a\nb"))          // 换行折成空格，朗读时不会吞字
        assertEquals("ab", sanitizeTts("a\u0000b\u007F")) // 控制字符直接丢
        val long = "字".repeat(SoundProtocol.TTS_TEXT_MAX_LEN + 30)
        assertEquals(SoundProtocol.TTS_TEXT_MAX_LEN, sanitizeTts(long).length)
    }

    @Test
    fun `截断不得把emoji的代理对切成半个`() {
        val text = "啊".repeat(SoundProtocol.TTS_TEXT_MAX_LEN - 1) + "😊!"
        val cut = sanitizeTts(text)
        assertEquals(
            "末尾出现孤立代理项会让 TTS 引擎乱码或抛异常",
            false,
            Character.isHighSurrogate(cut.last())
        )
        // clamp 单独验一次边界：正好切在高代理上
        val pair = "x".repeat(5) + "😊"
        assertEquals("xxxxx", clamp(pair, 6))
        assertEquals("xxxxx😊", clamp(pair, 7))
    }

    @Test
    fun `payload 编解码往返与旧端默认值兼容`() {
        val codec = MessageCodec
        val sound = PlaySoundPayload(soundId = "alert_call", ttsText = "主人我呼叫你", enableTts = true)
        val msg = InkMessage.text(
            MessageType.CMD_PLAY_SOUND,
            com.google.gson.Gson().toJson(sound),
            from = "ctl-1", target = "host-1"
        )
        val back = codec.decodeText(codec.encodeText(msg))
        assertEquals(MessageType.CMD_PLAY_SOUND.code, back.type)
        val p = com.google.gson.Gson().fromJson(back.payload, PlaySoundPayload::class.java)
        assertEquals(sound, p.copy(timestamp = sound.timestamp))

        // 旧端（不带新字段）发来的包：Gson 反序列化后走默认值，不得抛异常
        val legacy = com.google.gson.Gson().fromJson(
            """{"eventId":"event_feed"}""",
            PetEventPayload::class.java
        )
        assertEquals("event_feed", legacy.eventId)
        assertEquals("", legacy.ttsText)
        assertFalse(legacy.enableTts)
    }
}
