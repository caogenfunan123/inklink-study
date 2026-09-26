package com.inklink.common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单调窗口节流器契约测试。
 *
 * 存在的主要理由不是"测一个 tryAcquire"，而是钉死一条**曾经真实存在的失效模式**：
 * 冷却判据用墙钟时，`now - last < window` 在时钟被往回拨后恒为负 → 恒判"仍在窗口内"
 * → 功能永久静默、日志干净、只能靠重启"自愈"。儿童手表的墙钟由家长 App/网络校时随时可改，
 * 这不是理论风险。注入假时钟后这个场景第一次变得可测。
 */
class MonoThrottleTest {

    private class FakeClock(var t: Long = 0L) {
        fun now(): Long = t
    }

    @Test
    fun `首次调用必放行`() {
        val clock = FakeClock(123_456L)
        val throttle = MonoThrottle(windowMs = 1_500L, timeMs = clock::now)
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `窗口内第二次被拒`() {
        val clock = FakeClock(0L)
        val throttle = MonoThrottle(1_500L, clock::now)
        assertTrue(throttle.tryAcquire())
        clock.t = 1_499L
        assertTrue("窗口内不该放行", !throttle.tryAcquire())
    }

    @Test
    fun `窗口边界恰好放行`() {
        val clock = FakeClock(0L)
        val throttle = MonoThrottle(1_500L, clock::now)
        assertTrue(throttle.tryAcquire())
        clock.t = 1_500L
        assertTrue("恰好到窗口边界应放行", throttle.tryAcquire())
    }

    @Test
    fun `窗口锚定上次放行_被拒不滑动窗口`() {
        val clock = FakeClock(0L)
        val throttle = MonoThrottle(1_000L, clock::now)
        assertTrue(throttle.tryAcquire())
        clock.t = 900L
        assertFalse("仍在窗口内应被拒", throttle.tryAcquire())
        clock.t = 1_000L
        assertTrue("距上次放行满窗口即放行，中途的拒绝不延长窗口", throttle.tryAcquire())
        clock.t = 1_900L
        assertFalse("新窗口从 1000 起算", throttle.tryAcquire())
        clock.t = 2_000L
        assertTrue(throttle.tryAcquire())
    }

    /**
     * 回归钉死：坏时钟（返回值比上次更早）不得让节流器永久卡住。
     * 墙钟版本正是在这里退化成"永久限流"。
     */
    @Test
    fun `时钟回拨不得永久卡住`() {
        val clock = FakeClock(10_000L)
        val throttle = MonoThrottle(600_000L, clock::now)
        assertTrue(throttle.tryAcquire())
        clock.t = 5_000L                     // 模拟墙钟被回拨 5s
        assertTrue("时钟回拨后必须仍能放行，否则功能永久静默", throttle.tryAcquire())
        clock.t = 5_100L
        assertTrue("回拨后窗口以新时刻重算", !throttle.tryAcquire())
    }

    @Test
    fun `零或负窗口永远放行`() {
        val clock = FakeClock(0L)
        val zero = MonoThrottle(0L, clock::now)
        repeat(3) { assertTrue("windowMs=0 等于不限流", zero.tryAcquire()) }
        val negative = MonoThrottle(-5L, clock::now)
        repeat(3) { assertTrue(negative.tryAcquire()) }
    }

    @Test
    fun `reset 清空窗口`() {
        val clock = FakeClock(0L)
        val throttle = MonoThrottle(1_000L, clock::now)
        assertTrue(throttle.tryAcquire())
        assertFalse("窗口内应被拒", throttle.tryAcquire())
        throttle.reset()
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `拒绝时刻与剩余时间可观测`() {
        val clock = FakeClock(0L)
        val throttle = MonoThrottle(1_000L, clock::now)
        assertNull("未拒绝过应为 null", throttle.lastRejectTs)
        assertEquals(0L, throttle.remainingMs())
        assertTrue(throttle.tryAcquire())
        clock.t = 400L
        assertFalse("窗口内应被拒", throttle.tryAcquire())
        assertNotNull(throttle.lastRejectTs)
        assertEquals(600L, throttle.remainingMs())
        clock.t = 1_200L
        assertEquals("窗口已过剩余应为 0", 0L, throttle.remainingMs())
        assertTrue(throttle.tryAcquire())
        assertNull("放行后要清空拒绝标记", throttle.lastRejectTs)
    }

}
