package com.xiangqi.v2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xiangqi.v2.pipeline.SideMode
import com.xiangqi.v2.pipeline.WatchPhase
import com.xiangqi.v2.pipeline.WatchSnapshot

/**
 * 悬浮窗里的两个视图：悬浮球与面板。
 *
 * 为什么悬浮球是**胶囊形**而不是圆形棋子样：它会被自己拍进去。无障碍截图拍的是
 * 合成后的整屏，一个「深色圆盘 + 中间一个汉字」的形状和棋子几乎一模一样，
 * 落到棋盘格点上就会被检测成一颗子，整盘直接错。改成圆角矩形后模型不再对它响应，
 * 也就不需要「抓屏时隐藏悬浮窗」这种会闪烁的做法。
 */
object OverlayViews {

    private const val BG_PANEL = 0xF21A1A1A.toInt()
    private const val BG_CARD = 0xFF262626.toInt()
    private const val BG_BTN = 0xFF333333.toInt()
    private const val TXT_MAIN = 0xFFF2F2F2.toInt()
    private const val TXT_DIM = 0xFF9E9E9E.toInt()
    private const val ACCENT = 0xFFFFD24A.toInt()
    private const val OK = 0xFF4CAF50.toInt()
    private const val WARN = 0xFFFF9800.toInt()
    private const val ERR = 0xFFE5453A.toInt()

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun bg(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp
            if (strokeColor != null) setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
        }

    /** 胶囊形悬浮球。尺寸写死，以免拖动时窗口抖动。 */
    fun bubble(context: Context): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = bg(0xE6202020.toInt(), dp(context, 22f).toFloat(), ACCENT, 2f)
            setPadding(dp(context, 14f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
        }
        val dot = View(context).apply {
            background = bg(ACCENT, dp(context, 5f).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(context, 10f), dp(context, 10f)).apply {
                rightMargin = dp(context, 8f)
            }
        }
        val label = TextView(context).apply {
            text = "分析"
            setTextColor(ACCENT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        pill.addView(dot)
        pill.addView(label)
        pill.tag = dot // 服务用它在不同状态下换颜色
        return pill
    }

    /** 悬浮球的指示灯：灰=待机，黄=抓屏，绿=有结论，橙=提示，红=错误。 */
    fun tintBubble(bubble: View, phase: WatchPhase) {
        val dot = bubble.tag as? View ?: return
        val color = when (phase) {
            WatchPhase.IDLE -> 0xFF666666.toInt()
            WatchPhase.CAPTURING, WatchPhase.WAITING_STABLE -> ACCENT
            WatchPhase.ANALYZING -> WARN
            WatchPhase.READY -> OK
            WatchPhase.PROBLEM -> ERR
        }
        dot.background = bg(color, dot.layoutParams.height.toFloat() / 2)
    }

    /** 面板的回调，全部由悬浮服务实现。 */
    interface Host {
        fun onRefresh()
        fun onTogglePause()
        fun onCycleSide()
        fun onDepthDelta(delta: Int)
        fun onIntervalDelta(delta: Int)
        fun onMinimize()
        fun onStop()
    }
}

/**
 * 展开后的面板。
 *
 * 排版顺序按「用户最需要看到什么」排：推荐着法最大、其次评分/深度，最后才是 FEN 与参数。
 * FEN 放在下面还有一个用途 —— 识别错了的时候，它是唯一能直接看出错在哪一格的东西。
 */
@SuppressLint("ViewConstructor")
class PanelView(context: Context, private val host: OverlayViews.Host) : LinearLayout(context) {

    private val statusDot: View
    private val statusText: TextView
    private val moveText: TextView
    private val scoreText: TextView
    private val pvText: TextView
    private val fenText: TextView
    private val pauseButton: TextView
    private val sideButton: TextView
    private val depthLabel: TextView
    private val intervalLabel: TextView

    init {
        orientation = VERTICAL
        background = run {
            val d = GradientDrawable().apply {
                setColor(0xF21A1A1A.toInt())
                cornerRadius = dp(14f).toFloat()
                setStroke(2, 0xFF3A3A3A.toInt())
            }
            d
        }
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))

        // ---- 标题行 ----
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "象棋助手 v2"
            setTextColor(TXT_MAIN)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val minimize = iconButton("收起", host::onMinimize)
        val close = iconButton("退出", host::onStop)
        header.addView(title)
        header.addView(minimize)
        header.addView(close)
        addView(header)

        // ---- 状态行 ----
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(6f))
        }
        statusDot = View(context).apply {
            background = bg(0xFF666666.toInt(), dp(4f).toFloat())
            layoutParams = LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(6f) }
        }
        statusText = TextView(context).apply {
            setTextColor(TXT_DIM)
            textSize = 12f
            text = "待开始"
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        addView(statusRow)

        // ---- 推荐着法卡片 ----
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF262626.toInt())
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        moveText = TextView(context).apply {
            setTextColor(ACCENT)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            text = "—"
        }
        scoreText = TextView(context).apply {
            setTextColor(TXT_DIM)
            textSize = 13f
            text = "等待识别"
            setPadding(0, dp(4f), 0, 0)
        }
        pvText = TextView(context).apply {
            setTextColor(0xFF8FA0B0.toInt())
            textSize = 11f
            setPadding(0, dp(4f), 0, 0)
            visibility = GONE
        }
        card.addView(moveText)
        card.addView(scoreText)
        card.addView(pvText)
        addView(card)

        // ---- FEN ----
        fenText = TextView(context).apply {
            setTextColor(0xFF7A7A7A.toInt())
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8f), 0, 0)
            text = ""
        }
        addView(fenText)

        // ---- 第一排按钮 ----
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        pauseButton = actionButton("暂停") {
            host.onTogglePause()
        }
        sideButton = actionButton("轮次:自动") {
            host.onCycleSide()
        }
        row1.addView(pauseButton)
        row1.addView(sideButton)
        row1.addView(actionButton("重新识别") { host.onRefresh() })
        addView(row1)

        // ---- 第二排：深度 ----
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        depthLabel = TextView(context).apply {
            setTextColor(TXT_DIM)
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(depthLabel)
        row2.addView(smallButton("深 -") { host.onDepthDelta(-1) })
        row2.addView(smallButton("深 +") { host.onDepthDelta(1) })
        addView(row2)

        // ---- 第三排：盯盘节奏 ----
        val row3 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, 0)
        }
        intervalLabel = TextView(context).apply {
            setTextColor(TXT_DIM)
            textSize = 12f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row3.addView(intervalLabel)
        row3.addView(smallButton("慢一点") { host.onIntervalDelta(1) })
        row3.addView(smallButton("快一点") { host.onIntervalDelta(-1) })
        addView(row3)

        val hint = TextView(context).apply {
            setTextColor(0xFF5A5A5A.toInt())
            textSize = 10f
            text = "面板展开时会暂停盯盘（它会挡住棋盘）。收起后自动恢复。"
            setPadding(0, dp(8f), 0, 0)
        }
        addView(hint)
    }

    fun render(snapshot: WatchSnapshot, sideMode: SideMode, paused: Boolean, depth: Int, intervalMs: Int) {
        statusDot.background = bg(colorOf(snapshot.phase), dp(4f).toFloat())
        statusText.text = buildString {
            append(snapshot.message)
            val timing = StringBuilder()
            if (snapshot.recognizeMs > 0) timing.append("识别 ${snapshot.recognizeMs}ms")
            if (snapshot.analyzeMs > 0) timing.append(" · 搜索 ${snapshot.analyzeMs}ms")
            if (timing.isNotEmpty()) append("   ").append(timing)
        }

        val analysis = snapshot.analysis
        if (analysis != null && snapshot.phase == WatchPhase.READY) {
            moveText.text = "${snapshot.side.displayName}走：" + MoveFormatter.moveText(snapshot.fen ?: "", analysis)
            scoreText.text = buildString {
                append(MoveFormatter.scoreText(analysis).ifEmpty { "评分 —" })
                append("    深度 ").append(analysis.depth)
                if (analysis.hasMove) append("    ").append(analysis.bestMoveUci)
            }
            val pv = MoveFormatter.pvText(analysis, 4)
            pvText.visibility = if (pv.isEmpty()) GONE else VISIBLE
            pvText.text = "主变 $pv"
        } else {
            moveText.text = when (snapshot.phase) {
                WatchPhase.ANALYZING -> "分析中…"
                WatchPhase.PROBLEM -> "—"
                else -> "—"
            }
            scoreText.text = when (snapshot.phase) {
                WatchPhase.PROBLEM -> "看上面那行的原因"
                WatchPhase.ANALYZING -> "引擎正在搜索"
                else -> "等待识别"
            }
            pvText.visibility = GONE
        }

        fenText.text = snapshot.fen?.let { "FEN  $it" } ?: ""
        pauseButton.text = if (paused) "继续" else "暂停"
        sideButton.text = "轮次:${sideMode.label}"
        depthLabel.text = "深度 $depth"
        intervalLabel.text = "间隔 $intervalMs"
    }

    private fun colorOf(phase: WatchPhase): Int = when (phase) {
        WatchPhase.IDLE -> 0xFF666666.toInt()
        WatchPhase.CAPTURING, WatchPhase.WAITING_STABLE -> ACCENT
        WatchPhase.ANALYZING -> WARN
        WatchPhase.READY -> OK
        WatchPhase.PROBLEM -> ERR
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun bg(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TXT_MAIN)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(BG_BTN)
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6f) }
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TXT_MAIN)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(BG_BTN)
                cornerRadius = dp(6f).toFloat()
            }
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4f)
            }
            setOnClickListener { onClick() }
        }

    private fun iconButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TXT_DIM)
            textSize = 12f
            setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { onClick() }
        }

    private companion object {
        const val BG_BTN = 0xFF333333.toInt()
        const val TXT_MAIN = 0xFFF2F2F2.toInt()
        const val TXT_DIM = 0xFF9E9E9E.toInt()
        const val ACCENT = 0xFFFFD24A.toInt()
        const val OK = 0xFF4CAF50.toInt()
        const val WARN = 0xFFFF9800.toInt()
        const val ERR = 0xFFE5453A.toInt()
    }
}
