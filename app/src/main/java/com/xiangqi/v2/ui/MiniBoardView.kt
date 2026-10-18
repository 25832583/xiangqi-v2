package com.xiangqi.v2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.core.Side

/**
 * 面板里的小棋盘：自绘 10 行 × 9 列的格线、九宫、棋子，以及**推荐着法的标记**。
 *
 * 为什么要有它：文字写着「炮二平五」，用户还得自己在棋盘上找那个炮在哪、要走到哪一格。
 * 一个标记省掉这一步 —— 尤其残局里子少位偏，读记谱比看标记慢得多。
 *
 * 为什么画在面板里而不是盖在真实棋盘上：悬浮窗会挡住棋盘，面板展开期间盯盘是暂停的，
 * 所以这里画的是**已采纳的那个盘面**，不存在"跟手不跟手"的问题。真要盖在游戏棋盘上，
 * 得把识别出的棋盘几何映射回屏幕坐标，那是另一件事（也确实在计划里）。
 *
 * 坐标系用 [Board] 的约定：row 0 是黑方底线，col 0 是 a 线。但**画在屏幕上的位置**
 * 还要过一次 [BoardOrientation]：用户在平台上下棋时自己永远在下方，黑方用户看到的
 * 画面与标准方向差 180°，面板得按画面那个视角画，否则他得在脑子里转半圈。
 */
class MiniBoardView(context: Context) : View(context) {

    private var board: Board? = null
    private var move: Move? = null
    private var side: Side = Side.RED

    /** 是否按「画面视角」翻 180°（红方在上时翻）。见 [BoardOrientation]。 */
    private var flipped: Boolean = false

    /** 置信度偏低的格子（`row * 9 + col`），画虚线圈提醒「这颗可能认错了」。 */
    private var uncertain: Set<Int> = emptySet()

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

    /** 着法标记：四角框。颜色取自 JJ象棋 自己的"上一手"标记（深蓝），见 [MarkerGeometry]。 */
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 1.5dp：参考图换算过来约 0.7dp，但那是木色棋盘、格距是这里的三倍。
        // 面板是暗底 + 20dp 小格，再细就糊在格线里了（格线 0.9dp）。
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.SQUARE
        color = MARKER
    }

    /** 低置信标记：青色虚线圆。形状与四角框完全不同，不会被误读成着法提示。 */
    private val uncertainRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        color = WARN
        pathEffect = DashPathEffect(floatArrayOf(dp(2.5f), dp(2f)), 0f)
    }

    init {
        // 这个 View 只负责画，不接收事件：面板本身已经是 NOT_FOCUSABLE 的悬浮窗，
        // 让它可点只会和面板的拖动/点击抢事件
        isClickable = false
    }

    fun show(
        board: Board?,
        move: Move?,
        side: Side,
        uncertain: Set<Int> = emptySet(),
        flipped: Boolean = false
    ) {
        if (board === this.board && move == this.move && side == this.side &&
            uncertain == this.uncertain && flipped == this.flipped
        ) return
        this.board = board
        this.move = move
        this.side = side
        this.uncertain = uncertain
        this.flipped = flipped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 格距按「列数 / 行数」算，不是「列数-1」：九宫格线只占 8×9 个间距，但四周要各留
        // **半格**给四角框 —— 否则边线上的帅、车，框朝外的一半会被 View 边缘裁掉。
        val cell = minOf(width.toFloat() / Board.COLS, height.toFloat() / Board.ROWS)
        if (cell <= 0f) return

        val gridW = cell * (Board.COLS - 1)
        val gridH = cell * (Board.ROWS - 1)
        val x0 = (width - gridW) / 2f
        val y0 = (height - gridH) / 2f

        // 翻 180° 只改坐标映射，**不旋转画布** —— 旋转会把棋子上的汉字一起转倒。
        // 好处是格线、棋子、四角框、低置信虚线圈全都走这两个函数，翻一次全体一致，
        // 不会有「棋子翻了、标记没翻」这种只在某个局面上才暴露的错位。
        val flip = flipped
        fun cx(col: Int) = x0 + BoardOrientation.col(col, flip) * cell
        fun cy(row: Int) = y0 + BoardOrientation.row(row, flip) * cell

        drawGrid(canvas, x0, y0, gridW, gridH, ::cx, ::cy)

        val current = board ?: return
        drawPieces(canvas, current, cell, ::cx, ::cy)
        move?.let { drawMarker(canvas, it, cell, ::cx, ::cy) }
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
                // 中间七条纵线在河界断开，这是象棋棋盘的样子。
                // 河界是 row 4 与 row 5 之间那条带；翻过 180° 之后 cy(4) 跑到 cy(5) **下面**，
                // 所以这两段必须按大小取，写死「先画到 cy(4)」会把河界画穿。
                val yTop = minOf(cy(4), cy(5))
                val yBottom = maxOf(cy(4), cy(5))
                canvas.drawLine(x, y0, x, yTop, gridPaint)
                canvas.drawLine(x, yBottom, x, y0 + gridH, gridPaint)
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

                if (row * Board.COLS + col in uncertain) {
                    // 虚线圈落在棋子圆环外侧一点点：压在里面会和棋子边框混成实线，
                    // 用户会以为那只是样式，不会想到是警告
                    canvas.drawCircle(x, y, radius + dp(1.6f), uncertainRing)
                }
            }
        }
    }

    /**
     * 推荐着法的标记：起点与落点各画一个四角框（样式对齐 JJ象棋 自己的"上一手"标记）。
     *
     * 为什么不再画箭头：小棋盘格距只有约 20dp，走一格时两端让完只剩 1.4dp 中线 ——
     * 为了不让它消失，之前堆了一串让出量上限、最小中线、头长随可见长度缩的补丁。
     * 四角框没有这个问题：**形状与走法长度无关**，走一格和三格画出来一样大，
     * 也就不存在"太短画不出"那条代码路径（那正是"有时画有时不画"的来源）。
     *
     * 方向仍然读得出来：两格里**有我们棋子**的那格是起点。落点是敌方子（吃子）按颜色
     * 分得清，落点空着更不会有歧义 —— 所以不需要三角头。
     *
     * 框画在棋子外面（半边长 0.47 格 > 棋子半径 0.42 格），不压汉字。
     */
    private fun drawMarker(
        canvas: Canvas,
        move: Move,
        cell: Float,
        cx: (Int) -> Float,
        cy: (Int) -> Float
    ) {
        val halfSide = MarkerGeometry.halfSide(cell)
        val arm = MarkerGeometry.armOf(halfSide)
        frame(canvas, cx(move.fromCol), cy(move.fromRow), halfSide, arm)
        frame(canvas, cx(move.toCol), cy(move.toRow), halfSide, arm)
    }

    /** 在 (x, y) 这个格点上画四角框。 */
    private fun frame(canvas: Canvas, x: Float, y: Float, halfSide: Float, arm: Float) {
        for (s in MarkerGeometry.corners(x, y, halfSide, arm)) {
            canvas.drawLine(s.x1, s.y1, s.x2, s.y2, markerPaint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /**
         * 着法标记色，取自 JJ象棋 自己的"上一手"标记（原图 RGB #1A54D8）。
         *
         * 没有照抄原值：那个蓝是配木色棋盘调的，压在面板的 #1A1A1A 上对比度只有
         * 2.3:1，1.6dp 的细线会糊掉。提亮到 #4C7DFF 后约 5:1，同色系但不发灰。
         */
        const val MARKER = 0xFF4C7DFF.toInt()

        /**
         * 低置信标记色。刻意选青绿：红方棋子是橙红、黑方是蓝灰、推荐着法是蓝，
         * 只有这个颜色不会被误读成"某方的棋子"或"某一手棋"。
         */
        const val WARN = 0xFF6FE3C4.toInt()
        const val RED = 0xFFFF7A66.toInt()
        const val BLACK = 0xFFA9C0D6.toInt()

        /**
         * 棋子半径 / 格距。0.42 是"棋子几乎贴在一起"的比例 —— 棋盘本来就小
         * （格距约 20dp），再小就看不清字。四角框按 0.47 格画，正好落在圆外一点点。
         */
        const val PIECE_RADIUS_RATIO = 0.42f
    }
}
