package com.xiangqi.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸先验的边界。
 *
 * 关键用例来自真机：JJ象棋比赛皮肤上，模型把**整块棋盘**同时认成了一颗「红车」，
 * 框是 7.4 格 × 8.2 格、置信 0.323。这颗幽灵子会吸附到河界中间，之后每一帧都被
 * 「与上一步对不上」拒绝，用户看到的就是识别不了。
 */
class PieceSizeFilterTest {

    /** 一格 = 90px：棋盘框 720x810（9 列 8 间距、10 行 9 间距）。 */
    private val board = Box(0f, 0f, 720f, 810f)
    private val unitX = 90f
    private val unitY = 90f

    @Test
    fun `正常棋子通过`() {
        for (f in listOf(0.85f, 0.95f, 1.0f, 1.1f)) {
            assertTrue("$f 格应该像棋子", PieceSizeFilter.looksLikePiece(box(f), unitX, unitY))
        }
    }

    @Test
    fun `整块棋盘被当成棋子时被拒`() {
        // 真机实测尺寸，两个方向都远超一格
        assertFalse(PieceSizeFilter.looksLikePiece(Box(30f, 60f, 696f, 795f), unitX, unitY))
    }

    @Test
    fun `太小的框被拒`() {
        assertFalse("0.3 格不像棋子", PieceSizeFilter.looksLikePiece(box(0.3f), unitX, unitY))
    }

    @Test
    fun `边界值包含在内`() {
        assertTrue(PieceSizeFilter.looksLikePiece(box(PieceSizeFilter.MIN_CELLS), unitX, unitY))
        assertTrue(PieceSizeFilter.looksLikePiece(box(PieceSizeFilter.MAX_CELLS), unitX, unitY))
        assertFalse(PieceSizeFilter.looksLikePiece(box(PieceSizeFilter.MIN_CELLS - 0.01f), unitX, unitY))
        assertFalse(PieceSizeFilter.looksLikePiece(box(PieceSizeFilter.MAX_CELLS + 0.01f), unitX, unitY))
    }

    @Test
    fun `横纵分别判据 - 又扁又宽的也算异常`() {
        // 宽度正常、高度只有 0.3 格：变形框
        assertFalse(PieceSizeFilter.looksLikePiece(Box(0f, 0f, 90f, 27f), unitX, unitY))
    }

    @Test
    fun `keep 只留下棋子尺寸的框`() {
        val pieces = listOf(
            Detection(Box(0f, 0f, 90f, 90f), 0.95f, 7),          // 红车 正常
            Detection(Box(30f, 60f, 696f, 795f), 0.32f, 7),      // 整盘 幽灵
            Detection(Box(100f, 100f, 190f, 190f), 0.88f, 11)     // 红相 正常
        )
        val kept = PieceSizeFilter.keep(pieces, board)
        assertEquals(2, kept.size)
        assertTrue("幽灵框必须被滤掉", kept.none { it.score == 0.32f })
    }

    @Test
    fun `棋盘框尺寸非法时原样返回`() {
        val pieces = listOf(Detection(Box(0f, 0f, 90f, 90f), 0.9f, 7))
        assertEquals(pieces.size, PieceSizeFilter.keep(pieces, Box(0f, 0f, 0f, 0f)).size)
    }

    private fun box(cells: Float) = Box(0f, 0f, cells * unitX, cells * unitY)
}
