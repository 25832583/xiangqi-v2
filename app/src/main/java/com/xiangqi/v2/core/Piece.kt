package com.xiangqi.v2.core

/** 阵营。FEN 中红方用大写字母、黑方用小写，与皮卡鱼（UCI 中国象棋）约定一致。 */
enum class Side(val displayName: String, val isRed: Boolean) {
    RED("红方", true),
    BLACK("黑方", false);

    val opponent: Side get() = if (this == RED) BLACK else RED

    /** FEN 的走子方字段：w = 红走，b = 黑走。 */
    val fenLetter: Char get() = if (isRed) 'w' else 'b'

    companion object {
        fun fromFenLetter(letter: Char): Side? = when (letter) {
            'w', 'W' -> RED
            'b', 'B' -> BLACK
            else -> null
        }
    }
}

/**
 * 棋子种类 —— 全工程唯一的「字母 / 中文名 / 局面上限」真相源。
 *
 * 重构前这套映射在四处各存一份（BoardRecognizer.LABELS、CellClassifier.TYPES、
 * ChessFen.pieceName、BoardView.pieceName），加一个种类或改一个写法要同步改四处，
 * 漏一处就出现「识别是一种、显示是另一种」的隐性 bug。
 * 现在只在这里定义，其它地方一律经本枚举取。
 *
 * [letter] 是红方（大写）形式；黑方用小写，见 [Piece.fenChar]。
 */
enum class PieceType(
    val letter: Char,
    /** 标准局面下单方上限：帅将 1，仕士相象马车炮 2，兵卒 5 */
    val limit: Int,
    val redName: String,
    val blackName: String
) {
    KING('K', 1, "帅", "将"),
    ADVISOR('A', 2, "仕", "士"),
    ELEPHANT('B', 2, "相", "象"),
    HORSE('N', 2, "马", "马"),
    CHARIOT('R', 2, "车", "车"),
    CANNON('C', 2, "炮", "炮"),
    PAWN('P', 5, "兵", "卒");

    fun displayName(side: Side): String = if (side == Side.RED) redName else blackName

    /**
     * 两类棋子的统一称呼，用于「某类数量超上限」这类双方合并的提示。
     * 红黑同名时只写一个（马/车/炮），不同名才写两个（帅/将、兵/卒）——
     * 与重构前的日志文案逐字一致，便于新旧版本比对 logcat。
     */
    val pairedName: String get() = if (redName == blackName) redName else "$redName/$blackName"

    companion object {
        private val BY_LETTER: Map<Char, PieceType> = values().associateBy { it.letter }

        /** 按 FEN 字母查种类，大小写均可；不认识的字母返回 null。 */
        fun fromLetter(letter: Char): PieceType? = BY_LETTER[letter.uppercaseChar()]

        /**
         * 走斜线的种类（马/仕/相象）。
         * 中文记谱里这些棋子的「进/退」后面跟的是目标纵线，而不是移动步数。
         */
        val DIAGONAL_MOVERS: Set<PieceType> = setOf(HORSE, ADVISOR, ELEPHANT)

        /** 数量上限表：[PieceType] → 单方上限，供检测后处理与校验共用。 */
        val LIMITS: Map<PieceType, Int> = values().associateWith { it.limit }
    }
}

/** 一枚棋子 = 种类 + 阵营。 */
data class Piece(val type: PieceType, val side: Side) {

    /** FEN 字母：红方大写，黑方小写。 */
    val fenChar: Char get() = if (side.isRed) type.letter else type.letter.lowercaseChar()

    val displayName: String get() = type.displayName(side)

    override fun toString(): String = fenChar.toString()

    companion object {
        /** 解析 FEN 字母；非棋子字母返回 null。 */
        fun fromFen(letter: Char): Piece? {
            val type = PieceType.fromLetter(letter) ?: return null
            return Piece(type, if (letter.isUpperCase()) Side.RED else Side.BLACK)
        }
    }
}
