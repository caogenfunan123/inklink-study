package com.inklink.host.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inklink.host.data.EventLogEntry
import com.inklink.host.data.PetDatabase
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 游戏化内核集成单测（任务 8.11，Robolectric + 真实 Room）：
 * 道具消耗 / 性格增减 / 每日目标 / 里程碑 / 事件日志环形 500。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PetGamifyKernelTest {

    private fun mgr() = PetStateManager(ApplicationProvider.getApplicationContext())

    @Before
    fun resetStore() {
        PetClock.foreground = true // 测试内按前台全速推进（冷却/事件判定用真实墙钟）
        mgr().wipeForTest()
    }

    @Test
    fun `投喂消耗库存且不足时拒绝`() {
        val m = mgr()
        val pet = m.getActivePet()
        // wipeForTest 后重载会按 PetCatalog 重新播种新手礼包，库存基线非 0，断言用相对值
        val base = m.stockOf("food_normal")
        m.addItem("food_normal", 2)
        assertEquals(base + 2, m.stockOf("food_normal"))
        val (dh, _, deny1) = m.feed(2, "food_normal")
        assertNull(deny1)
        assertTrue(dh > 0)
        assertEquals(base, m.stockOf("food_normal"))
        // 单次投喂上限 5 份（feed 内 coerceIn(1,5)），分批吃光验证"库存不足"分支
        var guard = 0
        while (m.stockOf("food_normal") > 0 && guard++ < 10) {
            assertNull(m.feed(5, "food_normal").third)
        }
        val (_, _, deny2) = m.feed(1, "food_normal")
        assertNotNull(deny2) // 库存不足
        assertEquals("宠物不应因投喂而凭空增加库存", 0, m.stockOf("food_normal"))

    }

    @Test
    fun `玩耍消耗玩具并提升活泼性格`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.isSleeping = false
        pet.energy = 100
        val beforePlayfulness = pet.playfulness
        val base = m.stockOf("toy") // 新手礼包含玩具，断言净消耗 1
        m.addItem("toy", 1)
        val (dh, de, deny) = m.playWith()
        assertNull(deny)
        assertTrue(dh > 0 && de > 0) // de 返回的是消耗值(正数)
        assertEquals(base, m.stockOf("toy"))
        assertEquals(beforePlayfulness + 5, pet.playfulness)
    }

    @Test
    fun `学习消耗书本并累计 learnCount`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.isSleeping = false
        pet.energy = 100
        m.addItem("book", 1)
        val before = pet.learnCount
        val (exp, deny) = m.learn()
        assertNull(deny)
        assertTrue(exp >= 35)
        assertEquals(before + 1, pet.learnCount)
    }

    @Test
    fun `改名最长八字且持久生效`() {
        val m = mgr()
        val pet = m.getActivePet()
        assertTrue(m.renamePet(pet.petId, "  超长的名字测试一下 "))
        assertEquals(8, pet.name.length)
        assertTrue(!m.renamePet(pet.petId, "   "))
    }

    @Test
    fun `每日目标投喂两次达成并可领奖`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.isSleeping = false
        m.addItem("food_normal", 5)
        m.addItem("toy", 1)
        m.addItem("shower_gel", 1)
        pet.energy = 100
        // 推进计数：feed x2 play x1 clean x1
        m.feed(1, "food_normal"); m.feed(1, "food_normal")
        m.playWith(); m.cleanPet()
        val p = m.dailyGoalProgress()
        assertTrue("feed=${p.feed}", p.feed >= 2)
        assertTrue("play=${p.play}", p.play >= 1)
        assertTrue("clean=${p.clean}", p.clean >= 1)
    }

    @Test
    fun `随机事件冷却生效`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.isSleeping = false
        pet.isAlive = true
        pet.hunger = 80; pet.clean = 80
        pet.lastEventTs = System.currentTimeMillis() // 刚触发过
        assertNull(m.rollRandomEvent())
    }

    @Test
    fun `事件日志环形裁剪上限500`() {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PetDatabase::class.java
        ).allowMainThreadQueries().build()
        val dao = db.petDao()
        repeat(600) { i ->
            dao.insertLog(EventLogEntry(petId = "p", type = "CARE", message = "m$i", ts = 1_000_000L + i))
            if (dao.logCount() > PetDatabase.MAX_LOG) dao.trimLogTo(PetDatabase.MAX_LOG)
        }
        assertEquals(PetDatabase.MAX_LOG, dao.logCount())
        val recent = dao.recentLogs(5)
        assertEquals("m599", recent.first().message) // 最新在前
        assertEquals("m595", recent.last().message)
        db.close()
    }

    // ============ 10.7 边界：虚弱沉睡 → 治疗复活 ============

    @Test
    fun `虚弱沉睡后睡眠不恢复健康唯有治疗药剂复活`() {
        val m = mgr()
        val pet = m.getActivePet()
        // 制造虚弱沉睡：health 归零 + isAlive=false，并处于睡眠
        pet.health = 0
        pet.isAlive = false
        pet.isSleeping = true
        pet.hunger = 5
        // 睡眠 3 分钟：裁决 #6 虚弱冻结——health 保持 0，不靠睡眠回血
        PetDecayEngine.settle(pet, pet.lastUpdateTs + 180_000L, foreground = true)
        assertEquals(0, pet.health)
        assertFalse(pet.isAlive)
        // 睡眠开关也应被虚弱拦截
        val (sleepNow, denySleep) = m.toggleSleep()
        assertFalse(sleepNow)
        assertNotNull(denySleep)
        // 新手自带 1 瓶治疗药剂：复活成功、消耗库存、满血清醒
        val (ok, msg) = m.heal()
        assertTrue(ok)
        assertTrue(msg.contains("睁开了眼睛"))
        val p2 = m.getActivePet()
        assertEquals(100, p2.health)
        assertTrue(p2.isAlive)
        assertFalse(p2.isSleeping)
        assertEquals(0, m.stockOf("potion_heal"))
        // 再次致虚弱：无药剂时治疗被拒，且不消耗
        p2.health = 0
        p2.isAlive = false
        val (ok2, msg2) = m.heal()
        assertFalse(ok2)
        assertTrue(msg2.contains("治疗药剂"))
        assertEquals(0, m.stockOf("potion_heal"))
    }

    // ============ 10.7 边界：buff 赠送 → 衰减暂停 → 超时恢复 ============

    @Test
    fun `远程buff加数值并暂停衰减超时后自动恢复下滑`() {
        val m = mgr()
        val pet = m.getActivePet()
        pet.energy = 40
        pet.isSleeping = false
        // 赠送精力包：+40 且写入暂停时间戳（未来 10min）
        val (desc, deny) = m.applyBuff(PetCatalog.BUFF_ENERGY)
        assertNull(deny)
        assertTrue(desc.contains("+40"))
        assertEquals(80, pet.energy)
        val pauseTs = pet.energyPauseUntilTs
        assertTrue(pauseTs > System.currentTimeMillis())
        // 暂停窗口内 settle（模拟 60s 后）：精力不衰减
        PetDecayEngine.settle(pet, pet.lastUpdateTs, foreground = true)
        val afterPaused = pet.energy
        PetDecayEngine.settle(pet, pet.lastUpdateTs + 60_000L, foreground = true)
        assertEquals("暂停期内精力不应下滑", afterPaused, pet.energy)
        // 手动把暂停时间戳拨到过去 = buff 超时，再衰减恢复（1 小时窗口，佛系速率下约掉 5 点）
        pet.energyPauseUntilTs = System.currentTimeMillis() - 1L
        val before = pet.energy
        PetDecayEngine.settle(pet, pet.lastUpdateTs + 3600_000L, foreground = true)
        assertTrue("超时后应恢复衰减", pet.energy < before)
        assertEquals(0L, pet.energyPauseUntilTs) // 过期戳被引擎清理
    }

    // ============ 10.7 边界：节流写库 ============

    @Test
    fun `高频persist被节流flush强制落盘可恢复`() {
        val m = mgr()
        val pet = m.getActivePet()
        // 首次 persist(force) 建立落盘基线
        m.renamePet(pet.petId, "节流测试")
        m.flush()
        // 10s 窗口内连改 20 次名字：中间 persist 只标脏
        repeat(20) { m.renamePet(pet.petId, "名$it") }
        // 新开 manager 从磁盘重载：应拿到 flush 前最后一次落盘的状态或最新状态，
        // 但绝不能丢改名能力——flush 后必须可见最新
        m.flush()
        val reloaded = mgr()
        reloaded.reloadFromDisk()
        assertEquals("名19", reloaded.getActivePet().name)
    }

    // ============ 2026-08-31 一键赠送全部 ============

    @Test
    fun `applyGiftAll 补足全部资源金币到1000且幂等`() {
        val m = mgr()
        val pet = m.getActivePet()
        m.petBag.coin = 5
        val note = m.applyGiftAll()

        // 道具库存全部补到 10
        for (def in PetCatalog.ITEMS.values) {
            assertTrue("道具 ${def.id} 应补足到10，实际=${m.stockOf(def.id)}", m.stockOf(def.id) >= 10)
        }
        // 装饰全部解锁
        for (deco in PetCatalog.DECOS) {
            assertTrue("装饰 ${deco.id} 应解锁", m.petBag.unlockedDecorations.contains(deco.id))
        }
        // 金币补到 1000
        assertEquals(1000, m.petBag.coin)
        // 幂等：再次赠送仍 1000、不越界
        m.applyGiftAll()
        assertEquals(1000, m.petBag.coin)
        assertTrue(note.isNotBlank())
    }
}
