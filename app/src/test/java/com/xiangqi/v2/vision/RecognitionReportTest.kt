package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecognitionReport] 的判据：只有真正写进盘面的格子才可能被点名。
 *
 * 这一层没有算法，但它决定「用户会不会被告知这颗子不可信」。写错了有两种后果，
 * 都不报错、都只能靠人看：
 *  - 阈值写反 → 每个局面都挂一串警告，用户很快学会无视它；
 *  - 把没进盘面的格子也算进去 → 界面上圈出一个空格子，比不圈更让人困惑。
 */
class RecognitionReportTest {

    private fun detection(label: Char, score: Float): Detection {
        val classIndex = Labels.ALL.indexOf(label)
        require(classIndex >= 0) { "未知类别 $label" }
        return Detection(Box(0f, 0f, 10f, 10f), score, classIndex)
    }

    private fun candidate(row: Int, col: Int, label: Char, score: Float) =
        CellCandidate(row, col, detection(label, score), 0.02f)

    private fun assemble(board: Board, extra: List<Detection> = emptyList()): Assembly = Assembly(
        screenBoard = board,
        board = board,
        redOnTop = false,
        conflicts = emptyList(),
        overLimit = extra,
        rowFrom = 0,
        rowTo = 9,
        colFrom = 0,
        colTo = 8,
        maxResidual = 0.02f,
        notes = emptyList()
    )

    private fun report(board: Board, candidates: List<CellCandidate>) = RecognitionReport.build(
        assembly = assemble(board),
        candidates = candidates,
        fitX = AxisFit(pitch = 90f, phase = 10f, residual = 0.02f, concentration = 0.9f),
        fitY = AxisFit(pitch = 90f, phase = 10f, residual = 0.02f, concentration = 0.9f),
        frameWidth = 1080,
        frameHeight = 2400,
        colOffset = 0,
        rowOffset = 0
    )

    private fun boardWith(vararg cells: Triple<Int, Int, Pair<PieceType, Side>>): Board {
        val builder = Board.builder()
        for ((row, col, value) in cells) {
            builder.set(row, col, Piece(value.first, value.second))
        }
        return builder.build()
    }

    @Test
    fun `全部高置信时不点名，摘要里带上最低分与帧尺寸`() {
        val board = boardWith(
            Triple(7, 0, PieceType.ELEPHANT to Side.RED),
            Triple(9, 4, PieceType.KING to Side.RED)
        )
        val result = report(
            board,
            listOf(
                candidate(7, 0, 'B', 0.93f),
                candidate(9, 4, 'K', 0.88f)
            )
        )
        assertTrue("不该有低置信格，实际 ${result.uncertain}", result.uncertain.isEmpty())
        val summary = result.lines.first()
        assertTrue("摘要缺最低分: $summary", summary.contains("最低 conf 0.88"))
        assertTrue("摘要缺帧尺寸: $summary", summary.contains("帧 1080x2400"))
        // 最低的那一颗要能定位到具体格子，否则"最低 0.88"没法查
        assertTrue("摘要缺最低分所在格: $summary", summary.contains("(9,4)"))
        assertFalse("没有低置信格却打了警告: ${result.lines}", result.lines.any { it.contains("低置信") })
    }

    @Test
    fun `低于阈值的一格被点名，并接到高亮集合里`() {
        val board = boardWith(
            Triple(7, 0, PieceType.ELEPHANT to Side.RED),
            Triple(9, 4, PieceType.KING to Side.RED)
        )
        // 0.49 是实测值：把「相」的字形下半遮住 40% 时模型就是这么判的
        val result = report(
            board,
            listOf(
                candidate(7, 0, 'C', 0.49f),
                candidate(9, 4, 'K', 0.91f)
            )
        )
        assertEquals(setOf(7 * Board.COLS + 0), result.uncertain)
        assertTrue(
            "没把那一格列出来: ${result.lines}",
            result.lines.any { it.contains("(7,0)") && it.contains("0.49") }
        )
    }

    @Test
    fun `阈值边界：正好等于阈值不算低置信`() {
        val board = boardWith(Triple(5, 5, PieceType.CANNON to Side.BLACK))
        val result = report(board, listOf(candidate(5, 5, 'c', RecognitionReport.LOW_CONFIDENCE)))
        assertTrue(
            "等于阈值应当算可信（判据是严格小于），否则临界帧会无端亮警告",
            result.uncertain.isEmpty()
        )
    }

    @Test
    fun `降采样混叠导致的 0_71 也必须被点名`() {
        // 实测：整屏帧一次双线性缩到 640，(3,5) 的红车会变成红炮 0.706。
        // 旧阈值 0.70 恰好放过这一档，是「认错了但界面上一点提示都没有」的直接原因。
        val board = boardWith(
            Triple(3, 5, PieceType.CANNON to Side.RED),
            Triple(9, 4, PieceType.KING to Side.RED)
        )
        val result = report(
            board,
            listOf(
                candidate(3, 5, 'C', 0.706f),
                candidate(9, 4, 'K', 0.92f)
            )
        )
        assertEquals(setOf(3 * Board.COLS + 5), result.uncertain)
    }

    @Test
    fun `没进盘面的格子不会被点名`() {
        // 盘面里这一格是空的（例如这条候选被同格冲突或超限裁掉了），
        // 界面上没有棋子可圈 —— 圈一个空格子只会让人以为界面坏了
        val board = boardWith(Triple(9, 4, PieceType.KING to Side.RED))
        val result = report(
            board,
            listOf(
                candidate(9, 4, 'K', 0.92f),
                candidate(0, 0, 'p', 0.31f)
            )
        )
        assertTrue("空格子被点名了: ${result.uncertain}", result.uncertain.isEmpty())
    }
}
