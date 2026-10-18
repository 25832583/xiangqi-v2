package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 锚定结果：把「任意平移」的行列号减去这组偏移，就得到棋盘坐标（0..9 / 0..8）。
 *
 * [score] 越小越合法，[considered] 是搜索了多少组偏移。
 */
data class AnchorResult(
    val colOffset: Int,
    val rowOffset: Int,
    val score: Float,
    val considered: Int
)

/**
 * 用**象棋规则**把周期拟合出来的行/列索引锚定到棋盘左上角。
 *
 * ## 为什么必须有这一步
 * [AxisFitter] 解出的 `phase` 只是「模 pitch 的等价类」——
 * `phase`、`phase + pitch`、`phase + 2·pitch` 对拟合残差完全等价。
 * 所以 [GridSnap] 得到的行号列号带着一个**未知的整数平移**。
 *
 * ## 旧做法为什么错
 * 早期用「检出的最小行/最小列」当 0 位移（等价于把最靠左上角的棋子按到 (0,0)）。
 * 只要棋盘最外一列或最上一行**恰好没有棋子**（中局残局极常见，比如 9 路的兵已经过河），
 * 整盘就会横向/纵向错开一整格。19 张真实对局截图实测：每张 13~20 个棋子越界。
 *
 * ## 现在的做法
 * 在几何先验（棋盘框中心 ≈ (列 4, 行 4.5)）附近搜一小圈整数偏移，
 * 对每组偏移按**象棋规则**打分，取最合法的一组。规则与皮肤、分辨率、字体全都无关：
 *
 *  1. 帅/将必在列 4，必在行 0 或 9，且两者行号相差 9；
 *  2. 仕/士、相/象 必与己方帅/将同半区（行的上半或下半）；
 *  3. 兵/卒 不能倒退超过一格（相对己方帅/将的行号）；
 *  4. 每类棋子数不超上限；
 *  5. 落到棋盘外的检测越少越好。
 *
 * 命中 1+2 就几乎唯一确定了偏移。19 张真实截图 + 5 张登记真值的图全部锚定正确。
 */
object RulesAnchor {

    /** 棋盘框中心在格坐标里的期望位置（实测 19 张：4.03±0.01 / 4.47±0.04）。 */
    private const val CENTER_COL = 4.0f
    private const val CENTER_ROW = 4.5f

    /** 在几何先验附近搜多大范围（格）。 */
    private const val SEARCH_RADIUS = 2.2f

    // 各项违规的代价（单位任意，只要相对大小合理）
    private const val COST_KING_COL = 60f
    private const val COST_KING_ROW = 60f
    private const val COST_KING_GAP = 80f
    private const val COST_GUARD_HALF = 40f
    private const val COST_PAWN_BACK = 20f
    private const val COST_OVER_LIMIT = 15f
    private const val COST_OUT_OF_BOARD = 8f
    private const val COST_GEO_DEVIATION = 1.5f

    /**
     * @param candidates [GridSnap] 的输出，行列号仍是未锚定的
     * @param fitX       横向拟合结果（用它的 pitch / phase 反算几何先验）
     * @param fitY       纵向拟合结果
     * @param boardBox   棋盘框（帧像素坐标，与 candidates 同一空间）
     * @return null 表示候选为空
     */
    fun anchor(
        candidates: List<CellCandidate>,
        fitX: AxisFit,
        fitY: AxisFit,
        boardBox: Box
    ): AnchorResult? {
        if (candidates.isEmpty()) return null

        val geoCol = (boardBox.centerX - fitX.phase) / fitX.pitch - CENTER_COL
        val geoRow = (boardBox.centerY - fitY.phase) / fitY.pitch - CENTER_ROW

        val colFrom = floor(geoCol - SEARCH_RADIUS).toInt()
        val colTo = ceil(geoCol + SEARCH_RADIUS).toInt()
        val rowFrom = floor(geoRow - SEARCH_RADIUS).toInt()
        val rowTo = ceil(geoRow + SEARCH_RADIUS).toInt()

        var best: AnchorResult? = null
        var considered = 0
        for (colOff in colFrom..colTo) {
            for (rowOff in rowFrom..rowTo) {
                considered++
                val s = score(candidates, colOff, rowOff) +
                    COST_GEO_DEVIATION * (abs(colOff - geoCol) + abs(rowOff - geoRow))
                if (best == null || s < best.score) {
                    best = AnchorResult(colOff, rowOff, s, 0)
                }
            }
        }
        return best?.copy(considered = considered)
    }

    /** 对一组偏移打分，分越低越符合象棋规则。 */
    fun score(candidates: List<CellCandidate>, colOff: Int, rowOff: Int): Float {
        // 每格取分数最高的那个（吃掉重影）
        val best = HashMap<Int, CellCandidate>()
        var placedCells = 0
        for (c in candidates) {
            val r = c.row - rowOff
            val col = c.col - colOff
            if (r !in 0 until Board.ROWS || col !in 0 until Board.COLS) continue
            placedCells++
            val key = r * Board.COLS + col
            val prev = best[key]
            if (prev == null || c.detection.score > prev.detection.score) best[key] = c
        }

        var s = COST_OUT_OF_BOARD * (candidates.size - placedCells).coerceAtLeast(0)

        // 1. 帅/将
        var redKing: IntArray? = null
        var blackKing: IntArray? = null
        for ((key, c) in best) {
            val piece = c.detection.piece ?: continue
            if (piece.type != PieceType.KING) continue
            val rc = intArrayOf(key / Board.COLS, key % Board.COLS)
            if (piece.side == Side.RED) redKing = rc else blackKing = rc
        }
        for (k in listOfNotNull(redKing, blackKing)) {
            if (k[1] != 4) s += COST_KING_COL
            if (k[0] != 0 && k[0] != Board.ROWS - 1) s += COST_KING_ROW
        }
        if (redKing != null && blackKing != null) {
            if (abs(redKing[0] - blackKing[0]) != Board.ROWS - 1) s += COST_KING_GAP
        }

        // 2 & 3. 士象同半区、兵卒不倒退
        for ((key, c) in best) {
            val piece = c.detection.piece ?: continue
            val r = key / Board.COLS
            val kingRow = when (piece.side) {
                Side.RED -> redKing?.get(0)
                Side.BLACK -> blackKing?.get(0)
            } ?: continue
            when (piece.type) {
                PieceType.ADVISOR, PieceType.ELEPHANT -> {
                    val kingHalf = if (kingRow <= 4) 0 else 1
                    val thisHalf = if (r <= 4) 0 else 1
                    if (kingHalf != thisHalf) s += COST_GUARD_HALF
                }
                PieceType.PAWN -> {
                    // 兵卒朝对方底线走：己方将在下半区 => 前进方向是行号减小
                    val forward = if (kingRow >= 5) -1 else 1
                    if ((r - kingRow) * forward < -1) s += COST_PAWN_BACK
                }
                else -> Unit
            }
        }

        // 4. 同类超限
        val counts = HashMap<Char, Int>()
        for ((_, c) in best) {
            val lab = c.detection.label
            counts[lab] = (counts[lab] ?: 0) + 1
        }
        for ((lab, n) in counts) {
            val lim = Labels.limitOf(lab)
            if (n > lim) s += COST_OVER_LIMIT * (n - lim)
        }

        return s
    }
}
