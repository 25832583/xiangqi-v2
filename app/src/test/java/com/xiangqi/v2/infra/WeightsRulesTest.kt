package com.xiangqi.v2.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权重文件判据。
 *
 * 这套判据的失败方向是「宁可错拒」：拒掉一个可疑文件的代价是用户重选一次，
 * 而放进一个不是权重的文件的代价是引擎起来后加载时崩 —— 后者在界面上完全看不出来
 * 跟刚才那次选择有关。
 */
class WeightsRulesTest {

    private val zstdHead = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    @Test
    fun `已知权重的体积与文件头通过`() {
        // 与 fetch_assets.py 落位的 pikafish.nnue 一致：50,706,378 字节（2026-09-06 版）
        assertNull(WeightsRules.check(50_706_378L, zstdHead))
    }

    @Test
    fun `刚好到下限通过`() {
        assertNull(WeightsRules.check(WeightsRules.MIN_BYTES, zstdHead))
    }

    @Test
    fun `比下限少一字节被拒并说明体积`() {
        val reason = WeightsRules.check(WeightsRules.MIN_BYTES - 1, zstdHead)
        assertNotNull(reason)
        assertEquals(true, reason!!.contains("8MB"))
    }

    @Test
    fun `文件头不对被拒`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val reason = WeightsRules.check(50_706_378L, png)
        assertNotNull(reason)
        assertEquals(true, reason!!.contains("NNUE"))
    }

    @Test
    fun `文件头不足四字节被拒而不是越界`() {
        assertNotNull(WeightsRules.check(50_706_378L, byteArrayOf(0x28, 0xB5.toByte())))
        assertNotNull(WeightsRules.check(50_706_378L, ByteArray(0)))
    }

    @Test
    fun `超过上限被拒`() {
        assertEquals(WeightsRules.TOO_BIG, WeightsRules.check(WeightsRules.MAX_BYTES + 1, zstdHead))
    }

    @Test
    fun `体积按一位小数显示`() {
        assertEquals("50.7MB", WeightsRules.mb(50_706_378L))
        assertEquals("0.0MB", WeightsRules.mb(0L))
    }

    // ---- 引擎自述里的「权重加载失败」 ----

    /**
     * 这一段判据决定「出错时该不该降级」。误判成权重问题的代价是丢掉一份本来能用的
     * 权重、棋力白白掉一大截，所以「不该命中」的用例和「该命中」的一样重要。
     */
    @Test
    fun `认出皮卡鱼拒绝加载权重的自述`() {
        val lines = listOf(
            "info string ERROR: Network evaluation parameters compatible with the engine must be available.",
            "info string ERROR: The network file /data/user/0/x/files/engine/pikafish.nnue was not loaded successfully.",
            "info string ERROR: The engine will be terminated now.",
        )
        assertTrue(WeightsRules.looksLikeWeightMismatch(lines))
    }

    @Test
    fun `别的启动失败不会被当成权重问题`() {
        val lines = listOf(
            "info string Available processors: 0-7",
            "info string Using 4 threads",
            "info string ERROR: Illegal instruction",
        )
        assertFalse(WeightsRules.looksLikeWeightMismatch(lines))
    }

    @Test
    fun `大小写不影响判断，空输入不命中`() {
        assertTrue(
            WeightsRules.looksLikeWeightMismatch(listOf("INFO STRING ERROR: EVALFILE not found"))
        )
        assertFalse(WeightsRules.looksLikeWeightMismatch(emptyList()))
    }
}
