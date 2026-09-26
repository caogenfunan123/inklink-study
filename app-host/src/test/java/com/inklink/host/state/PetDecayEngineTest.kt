package com.inklink.host.state

import com.inklink.common.protocol.payload.PetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双时钟数值引擎单测（V1.1 裁决 #1/#2/#6，纯 JVM 时钟注入）。
 * 覆盖：前台全速 / 后台自动休眠（2026-08-31：四维不掉+精力健康恢复） /
 * Buff 暂停豁免不追缴 / health 侵蚀与虚弱沉睡 / 睡眠恢复 / 分属性余量不丢帧。
 */
class PetDecayEngineTest {

    private fun pet(
        hunger: Int = 80,
        happiness: Int = 80,
        energy: Int = 80,
        clean: Int = 80,
        health: Int = 100,
        sleeping: Boolean = false,
        alive: Boolean = true
    ) = PetItem(
        petId = "test",
        hunger = hunger,
        happiness = happiness,
        energy = energy,
        clean = clean,
        health = health,
        isSleeping = sleeping,
        isAlive = alive,
        lastUpdateTs = 0L
    )

    @Test
    fun `前台 1 小时按佛系节奏缓慢衰减`() {
        val p = pet()
        PetDecayEngine.settle(p, now = 3600_000L, foreground = true)
        // 16/23/20/32h 级：1 小时约掉 6/4/5/3 点，浮点 ±1 内
        assertTrue("hunger=${p.hunger}", p.hunger in 73..75)
        assertTrue("happiness=${p.happiness}", p.happiness in 75..77)
        assertTrue("energy=${p.energy}", p.energy in 74..76)
        assertTrue("clean=${p.clean}", p.clean in 76..78)
        assertEquals(3600_000L, p.lastUpdateTs)
    }

    @Test
    fun `后台自动休眠 60 分钟四维完全不衰减 精力与健康按后台预算恢复`() {
        val p = pet(hunger = 80, happiness = 80, energy = 40, clean = 80, health = 90)
        // 2026-08-31 修订：后台=自动休眠，四维不掉；恢复也按 ÷20 预算
        // budget = 3600s/20 = 180s：精力 +18（1/10s）、健康 +6（1/30s）
        PetDecayEngine.settle(p, now = 60 * 60_000L, foreground = false)
        assertEquals(80, p.hunger)
        assertEquals(80, p.happiness)
        assertEquals(80, p.clean)
        assertEquals(58, p.energy)  // 40 + 18
        assertEquals(96, p.health)  // 90 + 6
        assertEquals(true, remZero(p))
    }

    private fun remZero(p: PetItem) = p.remHunger == 0.0

    @Test
    fun `后台自动休眠整夜八小时也保持全状态`() {
        val p = pet(hunger = 80, happiness = 80, energy = 50, clean = 80, health = 80)
        PetDecayEngine.settle(p, now = 8 * 3600_000L, foreground = false)
        // budget = 8h/20 = 1440s：精力 50+144→100、健康 80+48→100，均封顶
        assertEquals(80, p.hunger)
        assertEquals(80, p.happiness)
        assertEquals(80, p.clean)
        assertEquals(100, p.energy)
        assertEquals(100, p.health)
    }

    @Test
    fun `低于地板的前台数值不受地板保护`() {
        val p = pet(hunger = 25, happiness = 80, energy = 80, clean = 80)
        PetDecayEngine.settle(p, now = 10_000L, foreground = false)
        // 后台=自动休眠，原本偏低的数值保持不变（不抬升也不继续衰减）
        assertEquals(25, p.hunger)
    }

    @Test
    fun `buff_mood 暂停期内开心不衰减 过期后恢复衰减`() {
        val p = pet(happiness = 90)
        // 开心暂停到 t=5min
        p.moodPauseUntilTs = 300_000L
        PetDecayEngine.settle(p, now = 240_000L, foreground = true)
        assertEquals(90, p.happiness) // 暂停期内豁免
        assertEquals(0.0, p.remMood, 1e-9)  // 暂停期不累积余量

        // 到期后推进 1 小时：开心按 23h 级缓慢下滑约 4 点
        PetDecayEngine.settle(p, now = 240_000L + 3600_000L, foreground = true)
        assertTrue("happiness=${p.happiness}", p.happiness in 85..87)  // 90 - ~4
        assertEquals(0L, p.moodPauseUntilTs) // 过期时间戳被清理
    }

    @Test
    fun `buff_energy 暂停只挡衰减不挡睡眠恢复`() {
        val p = pet(energy = 50, sleeping = true)
        p.energyPauseUntilTs = 600_000L
        // 睡眠 100s：+10（1/10s），暂停标志不影响恢复
        PetDecayEngine.settle(p, now = 100_000L, foreground = true)
        assertEquals(60, p.energy)
    }

    @Test
    fun `饥寒交迫时健康侵蚀 health 归零转虚弱沉睡`() {
        val p = pet(hunger = 10, happiness = 80, energy = 80, clean = 80, health = 10)
        // 270s 前台：health -3（1/90s）→ 7；hunger<20 触发侵蚀
        PetDecayEngine.settle(p, now = 270_000L, foreground = true)
        assertEquals(7, p.health)
        // 再 630s：-7 → 归零转虚弱
        PetDecayEngine.settle(p, now = 900_000L, foreground = true)
        assertEquals(0, p.health)
        assertFalse(p.isAlive)
    }

    @Test
    fun `虚弱沉睡后健康冻结不再变化`() {
        val p = pet(hunger = 5, health = 0, alive = false)
        PetDecayEngine.settle(p, now = 600_000L, foreground = true)
        assertEquals(0, p.health)
        assertFalse(p.isAlive)
    }

    @Test
    fun `睡眠同时恢复精力与健康`() {
        val p = pet(energy = 40, health = 70, sleeping = true)
        // 200s：energy +20（1/10s），health +6（1/30s 向下取整）
        PetDecayEngine.settle(p, now = 200_000L, foreground = true)
        assertEquals(60, p.energy)
        assertEquals(76, p.health)
    }

    @Test
    fun `分次 settle 与一次 settle 结果一致 余量不丢帧`() {
        val once = pet(happiness = 80)
        val sliced = pet(happiness = 80)
        // 一次结算 1 小时
        PetDecayEngine.settle(once, now = 3600_000L, foreground = true)
        // 分 60 次、每次 1 分钟
        var now = 0L
        repeat(60) {
            now += 60_000L
            PetDecayEngine.settle(sliced, now, foreground = true)
        }
        assertEquals(once.happiness, sliced.happiness)
    }

    @Test
    fun `重复 settle 同一时间戳幂等无副作用`() {
        val p = pet()
        assertTrue(PetDecayEngine.settle(p, 60_000L, foreground = true))
        val snapshot = p.hunger to p.happiness
        assertFalse(PetDecayEngine.settle(p, 60_000L, foreground = true))
        assertEquals(snapshot, p.hunger to p.happiness)
    }
}
