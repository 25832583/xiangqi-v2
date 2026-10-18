package com.xiangqi.v2.core

/**
 * 中国象棋 FEN 编解码。
 *
 * 格式：`<棋盘> <走子方> - - <半步计数> <回合数>`，例如
 * `rnbakabnr/9/1c2c4/p1p1p1ppp/9/9/P1P1P1P1P/1C2B2C1/3N5/R2AKABNR b - - 0 1`
 *
 * 棋盘部分：从黑方底线（row 0）开始，每行 9 个坐标，数字表示连续空格，`/` 分行。
 * 红方棋子大写、黑方小写。这套字母与皮卡鱼一致，可以直接喂给引擎。
 */
object Fen {

    /** 标准开局局面（红先），炮在 b/h 线、兵在 a/c/e/g/i 线。 */
    const val START_FEN: String =
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    /** 只输出棋盘部分，可用作局面指纹（不含走子方）。 */
    fun boardPart(board: Board): String {
        val sb = StringBuilder(90)
        for (row in 0 until Board.ROWS) {
            var empty = 0
            for (col in 0 until Board.COLS) {
                val piece = board[row, col]
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(piece.fenChar)
                }
            }
            if (empty > 0) sb.append(empty)
            if (row < Board.ROWS - 1) sb.append('/')
        }
        return sb.toString()
    }

    fun encode(position: FenPosition): String = buildString {
        append(boardPart(position.board))
        append(' ').append(position.sideToMove.fenLetter)
        append(" - - ")
        append(position.halfmoveClock)
        append(' ')
        append(position.fullmoveNumber)
    }

    fun encode(board: Board, sideToMove: Side): String =
        encode(FenPosition(board, sideToMove))

    /**
     * 解析 FEN。只要棋盘部分合法就返回结果；走子方字段缺失时按红方处理。
     * 完全无法解析（棋盘部分不含任何行）时返回 null。
     */
    fun decode(fen: String): FenPosition? {
        val trimmed = fen.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        val board = Board.fromFenBoardPart(parts[0])
        val side = parts.getOrNull(1)?.firstOrNull()?.let { Side.fromFenLetter(it) } ?: Side.RED
        return FenPosition(
            board = board,
            sideToMove = side,
            halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0,
            fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1
        )
    }

    /** 校验 FEN 的走子方字段，非法返回 null。解析严格版，给外部输入用。 */
    fun decodeStrict(fen: String): FenPosition? {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val side = Side.fromFenLetter(parts[1].firstOrNull() ?: return null) ?: return null
        return decode(fen)?.copy(sideToMove = side)
    }
}

/** 一个完整局面：棋盘 + 轮到谁走 + FEN 计数字段。 */
data class FenPosition(
    val board: Board,
    val sideToMove: Side = Side.RED,
    val halfmoveClock: Int = 0,
    val fullmoveNumber: Int = 1
) {
    fun toFen(): String = Fen.encode(this)
}
