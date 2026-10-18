package com.xiangqi.v2.core

/**
 * 一步棋，用棋盘行列表达。
 *
 * 行列与 [Board] 同一套约定：row 0 = 黑方底线，col 0 = a 线。
 * 皮卡鱼/引擎侧的 UCI 坐标（如 `h2e2`）与这里相差一个行号翻转，
 * 转换集中在 [toUci] / [parseUci]，不要在别处手写 `9 - rank`。
 */
data class Move(val fromCol: Int, val fromRow: Int, val toCol: Int, val toRow: Int) {

    val fromSquare: Square get() = Square(fromRow, fromCol)
    val toSquare: Square get() = Square(toRow, toCol)

    val fromFileChar: Char get() = 'a' + fromCol
    val toFileChar: Char get() = 'a' + toCol

    /** 是否横向移动（中文记谱的「平」）。 */
    val isHorizontal: Boolean get() = fromRow == toRow

    /** 行方向上的步数（取绝对值，用于马/车/炮兵卒的记谱）。 */
    val rowSteps: Int get() = kotlin.math.abs(toRow - fromRow)

    /**
     * 转成引擎用的 UCI 串。皮卡鱼的 rank 0 = 棋盘底部（红方底线），
     * 与 [Board] 的 row 相反，所以这里做 `9 - row`。
     */
    fun toUci(): String = buildString(4) {
        append(fromFileChar)
        append(9 - fromRow)
        append(toFileChar)
        append(9 - toRow)
    }

    /**
     * 180° 旋转，用于「标准方向 → 用户视角方向」的显示。
     * 棋盘画在屏幕上时如果红方在上，标注走法前必须先转一次，否则箭头会画反。
     */
    fun mirror180(): Move =
        Move(Board.COLS - 1 - fromCol, Board.ROWS - 1 - fromRow, Board.COLS - 1 - toCol, Board.ROWS - 1 - toRow)

    override fun toString(): String = toUci()

    companion object {
        /**
         * 解析 UCI 走法，如 `h2e2`。格式不合法返回 null（不抛异常）。
         * 非法输入来自引擎输出或外部粘贴，这里必须容错而不是让上层崩掉。
         */
        fun parseUci(uci: String): Move? {
            val s = uci.trim()
            if (s.length < 4) return null
            val fromCol = s[0] - 'a'
            val fromRank = s[1] - '0'
            val toCol = s[2] - 'a'
            val toRank = s[3] - '0'
            if (fromCol !in 0 until Board.COLS || toCol !in 0 until Board.COLS) return null
            if (fromRank !in 0 until Board.ROWS || toRank !in 0 until Board.ROWS) return null
            return Move(fromCol, Board.ROWS - 1 - fromRank, toCol, Board.ROWS - 1 - toRank)
        }
    }
}
