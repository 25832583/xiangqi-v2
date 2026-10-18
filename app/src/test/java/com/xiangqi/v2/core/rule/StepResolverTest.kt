package com.xiangqi.v2.core.rule

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一步可达性判据的护栏。
 *
 * 这组测试要钉住的是**两类相反的错误**：
 *
 *  - **漏放**：真实走子被判定为「解释不了」→ 丢帧 → 局面卡住不动。
 *    第 1、2、3、10 条守着这一侧（尤其第 10 条：对开局穷举**每一个**伪合法走法，
 *    生成的下一帧必须全部判为可解释）。
 *  - **误放**：识别抖动被判定为「一步棋」→ 错误进入历史。
 *    第 4~9 条守着这一侧，其中第 4 条是这次改动的**核心守卫**：
 *    「某一帧把一个仕认成马」只差 1 格，旧的「差异格数超阈值」判据完全看不见它。
 */
class StepResolverTest {

    private val red = Side.RED
    private val black = Side.BLACK

    // 旧工程这里调的是 OpeningCalibration.openingBoard()，而 OpeningCalibration 是
    // v2 明确不移植的 7 个修补模块之一。改用 Fen.START_FEN 直接建盘 —— 同一个局面，
    // 依赖少一层。
    private fun opening(): Board = Board.fromFenBoardPart(Fen.START_FEN.substringBefore(' '))

    private fun explain(prev: Board, curr: Board, maxStrayDiff: Int = 1) =
        StepResolver.explain(prev, curr, maxStrayDiff)

    private fun explained(prev: Board, curr: Board): StepResolver.Verdict.Explained {
        val v = explain(prev, curr)
        assertTrue("应当能被一步棋解释，实际 $v", v is StepResolver.Verdict.Explained)
        return v as StepResolver.Verdict.Explained
    }

    // ---------------------------------------------------------------- 放行的一侧

    @Test
    fun `逐格相同 —— 没有任何变化要解释`() {
        assertEquals(StepResolver.Verdict.NoChange, explain(opening(), opening()))
    }

    @Test
    fun `真走一步棋 —— 可解释且完美吻合`() {
        val prev = opening()
        // 红马二进三：(9,1) → (7,2)
        val curr = prev.with(9, 1, null).with(7, 2, Piece(PieceType.HORSE, red))

        val v = explained(prev, curr)

        assertEquals(PieceType.HORSE, v.piece)
        assertEquals(Side.RED, v.side)
        assertEquals("一步棋恰好改两格，应当完美吻合", 0, v.strayDiff)
        assertEquals(9, v.move.fromRow)
        assertEquals(1, v.move.fromCol)
        assertEquals(7, v.move.toRow)
        assertEquals(2, v.move.toCol)
    }

    @Test
    fun `吃子也是两格差异 —— 同样可解释`() {
        val prev = Board.fromFenBoardPart("p8/9/9/9/9/9/9/9/9/R8")
        val curr = prev.with(9, 0, null).with(0, 0, Piece(PieceType.CHARIOT, red))

        val v = explained(prev, curr)

        assertEquals(PieceType.CHARIOT, v.piece)
        assertEquals(0, v.strayDiff)
    }

    @Test
    fun `炮平移也是可解释的`() {
        val prev = opening()
        // 炮二平五：(7,1) → (7,4)，路径 (7,2)/(7,3) 在开局是空的
        val curr = prev.with(7, 1, null).with(7, 4, Piece(PieceType.CANNON, red))

        assertEquals(PieceType.CANNON, explained(prev, curr).piece)
    }

    @Test
    fun `走一步棋再加一格时有时无 —— 算解释得通 不丢帧`() {
        val prev = opening()
        val curr = prev
            .with(9, 1, null)
            .with(7, 2, Piece(PieceType.HORSE, red))
            // (8,0) 本来空着，这一帧凭空多出一个子（检测阈值边缘的抖动）
            .with(8, 0, Piece(PieceType.PAWN, black))

        val v = explained(prev, curr)

        assertEquals("那一格是容差内的残留", 1, v.strayDiff)
    }

    @Test
    fun `容差调到 0 时 上面那帧就不再被接受 —— 这条说明容差确实在起作用`() {
        val prev = opening()
        val curr = prev
            .with(9, 1, null)
            .with(7, 2, Piece(PieceType.HORSE, red))
            .with(8, 0, Piece(PieceType.PAWN, black))

        assertTrue(explain(prev, curr, maxStrayDiff = 0) is StepResolver.Verdict.Unexplained)
    }

    // ---------------------------------------------------------------- 拦下的一侧

    @Test
    fun `某一帧把一个仕认成马 —— 只差一格也必须拦下`() {
        val prev = opening()
        val curr = prev.with(9, 3, Piece(PieceType.HORSE, red))

        assertEquals("先把前提摆出来：确实只差一格", 1, prev.diffCount(curr))
        val v = explain(prev, curr)

        assertTrue(
            "凭空换种类没有任何走法能解释（落点对不上），实际 $v",
            v is StepResolver.Verdict.Unexplained
        )
    }

    @Test
    fun `凭空多出一个子 —— 拦下`() {
        val prev = opening()
        val curr = prev.with(8, 0, Piece(PieceType.PAWN, black))

        assertTrue(explain(prev, curr) is StepResolver.Verdict.Unexplained)
    }

    @Test
    fun `凭空少掉一个子 —— 拦下`() {
        val prev = opening()
        val curr = prev.with(9, 0, null)

        assertTrue(explain(prev, curr) is StepResolver.Verdict.Unexplained)
    }

    @Test
    fun `走一步棋 同时别处一格认错种类 —— 仍然拦下`() {
        val prev = opening()
        val curr = prev
            .with(9, 1, null)
            .with(7, 2, Piece(PieceType.HORSE, red))
            // 黑方的车被读成了马：两边都有子、种类不同，这是分类抖动
            .with(0, 0, Piece(PieceType.HORSE, black))

        val v = explain(prev, curr)

        assertTrue("错类抖动一格局不容忍，实际 $v", v is StepResolver.Verdict.Unexplained)
    }

    @Test
    fun `两个子各走一步 —— 不是一步棋`() {
        val prev = opening()
        val curr = prev
            .with(9, 1, null).with(7, 2, Piece(PieceType.HORSE, red))
            .with(9, 7, null).with(7, 6, Piece(PieceType.HORSE, red))

        assertTrue(explain(prev, curr) is StepResolver.Verdict.Unexplained)
    }

    @Test
    fun `差异过大时直接判不可解释`() {
        val v = explain(opening(), Board.empty())

        assertTrue(v is StepResolver.Verdict.Unexplained)
        assertEquals("开局 32 个子全没了", 32, (v as StepResolver.Verdict.Unexplained).diffCells)
    }

    // ---------------------------------------------------------------- 穷举自洽

    @Test
    fun `开局的每一个走法 生成的下一帧都必须可解释`() {
        val prev = opening()
        var tested = 0
        for (located in prev.pieces()) {
            for (move in MoveGenerator.movesOf(prev, located.square)) {
                val curr = prev
                    .with(move.fromRow, move.fromCol, null)
                    .with(move.toRow, move.toCol, located.piece)

                val v = explain(prev, curr)
                assertTrue("$move（${located.piece}）应当被解释，实际 $v", v is StepResolver.Verdict.Explained)
                assertEquals(
                    "$move 是干净的一步棋，不该有任何残留",
                    0,
                    (v as StepResolver.Verdict.Explained).strayDiff
                )
                tested++
            }
        }
        assertTrue("开局能枚举出的走法太少（只测到 $tested 个），说明生成器漏了", tested > 40)
    }
}
