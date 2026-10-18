package com.xiangqi.v2.core.rule

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.core.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 走法生成的护栏。
 *
 * 这个生成器不参与下棋、也不参与局面校验，只被 [StepResolver] 用来回答
 * 「这一帧的变化是不是一步棋」。所以它的**正确性要求是「不能漏」**：
 * 漏掉一条真实走法，就会把真实走子判成「无法解释」，进而丢帧、局面卡住。
 * 下面每条对应一条象棋走法规则，落点集合是**精确断言**（不是「包含」），
 * 这样多生成一条非法走法也会立刻暴露。
 *
 * 坐标系：row 0 = 黑方底线，row 9 = 红方底线；红方朝 row 减小方向前进。
 */
class MoveGeneratorTest {

    private val red = Side.RED
    private val black = Side.BLACK

    /** 落点集合。 */
    private fun targets(board: Board, row: Int, col: Int): Set<Pair<Int, Int>> =
        MoveGenerator.movesOf(board, Square(row, col)).map { it.toRow to it.toCol }.toSet()

    private fun only(row: Int, col: Int, piece: Piece): Board =
        Board.builder().set(row, col, piece).build()

    // ---------------------------------------------------------------- 车

    @Test
    fun `车在角上走 17 步 —— 向右 8 格向上 9 格`() {
        val t = targets(only(9, 0, Piece(PieceType.CHARIOT, red)), 9, 0)

        assertEquals(17, t.size)
        assertTrue((9 to 8) in t)
        assertTrue((0 to 0) in t)
        assertFalse("车不能停在原地", (9 to 0) in t)
    }

    @Test
    fun `车被己方子挡住 —— 走不到它 更越不过它`() {
        // 红兵在 (6,0)：挡住红车上去的路
        val b = Board.fromFenBoardPart("9/9/9/9/9/9/P8/9/9/R8")
        val t = targets(b, 9, 0)

        assertTrue((8 to 0) in t)
        assertTrue((7 to 0) in t)
        assertFalse("不能吃自己的兵", (6 to 0) in t)
        assertFalse("不能越过己方子", (5 to 0) in t)
        assertEquals("上方 2 格 + 右侧 8 格", 10, t.size)
    }

    @Test
    fun `车能吃掉挡路的敌子 但吃不到它后面`() {
        // 黑卒在 (6,0)
        val b = Board.fromFenBoardPart("9/9/9/9/9/9/p8/9/9/R8")
        val t = targets(b, 9, 0)

        assertTrue("敌子可以吃", (6 to 0) in t)
        assertFalse("吃不到敌子后面那格", (5 to 0) in t)
    }

    // ---------------------------------------------------------------- 马

    @Test
    fun `马在空盘中央八个方向都能走`() {
        assertEquals(8, targets(only(5, 4, Piece(PieceType.HORSE, red)), 5, 4).size)
    }

    @Test
    fun `蹩马腿 —— 腿被占住的那两个方向走不了`() {
        // 马 (5,4)；占住 (4,4) 就蹩住向上的两个日字步
        val b = Board.fromFenBoardPart("9/9/9/9/4P4/4N4/9/9/9/9")
        val t = targets(b, 5, 4)

        assertEquals(6, t.size)
        assertFalse((3 to 3) in t)
        assertFalse((3 to 5) in t)
        assertTrue("横向的日字步不受影响", (4 to 2) in t)
        assertTrue((7 to 5) in t)
    }

    // ---------------------------------------------------------------- 相 / 象

    @Test
    fun `相走田字 —— 底线角上只能走一侧`() {
        // 红相 (9,2)：田字落点只可能落在 (7,0)、(7,4)
        val t = targets(only(9, 2, Piece(PieceType.ELEPHANT, red)), 9, 2)

        assertEquals(setOf(7 to 0, 7 to 4), t)
    }

    @Test
    fun `塞象眼 —— 田字中心被占就走不了`() {
        // (8,3) 上放一个子，塞住通往 (7,4) 的象眼
        val b = Board.fromFenBoardPart("9/9/9/9/9/9/9/9/3P5/2B6")
        val t = targets(b, 9, 2)

        assertEquals(setOf(7 to 0), t)
    }

    @Test
    fun `相不过河 —— 站在河界上也只往自己这边走`() {
        // 红相 (5,2)：往上的田字落点 (3,0)/(3,4) 已过河，非法
        val t = targets(only(5, 2, Piece(PieceType.ELEPHANT, red)), 5, 2)

        assertEquals(setOf(7 to 0, 7 to 4), t)
    }

    // ---------------------------------------------------------------- 仕 / 帅

    @Test
    fun `仕斜走一格且不出九宫`() {
        // 红仕在九宫左下角 (9,3)：只能到 (8,4)
        val t = targets(only(9, 3, Piece(PieceType.ADVISOR, red)), 9, 3)

        assertEquals(setOf(8 to 4), t)
    }

    @Test
    fun `帅在九宫中心三个方向`() {
        // 红帅 (9,4)：(10,4) 出界、(9,2)/(9,6) 出九宫
        val t = targets(only(9, 4, Piece(PieceType.KING, red)), 9, 4)

        assertEquals(setOf(8 to 4, 9 to 3, 9 to 5), t)
    }

    // ---------------------------------------------------------------- 炮

    @Test
    fun `炮不吃子时与车完全一样`() {
        assertEquals(17, targets(only(9, 0, Piece(PieceType.CANNON, red)), 9, 0).size)
    }

    @Test
    fun `炮吃子必须恰好隔一个炮架 隔两个够不着`() {
        // 红炮 (9,0)；(6,0) 是炮架；(3,0) 在炮架之后可吃；(1,0) 隔了两个子
        val b = Board.fromFenBoardPart("9/p8/9/p8/9/9/p8/9/9/C8")
        val t = targets(b, 9, 0)

        assertTrue("炮架之前是空格，可以走", (8 to 0) in t)
        assertTrue((7 to 0) in t)
        assertFalse("不能停在炮架那格", (6 to 0) in t)
        assertFalse("炮架之后的空格不能停", (5 to 0) in t)
        assertFalse((4 to 0) in t)
        assertTrue("炮架之后的第一个子可以吃", (3 to 0) in t)
        assertFalse("隔两个子够不着", (1 to 0) in t)
        assertEquals("上方 3 步 + 右侧 8 格", 11, t.size)
    }

    // ---------------------------------------------------------------- 兵 / 卒

    @Test
    fun `兵没过河只能向前一步`() {
        val t = targets(only(6, 4, Piece(PieceType.PAWN, red)), 6, 4)

        assertEquals(setOf(5 to 4), t)
    }

    @Test
    fun `兵过河后可以横走 但永远不后退`() {
        // 红兵在 row 4 = 已经过河
        val t = targets(only(4, 4, Piece(PieceType.PAWN, red)), 4, 4)

        assertEquals(setOf(3 to 4, 4 to 3, 4 to 5), t)
        assertFalse("红兵朝 row 减小走，不可能后退", (5 to 4) in t)
    }

    @Test
    fun `黑卒方向相反 朝 row 增大走`() {
        val t = targets(only(3, 4, Piece(PieceType.PAWN, black)), 3, 4)

        assertEquals(setOf(4 to 4), t)
        assertFalse((2 to 4) in t)
    }

    @Test
    fun `黑卒在第 5 行即已过河 可以横走`() {
        val t = targets(only(5, 4, Piece(PieceType.PAWN, black)), 5, 4)

        assertEquals(setOf(6 to 4, 5 to 3, 5 to 5), t)
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `空格上生成不出走法`() {
        assertTrue(MoveGenerator.movesOf(Board.empty(), Square(5, 4)).isEmpty())
    }
}
