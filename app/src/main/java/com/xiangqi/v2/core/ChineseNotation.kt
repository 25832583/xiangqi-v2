package com.xiangqi.v2.core

private val CN_DIGITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

/**
 * 记谱里的数字写法。抽成枚举是为了让「用汉字还是用阿拉伯数字」变成可替换的策略，
 * 而不是散在 [ChineseNotation.render] 里的 `if (isRed)` 分支。
 */
enum class NumeralStyle {
    /** 汉字数字：一 二 三 … 九。 */
    CHINESE {
        override fun render(n: Int): String = CN_DIGITS.getOrElse(n - 1) { n.toString() }
    },

    /** 阿拉伯数字：1 2 3 … 9。 */
    ARABIC {
        override fun render(n: Int): String = n.toString()
    };

    abstract fun render(n: Int): String
}

/**
 * 记谱风格。通行惯例是**红方用汉字数字、黑方用阿拉伯数字**（红「炮二平五」对黑「炮8平5」）。
 *
 * 想换成双方统一用汉字，传 `NotationStyle(NumeralStyle.CHINESE, NumeralStyle.CHINESE)` 即可，
 * [ChineseNotation.render] 的逻辑一行都不用改。
 */
data class NotationStyle(
    val redNumerals: NumeralStyle = NumeralStyle.CHINESE,
    val blackNumerals: NumeralStyle = NumeralStyle.ARABIC,
) {
    fun numeralsFor(side: Side): NumeralStyle = if (side.isRed) redNumerals else blackNumerals

    companion object {
        /** 通行记谱：红汉字、黑阿拉伯数字。 */
        val STANDARD = NotationStyle()

        /** 双方都用汉字数字（与重构前的旧行为一致，保留备用）。 */
        val ALL_CHINESE = NotationStyle(NumeralStyle.CHINESE, NumeralStyle.CHINESE)
    }
}

/**
 * 把走法渲染成中文记谱（如「炮二平五」「马8进7」）。
 *
 * 规则要点：
 *  - 纵线序号是**己方视角**：红方从右往左数 1..9，黑方从左往右数 1..9；
 *  - 横向移动记「平 + 目标纵线」；
 *  - 马/仕/相象走斜线，「进/退」后面跟目标纵线；
 *  - 车/炮/兵卒走直线，「进/退」后面跟移动步数；
 *  - 数字写法由 [NotationStyle] 决定（默认红汉字、黑阿拉伯数字）。
 *
 * 待扩展：同一纵线上有两个同类棋子时，标准记谱还要加「前/后」消歧（如「前车进一」）。
 * 当前识别场景下这类局面很少见，先不做；要加就在 [render] 里加一层消歧策略即可，
 * 调用方签名不用变。
 */
object ChineseNotation {

    fun render(fen: String, uciMove: String, style: NotationStyle = NotationStyle.STANDARD): String {
        val position = Fen.decode(fen)
        val move = Move.parseUci(uciMove)
        if (position == null || move == null) return uciMove
        return render(position.board, move, style)
    }

    fun render(
        position: FenPosition,
        move: Move,
        style: NotationStyle = NotationStyle.STANDARD,
    ): String = render(position.board, move, style)

    fun render(board: Board, move: Move, style: NotationStyle = NotationStyle.STANDARD): String {
        val piece = board[move.fromRow, move.fromCol] ?: return coordinateFallback(move)
        val side = piece.side
        val numerals = style.numeralsFor(side)
        val fromFileNum = fileNumber(move.fromCol, side)
        val toFileNum = fileNumber(move.toCol, side)
        val name = piece.displayName

        if (move.isHorizontal) {
            return "$name${numerals.render(fromFileNum)}平${numerals.render(toFileNum)}"
        }
        // 红方向前 = 行号变小（朝 row 0），黑方向前 = 行号变大
        val forward = if (side.isRed) move.toRow < move.fromRow else move.toRow > move.fromRow
        val direction = if (forward) "进" else "退"
        return if (piece.type in PieceType.DIAGONAL_MOVERS) {
            "$name${numerals.render(fromFileNum)}$direction${numerals.render(toFileNum)}"
        } else {
            "$name${numerals.render(fromFileNum)}$direction${numerals.render(move.rowSteps)}"
        }
    }

    /** 己方视角的纵线序号：红方从右往左 1..9，黑方从左往右 1..9。 */
    fun fileNumber(col: Int, side: Side): Int = if (side.isRed) Board.COLS - col else col + 1

    /** 起点没有棋子时退化成坐标串，保证界面永远有东西可显示而不是空白。 */
    private fun coordinateFallback(move: Move): String =
        "${move.fromFileChar}${move.fromRow + 1}${move.toFileChar}${move.toRow + 1}"
}
