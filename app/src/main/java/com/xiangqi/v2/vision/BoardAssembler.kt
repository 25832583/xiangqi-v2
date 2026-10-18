package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.core.Square

/** 同一个交点上出现多个框时，被丢掉的那些。 */
data class Conflict(val square: Square, val kept: Detection, val dropped: List<Detection>)

/**
 * 一次组装的完整结果。
 *
 * [screenBoard] 是画面方向（row 0 = 图像顶部），[board] 已归一成标准方向
 * （row 0 = 黑方底线，与 FEN 一致）。下游规则/引擎只允许看 [board]。
 */
data class Assembly(
    val screenBoard: Board,
    val board: Board,
    val redOnTop: Boolean,
    val conflicts: List<Conflict>,
    val overLimit: List<Detection>,
    val rowFrom: Int,
    val rowTo: Int,
    val colFrom: Int,
    val colTo: Int,
    val maxResidual: Float,
    val notes: List<String>
) {
    val pieceCount: Int get() = board.pieceCount()

    /** 行 10 / 列 9 都铺满，且没有任何超限丢弃 —— 才算"几何完整"。 */
    val geometryComplete: Boolean
        get() = (rowTo - rowFrom) == Board.ROWS - 1 &&
            (colTo - colFrom) == Board.COLS - 1 &&
            overLimit.isEmpty()
}

/**
 * 组装：候选格子 -> 盘面。
 *
 * 这里刻意**没有**任何"修盘面"的逻辑 —— 不剪枝、不校准、不猜。
 * 只有两件事是必须做的：
 *  1. **按锚定偏移归一化行列号**：吸附得到的行列号带着未知整数平移，
 *     偏移由 [RulesAnchor] 用象棋规则解出，本类只负责套用；
 *  2. **同格冲突与超限裁剪**：取置信度最高的那个，其余丢弃并记录。
 *
 * 旧项目在等价位置长出了 `StructuralPruner` / `TypeBudgetRepair` / `OpeningCalibration`
 * / `OverlimitSilenceRecheck` 四个模块，因为它的棋子权威是"每格独立分类"，
 * 幻影棋子必须靠规则反推才能剔除。这里棋子只可能来自一次检测，
 * 超限就说明有重复框，按分数丢掉即可 —— 不需要知道象棋规则。
 */
object BoardAssembler {

    /**
     * 生产路径：行列号已由 [RulesAnchor] 解出锚定偏移。
     *
     * @param colOffset 列偏移（原始列号减去它得到 0..8）
     * @param rowOffset 行偏移（原始行号减去它得到 0..9）
     */
    fun assemble(
        candidates: List<CellCandidate>,
        colOffset: Int = 0,
        rowOffset: Int = 0,
        expectedRows: Int = Board.ROWS,
        expectedCols: Int = Board.COLS
    ): Assembly {
        if (candidates.isEmpty()) {
            return Assembly(
                Board.empty(), Board.empty(), false, emptyList(), emptyList(),
                0, -1, 0, -1, 0f, listOf("没有任何候选")
            )
        }

        val notes = ArrayList<String>()
        val groups = HashMap<Int, MutableList<CellCandidate>>()
        for (c in candidates) {
            val r = c.row - rowOffset
            val col = c.col - colOffset
            if (r !in 0 until expectedRows || col !in 0 until expectedCols) {
                notes.add("越界丢弃：原始(${c.row},${c.col}) 锚定后($r,$col)")
                continue
            }
            groups.getOrPut(r * Board.COLS + col) { ArrayList() }.add(c)
        }

        return build(groups, notes, candidates)
    }

    /**
     * 「最小行列当 0 位移」的旧做法。**只在测试和离线对比时用**。
     *
     * 危险：棋盘最外一行/列没有棋子时（中局残局极常见），整盘会错开一整行/列。
     * 19 张真实对局截图实测每张 13~20 个越界。生产请用带 offset 的重载。
     */
    @Deprecated(
        "min 平移在边缘行/列缺子时会整体错位，生产请用 assemble(candidates, colOffset, rowOffset)",
        ReplaceWith("assemble(candidates, colOffset, rowOffset)")
    )
    fun assembleByMinSpan(
        candidates: List<CellCandidate>,
        expectedRows: Int = Board.ROWS,
        expectedCols: Int = Board.COLS
    ): Assembly {
        if (candidates.isEmpty()) {
            return Assembly(
                Board.empty(), Board.empty(), false, emptyList(), emptyList(),
                0, -1, 0, -1, 0f, listOf("没有任何候选")
            )
        }

        val notes = ArrayList<String>()
        val minRow = candidates.minOf { it.row }
        val maxRow = candidates.maxOf { it.row }
        val minCol = candidates.minOf { it.col }
        val maxCol = candidates.maxOf { it.col }

        if ((maxCol - minCol) != expectedCols - 1) {
            notes.add("列跨度 ${maxCol - minCol + 1}，期望 $expectedCols：边缘列可能漏子，整盘会横向错位")
        }
        if ((maxRow - minRow) != expectedRows - 1) {
            notes.add("行跨度 ${maxRow - minRow + 1}，期望 $expectedRows：边缘行可能漏子，整盘会纵向错位")
        }

        val groups = HashMap<Int, MutableList<CellCandidate>>()
        for (c in candidates) {
            val r = c.row - minRow
            val col = c.col - minCol
            if (r !in 0 until expectedRows || col !in 0 until expectedCols) {
                notes.add("越界丢弃：原始(${c.row},${c.col}) 归一后($r,$col)")
                continue
            }
            groups.getOrPut(r * Board.COLS + col) { ArrayList() }.add(c)
        }

        return build(groups, notes, candidates)
    }

    /** 冲突消解 + 超限裁剪 + 建盘面。两个入口共用。 */
    private fun build(
        groups: Map<Int, MutableList<CellCandidate>>,
        notes: MutableList<String>,
        candidates: List<CellCandidate>
    ): Assembly {
        val conflicts = ArrayList<Conflict>()
        val kept = HashMap<Int, CellCandidate>(groups.size)
        for ((key, group) in groups) {
            val sorted = group.sortedByDescending { it.detection.score }
            kept[key] = sorted[0]
            if (sorted.size > 1) {
                conflicts.add(
                    Conflict(
                        square = Square(key / Board.COLS, key % Board.COLS),
                        kept = sorted[0].detection,
                        dropped = sorted.drop(1).map { it.detection }
                    )
                )
            }
        }

        val overLimit = ArrayList<Detection>()
        for (side in Side.values()) {
            for (type in PieceType.values()) {
                val hits = kept.entries
                    .filter { (_, c) ->
                        val p = c.detection.piece
                        p != null && p.type == type && p.side == side
                    }
                    .sortedByDescending { it.value.detection.score }
                if (hits.size > type.limit) {
                    for (entry in hits.drop(type.limit)) {
                        overLimit.add(entry.value.detection)
                        kept.remove(entry.key)
                    }
                }
            }
        }

        val builder = Board.builder()
        for ((key, c) in kept) {
            val piece = c.detection.piece ?: continue
            builder.set(key / Board.COLS, key % Board.COLS, piece)
        }
        val screenBoard = builder.build()
        val redOnTop = redSideOnTop(screenBoard)
        val board = if (redOnTop) screenBoard.flip180() else screenBoard

        val rowsUsed = kept.keys.map { it / Board.COLS }
        val colsUsed = kept.keys.map { it % Board.COLS }

        return Assembly(
            screenBoard = screenBoard,
            board = board,
            redOnTop = redOnTop,
            conflicts = conflicts,
            overLimit = overLimit,
            rowFrom = rowsUsed.minOrNull() ?: 0,
            rowTo = rowsUsed.maxOrNull() ?: -1,
            colFrom = colsUsed.minOrNull() ?: 0,
            colTo = colsUsed.maxOrNull() ?: -1,
            maxResidual = candidates.maxOfOrNull { it.residual } ?: 0f,
            notes = notes
        )
    }

    /**
     * 红方是否在画面上方。
     *
     * 用**将/帅的行号**判定，不用"哪个方向更像开局"：
     * 将帅永远在本方半场，这个判据在中局/残局同样成立；而"像开局"在残局直接失效。
     * 实测 5 张截图中 4 张是"红相已飞中"的教学局面，按开局判会直接判错方向。
     */
    fun redSideOnTop(board: Board): Boolean {
        var redKingRow: Int? = null
        var blackKingRow: Int? = null
        for (lp in board.pieces()) {
            if (lp.piece.type != PieceType.KING) continue
            if (lp.piece.side == Side.RED) redKingRow = lp.square.row else blackKingRow = lp.square.row
        }
        val red = redKingRow
        val black = blackKingRow
        if (red != null && black != null) return red < black
        if (red != null) return red <= 4
        if (black != null) return black > 4
        return false
    }
}
