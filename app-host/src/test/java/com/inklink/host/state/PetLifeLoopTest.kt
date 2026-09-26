package com.inklink.host.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inklink.host.data.PetBagRow
import com.inklink.host.data.PetDatabase
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V1.2 生命闭环 + 成长时间线单测（Robolectric + 真实 Room）：
 * 虚弱沉睡宽限期复活 / 超期离世归档纪念册 / 离世不可治疗 / 重生不继承资产 / 高光时刻沉淀。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PetLifeLoopTest {

    private fun mgr() = PetStateManager(ApplicationProvider.getApplicationContext())

    @Before
    fun resetStore() {
        PetClock.foreground = true
        mgr().wipeForTest()
    }

    @Test
    fun `宽限期内治疗药剂唤醒虚弱宠物并重置离世计时`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.health = 0
        pet.isAlive = false
        pet.lastHealthZeroTs = 0
        m.addItem("potion_heal", 1)
        val (ok, _) = m.heal()
        assertTrue(ok)
        assertTrue(pet.isAlive)
        assertEquals(100, pet.health)
        assertEquals(0L, pet.lastHealthZeroTs)
        assertEquals(0L, pet.passedAtTs)
    }

    @Test
    fun `超宽限期未治疗判定离世并归档纪念册`() {
        val m = mgr()
        val pet = m.getActivePet()
        val birthTs = pet.birthTs
        pet.health = 0
        pet.isAlive = false
        pet.lastHealthZeroTs = System.currentTimeMillis() - 25 * 3_600_000L
        m.recalculateState()
        assertFalse(pet.isAlive)
        assertTrue(pet.passedAtTs > 0)
        val mems = m.listMemorials()
        assertEquals(1, mems.size)
        assertEquals(pet.petId, mems[0].petId)
        assertEquals(birthTs, mems[0].birthTs)
        assertEquals(pet.passedAtTs, mems[0].passedTs)
        assertEquals(pet.passedAtTs - birthTs, mems[0].lifespanMs)
    }

    @Test
    fun `离世后治疗药剂被拒绝`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.health = 0
        pet.isAlive = false
        pet.lastHealthZeroTs = System.currentTimeMillis() - 25 * 3_600_000L
        m.recalculateState()
        assertTrue(pet.passedAtTs > 0)
        val base = m.stockOf("potion_heal")
        m.addItem("potion_heal", 1)
        val (ok, _) = m.heal()
        assertFalse(ok)
        assertFalse(pet.isAlive)
        assertEquals(base + 1, m.stockOf("potion_heal"))
    }

    @Test
    fun `重生生成全新宠物且纪念册保留不继承资产`() {
        val m = mgr()
        val old = m.getActivePet()
        old.health = 0
        old.isAlive = false
        old.lastHealthZeroTs = System.currentTimeMillis() - 25 * 3_600_000L
        m.recalculateState()
        assertTrue(old.passedAtTs > 0)
        m.addItem("food_normal", 5)
        val beforeStock = m.stockOf("food_normal")
        val newPet = m.rebirth()
        assertNotEquals(old.petId, newPet.petId)
        assertTrue(newPet.isAlive)
        assertEquals(0L, newPet.passedAtTs)
        assertEquals(100, newPet.health)
        assertEquals(1, m.listMemorials().size)
        assertEquals(beforeStock, m.stockOf("food_normal"))
        val active = m.getActivePet()
        assertEquals(newPet.petId, active.petId)
    }

    @Test
    fun `高光事件沉淀为时刻而非高光不沉淀`() {
        val m = mgr()
        m.logEvent("CARE", "日常投喂")
        assertEquals(0, m.listMoments().size)
        m.logEvent("LEVEL_UP", "升到了 Lv.2")
        val moments = m.listMoments()
        assertEquals(1, moments.size)
        assertEquals("LEVEL_UP", moments[0].type)
        assertEquals("升到了 Lv.2", moments[0].title)
    }

    @Test
    fun `旧档缺失 moments 字段迁移后访问不崩溃`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dao = PetDatabase.get(ctx).petDao()
        val oldJson = """{"activePetId":"legacy1","petList":[{"petId":"legacy1","name":"旧伙伴"}],"coin":500,"schemaVer":2}"""
        dao.upsertBag(PetBagRow(bagJson = oldJson))
        val m = mgr()
        assertEquals("legacy1", m.getActivePet().petId)
        m.listMoments()
        m.listMemorials()
        m.logEvent("LEVEL_UP", "测试升级")
        assertEquals(1, m.listMoments().size)
    }
}
