package com.xiangqi.v2.core

/**
 * 「这一局能不能送进引擎」的最后一道关。
 *
 * 为什么识别链路不需要它、引擎链路必须有：识别层是「宁可少认一颗，也不猜」，
 * 但少认一颗的盘面在象棋里可能是**根本不存在**的（红帅没认出来、两个黑将认成一个）。
 * 人看得出这是识别问题，引擎看不出来：喂给它一个物理上不可能的局面，
 * 轻则给不出任何着法，重则直接 `CRITICAL ERROR: Unsupported position` 然后退出进程
 * （皮卡鱼实测就是这样）。
 *
 * 所以判据只取**绝对成立**的那些 —— 与具体局面无关、象棋规则本身禁止的：
 *  1. 帅、将各一个；
 *  2. 总子数 2..32；
 *  3. 每类棋子单方不超过 [PieceType.limit]（象棋没有升变，某方出现 3 个马就是认错了）；
 *  4. 将帅照面（同一纵线且中间无子）；
 *  5. 兵卒不在己方底线上（过了河的兵回不去，底线上的兵从来不存在）。
 *
 * 假定棋盘处于**标准方向**（row 0 = 黑方底线，红方在下），调用方需自行翻转画面方向 ——
 * 识别层已经做好了，见 `Assembly.board`。
 */
object BoardSanity {

    /**
     * @return null 表示可以送引擎；否则返回**给用户看的原因**（同时写进日志）。
     */
    fun check(board: Board): String? {
        val redKing = board.countOf(PieceType.KING, Side.RED)
        val blackKing = board.countOf(PieceType.KING, Side.BLACK)
        if (redKing != 1 || blackKing != 1) {
            return "红帅 $redKing 个／黑将 $blackKing 个"
        }

        val total = board.pieceCount()
        if (total > 32 || total < 2) {
            return "盘面 $total 子（应为 2~32）"
        }

        for (type in PieceType.values()) {
            for (side in Side.values()) {
                val n = board.countOf(type, side)
                if (n > type.limit) {
                    return "${side.displayName} $n 个${type.displayName(side)}（上限 ${type.limit}）"
                }
            }
        }

        if (kingsFacing(board)) return "将帅照面"

        // 兵卒底线：红兵（row 9）与黑卒（row 0）都不可能存在
        for (col in 0 until Board.COLS) {
            if (board[Board.ROWS - 1, col]?.let { it.type == PieceType.PAWN && it.side == Side.RED } == true) {
                return "红兵在己方底线"
            }
            if (board[0, col]?.let { it.type == PieceType.PAWN && it.side == Side.BLACK } == true) {
                return "黑卒在己方底线"
            }
        }
        return null
    }

    /** 将帅同列、中间没有任何子 —— 象棋里不允许出现，引擎同样处理不了。 */
    private fun kingsFacing(board: Board): Boolean {
        val red = board.firstSquareOf(Piece(PieceType.KING, Side.RED))
        val black = board.firstSquareOf(Piece(PieceType.KING, Side.BLACK))
        if (red == null || black == null || red.col != black.col) return false
        val lo = minOf(red.row, black.row)
        val hi = maxOf(red.row, black.row)
        for (row in lo + 1 until hi) {
            if (board[row, red.col] != null) return false
        }
        return true
    }
}
