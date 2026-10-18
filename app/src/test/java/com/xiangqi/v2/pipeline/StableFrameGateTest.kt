package com.xiangqi.v2.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定帧门槛的边界。
 *
 * 这组测试的重点不是「两帧相同就放行」这条顺利路径，而是**抖动兜底**：
 * 门槛凑不满时如果什么都不做，局面会永远停在「画面变化中」，而界面上看不出原因。
 * 第 4、5、6 条守着这一侧，其中第 5 条是这次改动最容易写错的地方 ——
 * 「换指纹就重置计时」会让兜底永远触发不了，看起来代码写了、实际等于没写。
 */
class StableFrameGateTest {

    private fun gate(required: Int = 2, stallMs: Long = 3000L) = StableFrameGate(required, stallMs)

    @Test
    fun `第一帧不采纳，只是开始等`() {
        val d = gate().offer("A", 0)
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), d)
    }

    @Test
    fun `两帧相同就采纳`() {
        val g = gate()
        g.offer("A", 0)
        assertEquals(StableFrameGate.Decision.Stable(2), g.offer("A", 700))
    }

    @Test
    fun `A-B-B 第三帧才采纳`() {
        val g = gate()
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("A", 0))
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("B", 700))
        assertEquals(StableFrameGate.Decision.Stable(2), g.offer("B", 1400))
    }

    @Test
    fun `抖动期间一直是 Waiting`() {
        val g = gate()
        var t = 0L
        repeat(4) {
            assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("A", t))
            assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("B", t + 350))
            t += 700
        }
    }

    /**
     * 核心守卫：指纹每帧都在变，也必须在 stallMs 之后强制采纳。
     * 若实现里「换指纹就重置计时」，这里会一直等到 Waiting，永远不 Forced。
     */
    @Test
    fun `换指纹不重置计时，抖动超时后强制采纳`() {
        val g = gate(stallMs = 3000)
        g.offer("A", 0)
        g.offer("B", 700)
        g.offer("A", 1400)
        g.offer("B", 2100)
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("A", 2800))
        val forced = g.offer("B", 3500)
        assertTrue("抖动超过 3 秒必须强制采纳，实际 $forced", forced is StableFrameGate.Decision.Forced)
        assertEquals(3500L, (forced as StableFrameGate.Decision.Forced).waitedMs)
    }

    /** 恰好到点也触发（判据是 >=，不是 >）—— 否则边界上会多等一整轮。 */
    @Test
    fun `刚好到 stallMs 就强制采纳`() {
        val g = gate(stallMs = 3000)
        g.offer("A", 0)
        g.offer("B", 700)
        val forced = g.offer("A", 3000)
        assertTrue("实际 $forced", forced is StableFrameGate.Decision.Forced)
    }

    @Test
    fun `强制采纳之后重新开始凑帧`() {
        val g = gate(stallMs = 1000)
        g.offer("A", 0)
        g.offer("B", 600)
        assertTrue(g.offer("A", 1200) is StableFrameGate.Decision.Forced)
        // 上一轮的计时与指纹都必须清干净，否则下一帧会被立刻当成 Stable
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("A", 1400))
        assertEquals(StableFrameGate.Decision.Stable(2), g.offer("A", 2100))
    }

    @Test
    fun `采纳之后同一个指纹要重新凑两帧`() {
        val g = gate()
        g.offer("A", 0)
        g.offer("A", 700)
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("A", 1400))
    }

    @Test
    fun `required 大于 2 时两帧相同也不算稳定`() {
        val g = gate(required = 3)
        assertEquals(StableFrameGate.Decision.Waiting(1, 3), g.offer("A", 0))
        assertEquals(StableFrameGate.Decision.Waiting(2, 3), g.offer("A", 700))
        assertEquals(StableFrameGate.Decision.Stable(3), g.offer("A", 1400))
    }

    @Test
    fun `reset 清掉计时，抖动计时从头开始`() {
        val g = gate(stallMs = 1000)
        g.offer("A", 0)
        g.offer("B", 800)
        g.reset()
        // 若 reset 没清计时，这里会立刻 Forced
        assertEquals(StableFrameGate.Decision.Waiting(1, 2), g.offer("C", 900))
    }

    @Test
    fun `时刻 0 开始等待不会被当成没开始`() {
        val g = gate(stallMs = 500)
        g.offer("A", 0)
        g.offer("B", 100)
        assertTrue(g.offer("C", 500) is StableFrameGate.Decision.Forced)
    }
}
