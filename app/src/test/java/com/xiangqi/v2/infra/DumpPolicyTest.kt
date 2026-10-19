package com.xiangqi.v2.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 节流策略的边界：这组数字决定了「用户复现一次能不能拿到图」。
 *
 * 真机上没法验证「第 6 张不存」—— 单测是唯一能钉住它的地方。
 */
class DumpPolicyTest {

    @Test
    fun `首张立即通过`() {
        val policy = DumpPolicy(maxCount = 5, intervalMs = 8_000)
        assertTrue(policy.tryTake(1_000L))
        assertEquals(1, policy.savedCount)
    }

    @Test
    fun `间隔内拒绝，过了间隔才通过`() {
        val policy = DumpPolicy(maxCount = 5, intervalMs = 8_000)
        assertTrue(policy.tryTake(0L))
        assertFalse("7.9 秒还不够", policy.tryTake(7_999L))
        assertTrue(policy.tryTake(8_000L))
        assertEquals(2, policy.savedCount)
    }

    @Test
    fun `间隔再大也不超过上限`() {
        val policy = DumpPolicy(maxCount = 3, intervalMs = 1_000)
        assertTrue(policy.tryTake(0L))
        assertTrue(policy.tryTake(1_000L))
        assertTrue(policy.tryTake(2_000L))
        assertFalse("第 4 张超上限", policy.tryTake(100_000L))
        assertEquals(3, policy.savedCount)
    }

    @Test
    fun `reset 之后重新计数`() {
        val policy = DumpPolicy(maxCount = 1, intervalMs = 1_000)
        assertTrue(policy.tryTake(0L))
        assertFalse(policy.tryTake(10_000L))
        policy.reset()
        assertTrue(policy.tryTake(10_000L))
    }

    /**
     * 手动识别与盯盘循环会**同时**报失败：判定和记账必须是一个原子操作，
     * 否则两边都通过判定，一次失败存两张、八秒的间隔白设。
     */
    @Test
    fun `并发调用不会超发`() {
        // 间隔设 0：这条只测「上限的原子性」，别让时间抖动把用例变成偶发失败
        val policy = DumpPolicy(maxCount = 2, intervalMs = 0)
        val passed = AtomicInteger()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (policy.tryTake(System.currentTimeMillis())) passed.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(2, passed.get())
        assertEquals(2, policy.savedCount)
    }
}
