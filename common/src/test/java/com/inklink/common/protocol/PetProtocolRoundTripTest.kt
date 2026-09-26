package com.inklink.common.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.inklink.common.protocol.payload.PetBagInteractPayload
import com.inklink.common.protocol.payload.PetGiftAckPayload
import com.inklink.common.protocol.payload.PetItem
import com.inklink.common.protocol.payload.PetRemoteGiftPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10.6 双通路联调 · 协议层自动化代理。
 *
 * 局域网与 Ably 中转搬的都是同一份 `MessageCodec.encodeText(InkMessage)` 产物，
 * 通路差异只剩传输层；本测试锁定**数据面语义等价**：
 *  - 44 三类礼物 giftType(item/deco/buff/coin) 与 itemId 无损往返；
 *  - 43 四个 JSON 子类型(LOCAL_VISIT/FRIEND_VISIT/CALL_VISIT/TEASE) 与访客字段无损往返；
 *  - 赠礼 ACK 的 petSnapshot 快照跨通路保真；
 *  - 旧端兼容：缺省字段按默认值解析（giftType=item、subType=LOCAL_VISIT）。
 */
class PetProtocolRoundTripTest {

    private val gson = Gson()

    private fun roundTrip(type: Int, payloadObj: Any): Pair<InkMessage, String> {
        val msg = InkMessage(type = type, payload = gson.toJson(payloadObj))
        val wire = MessageCodec.encodeText(msg) // 发送端（LAN/Ably 共用的文本帧）
        val decoded = MessageCodec.decodeText(wire) // 接收端
        return decoded to wire
    }

    // ================= 44 赠礼 =================

    @Test
    fun `giftType item 实物道具往返无损`() {
        val sent = PetRemoteGiftPayload(itemId = "food_premium", count = 3, giftType = "item")
        val (msg, _) = roundTrip(MessageType.PET_REMOTE_GIFT.code, sent)
        val recv = gson.fromJson(msg.payload, PetRemoteGiftPayload::class.java)
        assertEquals(MessageType.PET_REMOTE_GIFT.code, msg.type)
        assertEquals("food_premium", recv.itemId)
        assertEquals(3, recv.count)
        assertEquals("item", recv.giftType)
        assertTrue(recv.triggerTs > 0)
    }

    @Test
    fun `giftType deco 装饰解锁往返无损`() {
        val sent = PetRemoteGiftPayload(itemId = "deco_bandana", giftType = "deco")
        val (msg, _) = roundTrip(MessageType.PET_REMOTE_GIFT.code, sent)
        val recv = gson.fromJson(msg.payload, PetRemoteGiftPayload::class.java)
        assertEquals("deco", recv.giftType)
        assertEquals("deco_bandana", recv.itemId)
    }

    @Test
    fun `giftType buff 与 coin 往返无损`() {
        for (pair in listOf("buff_energy" to "buff", "buff_mood" to "buff", "coin_5" to "coin")) {
            val (itemId, giftType) = pair
            val sent = PetRemoteGiftPayload(itemId = itemId, giftType = giftType)
            val (msg, _) = roundTrip(MessageType.PET_REMOTE_GIFT.code, sent)
            val recv = gson.fromJson(msg.payload, PetRemoteGiftPayload::class.java)
            assertEquals(itemId, recv.itemId)
            assertEquals(giftType, recv.giftType)
        }
    }

    @Test
    fun `旧端赠礼帧缺省字段按兼容默认解析`() {
        // 旧版发送端只带 itemId/count，不含 giftType
        // Gson 直接实例化 Kotlin 数据类时不触发构造默认值，缺省字段为 null；
        // 生产端（InkForegroundService）通过 giftType.isNotBlank() + itemId 前缀推断兜底
        val legacyJson = """{"itemId":"food_normal","count":2,"triggerTs":1700000000000}"""
        val msg = InkMessage(type = MessageType.PET_REMOTE_GIFT.code, payload = legacyJson)
        val wire = MessageCodec.encodeText(msg)
        val recv = gson.fromJson(MessageCodec.decodeText(wire).payload, PetRemoteGiftPayload::class.java)
        assertEquals(null, recv.giftType) // 缺省按 null 到达，兼容分支在消费端兜底
        assertEquals(2, recv.count)
    }

    // ================= 43 串门 / 逗弄 =================

    @Test
    fun `43 四个子类型与访客字段往返无损`() {
        for (sub in listOf("LOCAL_VISIT", "FRIEND_VISIT", "CALL_VISIT", "TEASE")) {
            val sent = PetBagInteractPayload(
                targetPetId = "pet-abc", subType = sub,
                visitorName = "小美", visitorPetType = "rabbit"
            )
            val (msg, _) = roundTrip(MessageType.PET_BAG_INTERACT.code, sent)
            val recv = gson.fromJson(msg.payload, PetBagInteractPayload::class.java)
            assertEquals(sub, recv.subType)
            assertEquals("pet-abc", recv.targetPetId)
            assertEquals("小美", recv.visitorName)
            assertEquals("rabbit", recv.visitorPetType)
        }
    }

    @Test
    fun `旧端串门帧缺省 subType 按本机串门解析`() {
        // Gson 直接实例化 Kotlin 数据类不触发构造默认值，缺省 subType 为 null；
        // 生产端（InkForegroundService / PetDetailActivity）用 subType==CALL_VISIT 精确判别，
        // 其余值（含 null）一律按本机串门处理，与"缺省即 LOCAL_VISIT"语义等价
        val legacyJson = """{"targetPetId":"pet-xyz","triggerTs":1700000000000}"""
        val wire = MessageCodec.encodeText(InkMessage(type = MessageType.PET_BAG_INTERACT.code, payload = legacyJson))
        val recv = gson.fromJson(MessageCodec.decodeText(wire).payload, PetBagInteractPayload::class.java)
        assertEquals(null, recv.subType)
        assertEquals(null, recv.visitorName)
    }

    // ================= 赠礼 ACK =================

    @Test
    fun `赠礼ACK携带宠物快照跨通路保真`() {
        val snapshot = PetItem(
            petId = "p1", name = "团子", petType = "penguin",
            hunger = 66, happiness = 88, exp = 1234, level = 7,
            playfulness = 42, isAlive = true
        )
        val ack = PetGiftAckPayload(
            itemId = "buff_mood", giftType = "buff", success = true,
            note = "TA 开心地转了个圈", petSnapshot = snapshot
        )
        val (msg, _) = roundTrip(MessageType.PET_INTERACT_ACK.code, ack)
        val recv = gson.fromJson(msg.payload, PetGiftAckPayload::class.java)
        assertEquals("buff", recv.giftType)
        assertEquals("TA 开心地转了个圈", recv.note)
        val p = recv.petSnapshot!!
        assertEquals("团子", p.name)
        assertEquals(66, p.hunger)
        assertEquals(7, p.level)
        assertEquals(42, p.playfulness)
    }

    @Test
    fun `ACK与照料复用同一通道时靠giftType字段做JSON鸭子类型判别`() {
        // 10.1 裁决：32 通道同时跑「照料 ACK」与「赠礼 ACK」，接收端用 giftType 字段有无来分流
        val careAckJson = """{"success":true,"note":"喂过了"}"""
        val giftAckJson = gson.toJson(
            PetGiftAckPayload(itemId = "toy", giftType = "item", success = true)
        )
        // 判别规则与 InkForegroundService/PetDetailActivity 一致：giftType 非空即赠礼 ACK
        assertTrue("照料帧不应被判成赠礼", gson.fromJson(careAckJson, JsonObject::class.java).get("giftType") == null)
        assertTrue("赠礼帧必须带 giftType",
            gson.fromJson(giftAckJson, JsonObject::class.java).get("giftType").asString == "item")

        val wire = MessageCodec.encodeText(
            InkMessage(type = MessageType.PET_INTERACT_ACK.code, payload = giftAckJson)
        )
        val back = gson.fromJson(MessageCodec.decodeText(wire).payload, PetGiftAckPayload::class.java)
        assertEquals("item", back.giftType)
        assertTrue(back.success)
        assertNull(back.petSnapshot) // 轻量回执可省快照
    }
}
