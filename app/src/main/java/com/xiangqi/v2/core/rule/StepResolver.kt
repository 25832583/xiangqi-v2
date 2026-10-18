package com.xiangqi.v2.core.rule

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.core.Square

/**
 * 一步可达性判据 —— 回答一个问题：
 *
 * > **这一帧的盘面，能不能由上一帧的盘面走一步棋得到？**
 *
 * 移植自旧工程 `xiangqi-assistant`（那边跑过真机），判定逻辑一行未改。
 *
 * ## 为什么 v2 需要它
 *
 * 在它之前，v2 的时序判据只有「连续两帧识别结果完全相同就采纳」。那是个**纯一致性**
 * 判据：它挡得住走子动画的中间态（中间态会连续变化，凑不满两帧相同），
 * 但它挡不住**稳定地错** —— 如果某一帧开始把一个仕认成马，而画面本身静止，
 * 那么后面每一帧都会认成马，**两帧当然完全一样**，判据直接放行，错误就此成为历史。
 *
 * 判据换成「能不能由上一帧走一步棋得到」之后，这两类问题一起被覆盖：
 * 「认错种类」和「凭空多/少一个子」都找不到任何走法能解释。
 *
 * ## 判据
 *
 * 一步棋必然同时改动**两格**：起点清空、落点被占（落点原本有子就是被吃）。
 * 于是有两条硬约束和一条容差：
 *
 *  1. **起点必须在差异集里** —— 只枚举差异格上原本有子的格作为起点，
 *     候选数从「全盘 32 子」降到「差异格数」；
 *  2. **落点也必须在差异集里** —— 这一条很关键，它直接挡掉两类假象：
 *     「一个子凭空消失」（差异集里只有起点那一格）和「一个子凭空出现」
 *     （差异集里只有落点那一格）。这两种都没有任何走法的「起点 + 落点」能同时对上。
 *  3. **容差 [maxStrayDiff]**：允许若干格「与走法无关的残留差异」，
 *     但**残留差异里不许出现「两格都有子、种类却不同」** —— 那种差异是
 *     分类抖动（把某子读成了另一个种类），一格都不容忍。
 *
 * ## 判据宁可宽，不要严
 *
 * 把「真实走子」误判成「解释不了」会丢好帧、让局面卡住，这比偶尔采纳一帧坏数据更糟
 * （用户完全不知道发生了什么）。所以：
 *  - [MoveGenerator] 生成的走法**不判将军**；
 *  - 允许 [maxStrayDiff] 格「一边有一边无」的检出抖动（真实走子常伴随一两格
 *    落在检测阈值边缘的抖动，卡死在 0 格会把这类帧全丢掉）；
 *  - 调用方 [com.xiangqi.v2.pipeline.BoardWatcher] 另有「连续丢帧达到上限就强制放行」
 *    的兜底，任何判据都不至于把界面锁死。
 */
object StepResolver {

    /**
     * 差异格数的上限。超过这个数就不必再算了 —— 一步棋最多改 2 格，
     * 差 20 格以上的两个局面之间不存在「一步可达」的可能，直接判不可解释。
     * 这道闸门只是省算力，不影响结论。
     */
    private const val MAX_DIFF_CELLS = 20

    /** 判据结论。 */
    sealed interface Verdict {

        /** 与上一盘面逐格相同 —— 没有任何变化要解释。 */
        data object NoChange : Verdict

        /**
         * 找到了能解释这组差异的走法。
         *
         * @param move         解释用的走法（多个走法都吻合时取枚举到的第一个，顺序固定可复现）
         * @param piece        走的是哪类棋子
         * @param side         走的是哪一方 —— **走出这一步的一方**，不是下一步该谁走
         * @param strayDiff    一步棋解释不到的**残留**差异格数（都是「一边有一边无」的检出抖动）。
         *                     0 = 完美吻合
         * @param alternatives 同样吻合的走法个数。>1 说明有歧义，但都是合法走子，照收
         */
        data class Explained(
            val move: Move,
            val piece: PieceType,
            val side: Side,
            val strayDiff: Int,
            val alternatives: Int
        ) : Verdict

        /**
         * 找不到任何走法能解释。
         *
         * @param closestStrayDiff 最接近的那个走法还差几格；一个候选都没有时为 -1
         * @param diffCells        实际差异格数
         */
        data class Unexplained(val closestStrayDiff: Int, val diffCells: Int) : Verdict
    }

    /**
     * 判断 [current] 能否由 [previous] 走一步棋得到。
     *
     * @param maxStrayDiff 允许几格「一边有一边无」的残留差异。默认 1 ——
     *                     恰好挡住「单格凭空变化」（差异集只有一格时，没有任何走法的
     *                     起点 + 落点能同时落进去），同时放过「走一步 + 一格检出抖动」。
     *                     设为 0 更严格，但会把后者也拒掉。
     */
    fun explain(previous: Board, current: Board, maxStrayDiff: Int = 1): Verdict {
        if (previous == current) return Verdict.NoChange

        val changed = diffCells(previous, current)
        if (changed.isEmpty()) return Verdict.NoChange
        if (changed.size > MAX_DIFF_CELLS) return Verdict.Unexplained(-1, changed.size)

        val changedSet = HashSet<Square>(changed)

        var bestStray = Int.MAX_VALUE
        var bestJitter = Int.MAX_VALUE
        var bestMove: Move? = null
        var bestPiece: PieceType? = null
        var bestSide: Side? = null
        var alternatives = 0

        for (square in changed) {
            val piece = previous[square] ?: continue
            for (move in MoveGenerator.movesOf(previous, square)) {
                // 约束二：落点也必须是差异格。一步棋改了起点和落点两格，
                // 落点没变说明这步棋根本没发生（「凭空消失/出现」都卡在这里）。
                if (move.toSquare !in changedSet) continue

                val stray = strayOf(applyMove(previous, move), current)
                if (stray.cells < bestStray ||
                    (stray.cells == bestStray && stray.typeJitter < bestJitter)
                ) {
                    bestStray = stray.cells
                    bestJitter = stray.typeJitter
                    bestMove = move
                    bestPiece = piece.type
                    bestSide = piece.side
                    alternatives = 1
                } else if (stray.cells == bestStray && stray.typeJitter == bestJitter) {
                    alternatives++
                }
            }
        }

        val move = bestMove
        if (move != null && bestStray <= maxStrayDiff && bestJitter == 0) {
            return Verdict.Explained(move, bestPiece!!, bestSide!!, bestStray, alternatives)
        }
        return Verdict.Unexplained(
            closestStrayDiff = if (move == null) -1 else bestStray,
            diffCells = changed.size
        )
    }

    /** 走出这一步之后的盘面（吃子由「覆盖落点」自然完成）。 */
    private fun applyMove(board: Board, move: Move): Board {
        val piece = board[move.fromRow, move.fromCol] ?: return board
        return board.with(move.fromRow, move.fromCol, null)
            .with(move.toRow, move.toCol, piece)
    }

    /** 两个盘面所有不同的格，按行优先顺序。 */
    private fun diffCells(a: Board, b: Board): List<Square> {
        val out = ArrayList<Square>(8)
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) if (a[r, c] != b[r, c]) out.add(Square(r, c))
        }
        return out
    }

    /**
     * 残留差异的构成。
     *
     * @param cells      与走法无关的差异格数
     * @param typeJitter 其中「两格都有子、种类却不同」的格数 —— 分类抖动，一格不容忍
     */
    private class Stray(val cells: Int, val typeJitter: Int)

    private fun strayOf(applied: Board, current: Board): Stray {
        var cells = 0
        var typeJitter = 0
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) {
                val a = applied[r, c]
                val b = current[r, c]
                if (a == b) continue
                cells++
                if (a != null && b != null) typeJitter++
            }
        }
        return Stray(cells, typeJitter)
    }
}
