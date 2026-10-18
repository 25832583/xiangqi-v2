package com.xiangqi.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.core.Side

/**
 * 面板里的小棋盘：自绘 10 行 × 9 列的格线、九宫、棋子，以及**推荐着法的箭头**。
 *
 * 为什么要有它：文字写着「炮二平五」，用户还得自己在棋盘上找那个炮在哪、要走到哪一格。
 * 一个箭头省掉这一步 —— 尤其残局里子少位偏，读记谱比看箭头慢得多。
 *
 * 为什么画在面板里而不是盖在真实棋盘上：悬浮窗会挡住棋盘，面板展开期间盯盘是暂停的，
 * 所以这里画的是**已采纳的那个盘面**，不存在"跟手不跟手"的问题。真要盖在游戏棋盘上，
 * 得把识别出的棋盘几何映射回屏幕坐标，那是另一件事（也确实在计划里）。
 *
 * 坐标系直接用 [Board] 的约定：row 0 在顶部（黑方底线），col 0 在左侧。也就是红方在下，
 * 与残局截图的常见摆法一致，不需要 [Move.mirror180]。
 */
class MiniBoardView(context: Context) : View(context) {

    private var board: Board? = null
    private var move: Move? = null
    private var side: Side = Side.RED

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
        color = 0xFF4A4A4A.toInt()
    }

    private val pieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF191919.toInt()
    }

    private val pieceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }

    private val pieceText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        color = ACCENT
    }

    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ACCENT
    }

    private val targetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = ACCENT
    }

    init {
        // 这个 View 只负责画，不接收事件：面板本身已经是 NOT_FOCUSABLE 的悬浮窗，
        // 让它可点只会和面板的拖动/点击抢事件
        isClickable = false
    }

    fun show(board: Board?, move: Move?, side: Side) {
        if (board === this.board && move == this.move && side == this.side) return
        this.board = board
        this.move = move
        this.side = side
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padX = dp(2f)
        val padY = dp(2f)
        val cellW = (width - 2 * padX) / (Board.COLS - 1)
        val cellH = (height - 2 * padY) / (Board.ROWS - 1)
        val cell = minOf(cellW, cellH)
        if (cell <= 0f) return

        val gridW = cell * (Board.COLS - 1)
        val gridH = cell * (Board.ROWS - 1)
        val x0 = (width - gridW) / 2f
        val y0 = (height - gridH) / 2f

        fun cx(col: Int) = x0 + col * cell
        fun cy(row: Int) = y0 + row * cell

        drawGrid(canvas, x0, y0, gridW, gridH, ::cx, ::cy)

        val current = board ?: return
        drawPieces(canvas, current, cell, ::cx, ::cy)
        move?.let { drawArrow(canvas, it, cell, ::cx, ::cy) }
    }

    /** 格线：两侧纵线通到底，中间七条在河界断开；再补两个九宫的斜线。 */
    private fun drawGrid(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        gridW: Float,
        gridH: Float,
        cx: (Int) -> Float,
        cy: (Int) -> Float
    ) {
        for (row in 0 until Board.ROWS) {
            canvas.drawLine(x0, cy(row), x0 + gridW, cy(row), gridPaint)
        }
        for (col in 0 until Board.COLS) {
            val x = cx(col)
            if (col == 0 || col == Board.COLS - 1) {
                canvas.drawLine(x, y0, x, y0 + gridH, gridPaint)
            } else {
                // 中间七条纵线在河界断开，这是象棋棋盘的样子
                canvas.drawLine(x, y0, x, cy(4), gridPaint)
                canvas.drawLine(x, cy(5), x, y0 + gridH, gridPaint)
            }
        }
        // 九宫斜线：上下各一个 3×3 的米字格
        for (top in intArrayOf(0, 7)) {
            canvas.drawLine(cx(3), cy(top), cx(5), cy(top + 2), gridPaint)
            canvas.drawLine(cx(5), cy(top), cx(3), cy(top + 2), gridPaint)
        }
    }

    private fun drawPieces(
        canvas: Canvas,
        board: Board,
        cell: Float,
        cx: (Int) -> Float,
        cy: (Int) -> Float
    ) {
        val radius = cell * PIECE_RADIUS_RATIO
        pieceText.textSize = cell * 0.62f
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val piece = board[row, col] ?: continue
                val x = cx(col)
                val y = cy(row)
                val tint = if (piece.side.isRed) RED else BLACK

                canvas.drawCircle(x, y, radius, pieceFill)
                pieceStroke.color = tint
                canvas.drawCircle(x, y, radius, pieceStroke)

                pieceText.color = tint
                val baseline = y - (pieceText.descent() + pieceText.ascent()) / 2f
                canvas.drawText(piece.displayName, x, baseline, pieceText)
            }
        }
    }

    /**
     * 推荐着法的箭头：起点画一个空心圈（"这枚子"），终点画箭头三角。
     *
     * 起终点默认停在棋子外沿 —— 线画进棋子圆里会让文字糊掉，而记谱里"起点是什么子"
     * 和"落在哪一格"恰恰是两件都要看清的事。
     *
     * **但"停在棋子外沿"不能无条件执行**：走一格时两圆之间只剩 0.16 个格距（≈3dp），
     * 两端都按「半径 + 1dp」让完是负长度，箭头连起点圈都会被丢掉。让出量带上限、
     * 允许压进圆边约 2dp（仍在汉字外沿之外）这件事交给 [ArrowGeometry] 算。
     */
    private fun drawArrow(
        canvas: Canvas,
        move: Move,
        cell: Float,
        cx: (Int) -> Float,
        cy: (Int) -> Float
    ) {
        val fromX = cx(move.fromCol)
        val fromY = cy(move.fromRow)
        val toX = cx(move.toCol)
        val toY = cy(move.toRow)

        val dx = toX - fromX
        val dy = toY - fromY
        val length = kotlin.math.hypot(dx, dy)
        val radius = cell * PIECE_RADIUS_RATIO
        val layout = ArrowGeometry.layout(
            length = length,
            radius = radius,
            edgeGap = dp(1f),
            minShaft = dp(8f),
            maxHead = dp(6f)
        ) ?: return
        val ux = dx / length
        val uy = dy / length

        val startX = fromX + ux * layout.margin
        val startY = fromY + uy * layout.margin
        val endX = toX - ux * layout.margin
        val endY = toY - uy * layout.margin

        // 起点圈：标出"走的是这枚子"
        canvas.drawCircle(fromX, fromY, radius + dp(1.2f), targetRing)
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)

        val head = layout.head
        val halfWidth = layout.halfWidth
        val path = Path().apply {
            moveTo(endX, endY)
            lineTo(endX - ux * head - uy * halfWidth, endY - uy * head + ux * halfWidth)
            lineTo(endX - ux * head + uy * halfWidth, endY - uy * head - ux * halfWidth)
            close()
        }
        canvas.drawPath(path, arrowHead)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val ACCENT = 0xFFFFD24A.toInt()
        const val RED = 0xFFFF7A66.toInt()
        const val BLACK = 0xFFA9C0D6.toInt()

        /**
         * 棋子半径 / 格距。0.42 是"棋子几乎贴在一起"的比例 —— 棋盘本来就小
         * （格距约 21.8dp），再小就看不清字。代价是走一格时两圆之间几乎没空隙，
         * 见 [drawArrow] 与 [ArrowGeometry]。
         */
        const val PIECE_RADIUS_RATIO = 0.42f
    }
}
