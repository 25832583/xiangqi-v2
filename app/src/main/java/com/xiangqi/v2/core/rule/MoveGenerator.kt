package com.xiangqi.v2.core.rule

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.core.Square
import kotlin.math.abs

/**
 * 走法生成器 —— 只生成**伪合法**走法。
 *
 * 移植自旧工程 `xiangqi-assistant`（同一份判据在那边跑过真机），搬到 v2 后
 * 包名与 `[PieceType]` 枚举对齐，判定逻辑一行未改。
 *
 * 「伪合法」的含义：棋子自身的走法几何（车走直线、马走日、炮翻山、兵过河可横走……）、
 * 不能吃己方子、不能出九宫、相不过河、兵不后退 —— 这些都判；但**不判将军**，
 * 所以「走完自己被将死」这种着法也会被生成出来。
 *
 * 为什么故意不判将军：
 * 本生成器的唯一用途是[给「这一帧与上一帧的差异」找一个解释][StepResolver]，
 * 判据宁可宽也不能严 —— 判严了会把**真实走子**误判成「无法解释」，进而把好帧丢掉，
 * 表现成局面卡住不动。而真实对局里的绝大多数走法都不会让自己被将死，
 * 所以放宽这一条几乎不会引入误判；反过来误检造成的差异（一个子凭空变种、
 * 瞬移、多出来一个子）**基本不可能**恰好落进某条走法几何里，该挡的仍然挡得住。
 *
 * 全部坐标用 [Board] 的约定：row 0 = 黑方底线，row 9 = 红方底线；
 * 红方朝 row 减小的方向前进，黑方朝 row 增大的方向前进。
 */
object MoveGenerator {

    /** 四正方向：上 下 左 右。 */
    private val ORTHO = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /** 四斜方向：四个角。仕走一格、相走两格都用它。 */
    private val DIAG = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

    /** 马的八个落点。 */
    private val HORSE_STEPS = arrayOf(
        -2 to -1, -2 to 1, -1 to -2, -1 to 2,
        1 to -2, 1 to 2, 2 to -1, 2 to 1
    )

    /**
     * [from] 上那枚棋子的全部伪合法走法；[from] 没有棋子时返回空表。
     *
     * 顺序是固定的（先生成哪条走法、后生成哪条走法每次都一样），
     * 因为 [StepResolver] 在多个走法都能解释同一组差异时取「第一个」，
     * 结果必须可复现，否则日志与测试都会飘。
     */
    fun movesOf(board: Board, from: Square): List<Move> {
        val piece = board[from] ?: return emptyList()
        val out = ArrayList<Move>(8)
        val r = from.row
        val c = from.col

        /** 落点能否走：越界、吃己方子都不行。 */
        fun step(tr: Int, tc: Int) {
            if (!inside(tr, tc)) return
            val target = board[tr, tc]
            if (target != null && target.side == piece.side) return
            out.add(Move(c, r, tc, tr))
        }

        when (piece.type) {
            PieceType.KING -> {
                // 帅/将只在九宫内直走一格。不实现「飞将」（帅直接吃对面将）——
                // 那种着法在真实对局里几乎不出现，为它增加一条判据不划算。
                val rows = palaceRows(piece)
                for ((dr, dc) in ORTHO) {
                    val tr = r + dr
                    val tc = c + dc
                    if (tr in rows && tc in PALACE_COLS) step(tr, tc)
                }
            }

            PieceType.ADVISOR -> {
                val rows = palaceRows(piece)
                for ((dr, dc) in DIAG) {
                    val tr = r + dr
                    val tc = c + dc
                    if (tr in rows && tc in PALACE_COLS) step(tr, tc)
                }
            }

            PieceType.ELEPHANT -> {
                // 走田字、不过河；田字中心被占（塞象眼）就走不了。
                val rows = if (piece.side.isRed) 5..9 else 0..4
                for ((dr, dc) in DIAG) {
                    val tr = r + dr * 2
                    val tc = c + dc * 2
                    if (tr !in rows || tc !in 0 until Board.COLS) continue
                    val eyeR = r + dr
                    val eyeC = c + dc
                    if (!inside(eyeR, eyeC) || board[eyeR, eyeC] != null) continue
                    step(tr, tc)
                }
            }

            PieceType.HORSE -> {
                // 走日字；先直走的那一格被占（蹩马腿）就走不了。
                for ((dr, dc) in HORSE_STEPS) {
                    val legR = r + if (abs(dr) == 2) dr / 2 else 0
                    val legC = c + if (abs(dc) == 2) dc / 2 else 0
                    if (!inside(legR, legC) || board[legR, legC] != null) continue
                    step(r + dr, c + dc)
                }
            }

            PieceType.CHARIOT -> walkStraight(board, piece, r, c, out)

            PieceType.CANNON -> walkCannon(board, piece, r, c, out)

            PieceType.PAWN -> {
                // 兵/卒永不后退。红方朝 row 减小走，黑方朝 row 增大走。
                val forward = if (piece.side.isRed) -1 else 1
                step(r + forward, c)
                // 过了河才能横走。
                if (crossedRiver(piece.side, r)) {
                    step(r, c - 1)
                    step(r, c + 1)
                }
            }
        }
        return out
    }

    /** 车：沿四个方向一路走，路径必须全空；遇到第一枚棋子时，是敌子可以吃，立即停。 */
    private fun walkStraight(board: Board, piece: Piece, r: Int, c: Int, out: MutableList<Move>) {
        for ((dr, dc) in ORTHO) {
            var tr = r + dr
            var tc = c + dc
            while (inside(tr, tc)) {
                val target = board[tr, tc]
                if (target == null) {
                    out.add(Move(c, r, tc, tr))
                } else {
                    if (target.side != piece.side) out.add(Move(c, r, tc, tr))
                    break
                }
                tr += dr
                tc += dc
            }
        }
    }

    /**
     * 炮：不吃子时与车完全一样；吃子必须**恰好翻过一个子**（炮架），隔两个子够不着。
     * 炮架是敌是友都行 —— 这是象棋的规则，不是漏判。
     */
    private fun walkCannon(board: Board, piece: Piece, r: Int, c: Int, out: MutableList<Move>) {
        for ((dr, dc) in ORTHO) {
            var tr = r + dr
            var tc = c + dc
            var screened = false
            while (inside(tr, tc)) {
                val target = board[tr, tc]
                if (!screened) {
                    if (target == null) out.add(Move(c, r, tc, tr)) else screened = true
                } else if (target != null) {
                    if (target.side != piece.side) out.add(Move(c, r, tc, tr))
                    break
                }
                tr += dr
                tc += dc
            }
        }
    }

    /** 帅/将所在的九宫行区间。 */
    private fun palaceRows(piece: Piece): IntRange = if (piece.side.isRed) 7..9 else 0..2

    /** 兵/卒是否已过河：红兵在 row 4 及以下、黑卒在 row 5 及以上即已过河。 */
    fun crossedRiver(side: Side, row: Int): Boolean =
        if (side.isRed) row <= 4 else row >= 5

    private val PALACE_COLS = 3..5

    private fun inside(r: Int, c: Int): Boolean =
        r in 0 until Board.ROWS && c in 0 until Board.COLS
}
