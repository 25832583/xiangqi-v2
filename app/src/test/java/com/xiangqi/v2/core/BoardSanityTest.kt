package com.xiangqi.v2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引擎前合法性校验。
 *
 * 这一层只拦「象棋里根本不存在」的局面 —— 识别漏一颗子会造出这种局面，
 * 而皮卡鱼的应对是 `CRITICAL ERROR: Unsupported position` 然后退出进程，
 * 界面上只剩一个「—」，用户完全分不清是识别错了还是没着可走。所以每条都要钉住。
 */
class BoardSanityTest {

    private val START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    @Test
    fun `标准开局可以送引擎`() {
        assertNull(BoardSanity.check(Board.fromFenBoardPart(START)))
    }

    @Test
    fun `残局只有双方将帅也可以送引擎`() {
        assertNull(BoardSanity.check(Board.fromFenBoardPart("3k5/9/9/9/9/9/9/9/9/4K4")))
    }

    @Test
    fun `将帅照面被拦下`() {
        val problem = BoardSanity.check(Board.fromFenBoardPart("4k4/9/9/9/9/9/9/9/9/4K4"))
        assertEquals("将帅照面", problem)
    }

    @Test
    fun `将帅照面但中间有子则放过`() {
        assertNull(BoardSanity.check(Board.fromFenBoardPart("4k4/9/9/9/4P4/9/9/9/9/4K4")))
    }

    @Test
    fun `黑将两个被拦下`() {
        val problem = BoardSanity.check(Board.fromFenBoardPart("3kk4/9/9/9/9/9/9/9/9/4K4"))
        assertNotNull(problem)
        assertEquals("红帅 1 个／黑将 2 个", problem)
    }

    @Test
    fun `红帅缺失被拦下`() {
        val problem = BoardSanity.check(Board.fromFenBoardPart("3k5/9/9/9/9/9/9/9/9/4P4"))
        assertEquals("红帅 0 个／黑将 1 个", problem)
    }

    @Test
    fun `红兵在己方底线被拦下`() {
        val problem = BoardSanity.check(Board.fromFenBoardPart("3k5/9/9/9/9/9/9/9/9/3PK4"))
        assertEquals("红兵在己方底线", problem)
    }

    @Test
    fun `黑卒在己方底线被拦下`() {
        // 红帅放在 col 0，避免同时触发「将帅照面」把真正的目标掩盖掉
        val problem = BoardSanity.check(Board.fromFenBoardPart("3pk4/9/9/9/9/9/9/9/9/K8"))
        assertEquals("黑卒在己方底线", problem)
    }

    @Test
    fun `马超过两匹被拦下`() {
        // 红方三匹马：识别把仕读成马时就是这个样子
        val problem = BoardSanity.check(Board.fromFenBoardPart("3k5/9/9/9/9/9/9/9/9/2NNNK3"))
        assertNotNull(problem)
        assertEquals(true, problem!!.contains("马"))
    }
}
