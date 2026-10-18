package com.xiangqi.v2.pipeline

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 走子方推断。
 *
 * 这一项的错法特别难发现：推断反了，引擎会给出**对方**的最佳着法，看着完全合理，
 * 用户照着走却发现自己根本不能那么走。所以每条规则都钉住。
 */
class SideInferenceTest {

    private val START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

    @Test
    fun `红方走子后轮到黑方`() {
        val before = Board.fromFenBoardPart(START)
        // 红炮 (面向上时的第 2 行, 第 2 列) 平到中线
        val after = before
            .with(7, 1, null)
            .with(7, 4, Piece(PieceType.CANNON, Side.RED))
        assertEquals(Side.BLACK, SideInference.infer(before, after, Side.RED))
    }

    @Test
    fun `黑方走子后轮到红方`() {
        val before = Board.fromFenBoardPart(START)
        // 黑卒向前一步（row 3 → row 4）
        val after = before
            .with(3, 0, null)
            .with(4, 0, Piece(PieceType.PAWN, Side.BLACK))
        assertEquals(Side.RED, SideInference.infer(before, after, Side.BLACK))
    }

    @Test
    fun `吃子时也能判出是谁走的`() {
        val before = Board.fromFenBoardPart(START)
        // 红车吃掉黑卒：黑卒格子从"有子"变"有红车"，红车原位变空
        val after = before
            .with(9, 0, null)
            .with(3, 0, Piece(PieceType.CHARIOT, Side.RED))
        assertEquals(Side.BLACK, SideInference.infer(before, after, Side.RED))
    }

    @Test
    fun `变化过大不推断，保持原值`() {
        val before = Board.fromFenBoardPart(START)
        val after = Board.fromFenBoardPart(
            "2bak1b2/4a4/2n1c1n2/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/4A4/2BAK1B2"
        )
        assertEquals(Side.RED, SideInference.infer(before, after, Side.RED))
        assertEquals(Side.BLACK, SideInference.infer(before, after, Side.BLACK))
    }

    @Test
    fun `首次识别没有参照，保持原值`() {
        val board = Board.fromFenBoardPart(START)
        assertEquals(Side.RED, SideInference.infer(null, board, Side.RED))
        assertEquals(Side.BLACK, SideInference.infer(null, board, Side.BLACK))
    }

    @Test
    fun `盘面没变也不推断`() {
        val board = Board.fromFenBoardPart(START)
        assertEquals(Side.RED, SideInference.infer(board, board, Side.RED))
    }
}
