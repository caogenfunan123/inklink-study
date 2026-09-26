package com.inklink.common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幂等控制器契约测试。
 *
 * 本类是全 App 入站指令去重的**唯一真相源**（`InkForegroundService` 的两处守卫都建在它上面：
 * msgId 去重 + CMD_PET_EVENT(23) 的 `eventId@triggerTs` 语义守卫），此前零测试。
 * 时钟已参数化，才第一次能确定性地测到 TTL 边界与容量淘汰。
 *
 * 重点钉死三条：
 *  1. 重复必吞、窗口边界必放行（业务幂等的前提）；
 *  2. **时钟异常不得制造不死条目**——墙钟版本里 `now - existTime` 为负会同时骗过
 *     TTL 判定与 `cleanExpired`，导致该 msgId 永久被吞、日志干净、只有重启才恢复；
 *  3. 容量淘汰只淘汰最旧，不影响仍在窗口内的新消息。
 */
class IdempotentControllerTest {

    private class FakeClock(var t: Long = 0L) {
        fun now(): Long = t
    }

    private fun controller(maxCapacity: Int = 50, ttlMs: Long = 60_000L, clock: FakeClock) =
        IdempotentController(maxCapacity = maxCapacity, ttlMs = ttlMs, timeMs = clock::now)

    @Test
    fun `新消息放行_重复消息吞掉`() {
        val clock = FakeClock(1_000L)
        val c = controller(clock = clock)
        assertTrue(c.checkAndRecord("msg-1"))
        assertFalse("同 msgId 二次到达必须判重复", c.checkAndRecord("msg-1"))
        assertTrue("不同 msgId 互不影响", c.checkAndRecord("msg-2"))
        assertFalse(c.checkAndRecord("msg-2"))
    }

    @Test
    fun `空msgId不去重`() {
        val clock = FakeClock(0L)
        val c = controller(clock = clock)
        // 无 ID 的消息没有去重依据，一律放行（丢消息比重复执行更糟）
        repeat(3) {
            assertTrue(c.checkAndRecord(null))
            assertTrue(c.checkAndRecord(""))
            assertTrue(c.checkAndRecord("   "))
        }
        assertEquals("空 ID 不应占用容量", 0, c.size())
    }

    @Test
    fun `TTL边界`() {
        val clock = FakeClock(0L)
        val c = controller(ttlMs = 10_000L, clock = clock)
        assertTrue(c.checkAndRecord("msg"))
        clock.t = 9_999L
        assertFalse("窗口内仍算重复", c.checkAndRecord("msg"))
        clock.t = 10_000L
        assertTrue("恰好到 TTL 视为过期，放行并重记", c.checkAndRecord("msg"))
        clock.t = 19_999L
        assertFalse("过期重记后重新计时", c.checkAndRecord("msg"))
    }

    /**
     * 回归钉死（原墙钟实现的真实故障模式）：时钟返回值早于记录时刻时，
     * 条目必须判为过期并放行，绝不能"永久重复"。
     */
    @Test
    fun `时钟回拨不得吞掉后续消息`() {
        val clock = FakeClock(60_000L)
        val c = controller(ttlMs = 60_000L, clock = clock)
        assertTrue(c.checkAndRecord("msg-a"))
        clock.t = 10L                                     // 时间被往回调
        assertTrue("回拨后同 msgId 必须可再次执行，否则指令永久失效", c.checkAndRecord("msg-a"))
        clock.t = 20L
        assertFalse("回拨后以新时刻重算窗口", c.checkAndRecord("msg-a"))
    }

    @Test
    fun `过期条目被清理不占容量`() {
        val clock = FakeClock(0L)
        val c = controller(maxCapacity = 50, ttlMs = 1_000L, clock = clock)
        repeat(10) { c.checkAndRecord("old-$it") }
        assertEquals(10, c.size())
        clock.t = 5_000L
        assertTrue(c.checkAndRecord("fresh"))
        assertEquals("过期条目应被顺带清掉", 1, c.size())
    }

    @Test
    fun `容量淘汰只淘汰最旧`() {
        val clock = FakeClock(0L)
        val c = controller(maxCapacity = 2, ttlMs = 100_000L, clock = clock)
        assertTrue(c.checkAndRecord("a"))
        clock.t = 1L
        assertTrue(c.checkAndRecord("b"))
        clock.t = 2L
        assertTrue(c.checkAndRecord("c"))                 // 触发淘汰最旧的 a
        assertEquals(2, c.size())
        assertFalse("b 仍在窗口内，不该被 a 的淘汰波及", c.checkAndRecord("b"))
        assertTrue("a 已因容量淘汰被放行", c.checkAndRecord("a"))
    }

    @Test
    fun `clear 复位全部窗口`() {
        val clock = FakeClock(0L)
        val c = controller(clock = clock)
        c.checkAndRecord("a"); c.checkAndRecord("b")
        assertFalse(c.checkAndRecord("a"))
        c.clear()
        assertEquals(0, c.size())
        assertTrue(c.checkAndRecord("a"))
        assertTrue(c.checkAndRecord("b"))
    }

    /**
     * 语义守卫的用法：eventId@triggerTs 作 key。
     * 同一逻辑事件（同 triggerTs）重投必吞；用户连点两次（triggerTs 不同）必放行。
     * 这条断言保护的是我在 CMD_PET_EVENT 里定的幂等口径，最容易被"顺手改成只按 eventId 去重"改坏。
     */
    @Test
    fun `语义守卫键口径_重投吞_连点放`() {
        val clock = FakeClock(0L)
        val guard = controller(maxCapacity = 64, ttlMs = 10_000L, clock = clock)
        assertTrue(guard.checkAndRecord("event_feed@1000"))
        clock.t = 2_000L
        assertFalse("同事件重投应吞掉", guard.checkAndRecord("event_feed@1000"))
        assertTrue("用户连点（新 triggerTs）不得被误杀", guard.checkAndRecord("event_feed@2000"))
        clock.t = 3_000L
        assertFalse("不同事件互不影响", guard.checkAndRecord("event_feed@1000"))
        assertTrue(guard.checkAndRecord("event_play@3000"))
    }
}
