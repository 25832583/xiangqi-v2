package com.xiangqi.v2.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「单帧即采」判据的边界。
 *
 * 这组测试守的是**两格容忍**，不是顺利路径：
 *  - 走子动画的中间态会短暂地既不合法、也对不上，第一帧就报红字会让每步棋闪一次；
 *  - 但容忍也不能无限：连续几帧都不对，就说明不是中间态，而是真的认错了/换了局面，
 *    这时候既不能不吭声（局面永不更新），也不能永久拦着（卡死）。
 *
 * 每条都用「第几帧」写清楚，因为这里所有的错都长成 off-by-one 的样子。
 */
class FastAcceptPolicyTest {

    private fun policy() = FastAcceptPolicy()

    @Test
    fun `首帧没有参照盘面，直接采纳`() {
        assertEquals(FastAcceptPolicy.Decision.Accept, policy().onFrame(null, null))
    }

    @Test
    fun `合法且能由上一帧走一步得到，采纳`() {
        assertEquals(FastAcceptPolicy.Decision.Accept, policy().onFrame(null, true))
    }

    @Test
    fun `不合法只跳过一帧，先不打扰用户`() {
        val d = policy().onFrame("红帅 2 个／黑将 1 个", null)
        assertEquals(FastAcceptPolicy.Decision.Skip, d)
    }

    @Test
    fun `连续两帧不合法才报，且原因带前缀`() {
        val p = policy()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame("红兵在己方底线", null))
        val d = p.onFrame("红兵在己方底线", null)
        assertTrue(d is FastAcceptPolicy.Decision.ReportIllegal)
        assertEquals("识别异常：红兵在己方底线", (d as FastAcceptPolicy.Decision.ReportIllegal).reason)
    }

    @Test
    fun `不合法一帧后又合法，计数清零`() {
        val p = policy()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame("将帅照面", null))
        assertEquals(FastAcceptPolicy.Decision.Accept, p.onFrame(null, true))
        // 清零之后再遇到一帧不合法，仍然只是跳过 —— 不该接上次的账凑成「连续两帧」
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame("将帅照面", null))
    }

    @Test
    fun `对不上第一帧跳过，第二帧强制放行`() {
        val p = policy()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame(null, false))
        assertEquals(FastAcceptPolicy.Decision.ForceAccept, p.onFrame(null, false))
    }

    @Test
    fun `对不上之后重新对上，立刻采纳并清零`() {
        val p = policy()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame(null, false))
        assertEquals(FastAcceptPolicy.Decision.Accept, p.onFrame(null, true))
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame(null, false))
    }

    @Test
    fun `采纳之后计数清零，下一帧不合法仍只是跳过`() {
        val p = policy()
        p.onFrame(null, false)
        p.onFrame(null, false)
        p.onAccepted()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame("黑卒在己方底线", null))
    }

    @Test
    fun `不合法与对不上各算各的，交替出现不会凑成任何一条`() {
        val p = policy()
        repeat(3) {
            assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame("将帅照面", null))
            assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame(null, false))
        }
    }

    @Test
    fun `强制放行后也要清零，否则下一帧立刻又强制放行`() {
        val p = policy()
        p.onFrame(null, false)
        assertEquals(FastAcceptPolicy.Decision.ForceAccept, p.onFrame(null, false))
        // 调用方在强制放行后同样会走 onAccepted()
        p.onAccepted()
        assertEquals(FastAcceptPolicy.Decision.Skip, p.onFrame(null, false))
    }

    @Test
    fun `默认容忍是两帧 —— 换成 3 就等于把省下的那一帧又赔回去了`() {
        // 2 帧 × 700ms 默认间隔 ≈ 1.4 秒，已经长过一个走子动画（<1 秒）
        assertEquals(2, FastAcceptPolicy.DEFAULT_MAX_ILLEGAL_STREAK)
        assertEquals(2, FastAcceptPolicy.DEFAULT_MAX_REJECT_STREAK)
    }

    @Test
    fun `单帧即采与多帧确认互为切换`() {
        assertEquals(GateMode.FAST, GateMode.STABLE.next())
        assertEquals(GateMode.STABLE, GateMode.FAST.next())
    }
}
