package com.xiangqi.v2.core

/** 棋盘上的一个坐标点。 */
data class Square(val row: Int, val col: Int) {
    val valid: Boolean get() = row in 0 until Board.ROWS && col in 0 until Board.COLS
}

/** 带坐标的棋子，遍历棋盘时用。 */
data class LocatedPiece(val square: Square, val piece: Piece)

/**
 * 不可变棋盘。
 *
 * 坐标约定 —— 全工程只有这一种，不再各自解释：
 *   - row 0 = 黑方底线（棋盘顶部 / FEN 第一行），row 9 = 红方底线；
 *   - col 0 = 最左侧纵线（a 线），col 8 = 最右侧纵线（i 线）。
 *
 * 识别产出的是「画面方向」，交给 [Fen] 编码或做任何棋规判断之前，必须先按用户视角
 * 用 [flip180] 归一到这个标准方向 —— 结构性规则（兵卒未过河不换纵线、相象不过河、
 * 仕士不出九宫）全都假定标准方向。
 *
 * 所有「修改」都返回新实例，因此可以安全地在多线程间传递，也不会出现
 * 「调用方拿到引用后被下游就地改写」这类隐蔽 bug。
 */
class Board private constructor(private val cells: Array<Array<Piece?>>) {

    /** 越界返回 null，调用方不必自己判边界。 */
    operator fun get(row: Int, col: Int): Piece? {
        if (row !in 0 until ROWS || col !in 0 until COLS) return null
        return cells[row][col]
    }

    operator fun get(square: Square): Piece? = get(square.row, square.col)

    /** 返回把 [row],[col] 换成 [piece] 之后的新棋盘。 */
    fun with(row: Int, col: Int, piece: Piece?): Board {
        if (row !in 0 until ROWS || col !in 0 until COLS) return this
        val copy = Array(ROWS) { r -> cells[r].clone() }
        copy[row][col] = piece
        return Board(copy)
    }

    fun pieceCount(): Int {
        var n = 0
        for (r in 0 until ROWS) for (c in 0 until COLS) if (cells[r][c] != null) n++
        return n
    }

    fun countOf(type: PieceType, side: Side? = null): Int {
        var n = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val p = cells[r][c] ?: continue
                if (p.type == type && (side == null || p.side == side)) n++
            }
        }
        return n
    }

    fun countOf(piece: Piece): Int = countOf(piece.type, piece.side)

    fun firstSquareOf(piece: Piece): Square? {
        for (r in 0 until ROWS) for (c in 0 until COLS) if (cells[r][c] == piece) return Square(r, c)
        return null
    }

    /** 按行优先顺序遍历全部棋子。 */
    fun pieces(): List<LocatedPiece> {
        val out = ArrayList<LocatedPiece>(32)
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) cells[r][c]?.let { out.add(LocatedPiece(Square(r, c), it)) }
        }
        return out
    }

    /** 180° 旋转：既用于「按用户视角显示」，也用于把画面方向归一成标准方向。 */
    fun flip180(): Board {
        val out = Array(ROWS) { arrayOfNulls<Piece>(COLS) }
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) out[r][c] = cells[ROWS - 1 - r][COLS - 1 - c]
        }
        return Board(out)
    }

    /**
     * 与另一盘面不同的格数。
     * 时序校验用：合法走子一次只改 1~2 格，差异过大说明识别在抖。
     */
    fun diffCount(other: Board): Int {
        var diff = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) if (cells[r][c] != other.cells[r][c]) diff++
        }
        return diff
    }

    /** 某阵营棋子的平均行号；没有该阵营棋子时返回 null。方向判定用。 */
    fun averageRow(side: Side): Double? {
        var sum = 0.0
        var n = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (cells[r][c]?.side == side) { sum += r; n++ }
            }
        }
        return if (n == 0) null else sum / n
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) if (cells[r][c] != other.cells[r][c]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 17
        for (r in 0 until ROWS) for (c in 0 until COLS) h = h * 31 + (cells[r][c]?.hashCode() ?: 0)
        return h
    }

    /** 输出 FEN 的棋盘部分，便于日志与做局面指纹。 */
    override fun toString(): String = Fen.boardPart(this)

    /** 便于构建：可变的临时棋盘，build() 之后不可再改。 */
    class Builder {
        private val cells = Array(ROWS) { arrayOfNulls<Piece>(COLS) }

        fun get(row: Int, col: Int): Piece? =
            if (row in 0 until ROWS && col in 0 until COLS) cells[row][col] else null

        fun get(square: Square): Piece? = get(square.row, square.col)

        fun set(row: Int, col: Int, piece: Piece?): Builder {
            if (row in 0 until ROWS && col in 0 until COLS) cells[row][col] = piece
            return this
        }

        fun set(square: Square, piece: Piece?): Builder = set(square.row, square.col, piece)

        fun count(): Int {
            var n = 0
            for (r in 0 until ROWS) for (c in 0 until COLS) if (cells[r][c] != null) n++
            return n
        }

        fun build(): Board = Board(Array(ROWS) { r -> cells[r].clone() })
    }

    companion object {
        const val ROWS = 10
        const val COLS = 9

        private val EMPTY_BOARD = Board(Array(ROWS) { arrayOfNulls<Piece>(COLS) })

        fun empty(): Board = EMPTY_BOARD

        fun builder(): Builder = Builder()

        fun of(cells: Array<Array<Piece?>>): Board {
            require(cells.size == ROWS) { "棋盘必须是 $ROWS 行，实际 ${cells.size}" }
            return Board(Array(ROWS) { r ->
                require(cells[r].size == COLS) { "第 $r 行必须是 $COLS 列，实际 ${cells[r].size}" }
                cells[r].clone()
            })
        }

        /**
         * 用 FEN 的棋盘部分建盘，方便测试与手写局面。
         * 不足 10 行补空行；每行容忍用 '.' 或 ' ' 表示空格。
         */
        fun fromFenBoardPart(boardPart: String): Board {
            val builder = builder()
            val ranks = boardPart.trim().split('/')
            for (row in 0 until minOf(ROWS, ranks.size)) {
                var col = 0
                for (ch in ranks[row]) {
                    when {
                        ch in '1'..'9' -> col += ch - '0'
                        ch == '.' || ch == ' ' -> col++
                        else -> {
                            Piece.fromFen(ch)?.let { if (col < COLS) builder.set(row, col, it) }
                            col++
                        }
                    }
                }
            }
            return builder.build()
        }
    }
}
