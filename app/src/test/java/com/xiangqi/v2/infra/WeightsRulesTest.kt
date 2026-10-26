package com.xiangqi.v2.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // 与 fetch_assets.py 落位的 pikafish.nnue 一致：53,212,941 字节
        assertNull(WeightsRules.check(53_212_941L, zstdHead))
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
        val reason = WeightsRules.check(53_212_941L, png)
        assertNotNull(reason)
        assertEquals(true, reason!!.contains("NNUE"))
    }

    @Test
    fun `文件头不足四字节被拒而不是越界`() {
        assertNotNull(WeightsRules.check(53_212_941L, byteArrayOf(0x28, 0xB5.toByte())))
        assertNotNull(WeightsRules.check(53_212_941L, ByteArray(0)))
    }

    @Test
    fun `超过上限被拒`() {
        assertEquals(WeightsRules.TOO_BIG, WeightsRules.check(WeightsRules.MAX_BYTES + 1, zstdHead))
    }

    @Test
    fun `体积按一位小数显示`() {
        assertEquals("53.2MB", WeightsRules.mb(53_212_941L))
        assertEquals("0.0MB", WeightsRules.mb(0L))
    }
}
