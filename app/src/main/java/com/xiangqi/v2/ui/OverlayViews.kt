package com.xiangqi.v2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.pipeline.GateMode
import com.xiangqi.v2.pipeline.SideMode
import com.xiangqi.v2.pipeline.WatchPhase
import com.xiangqi.v2.pipeline.WatchSnapshot

/**
 * 悬浮窗里的两个视图：悬浮球与面板。
 *
 * 为什么悬浮球是**切角方块**而不是圆形棋子样：它会被自己拍进去。无障碍截图拍的是
 * 合成后的整屏，一个「深色圆盘 + 中间一个汉字」的形状和棋子几乎一模一样，
 * 落到棋盘格点上就会被检测成一颗子，整盘直接错。改成方块后模型不再对它响应，
 * 也就不需要「抓屏时隐藏悬浮窗」这种会闪烁的做法。
 * 换成现在这套切角样式（[CutRectDrawable]）后，轮廓离圆更远了。
 */
object OverlayViews {

    private fun dp(context: Context, value: Float): Int = Hud.dp(context, value)

    private fun dpf(context: Context, value: Float): Float = Hud.dpf(context, value)

    /** 切角悬浮球。尺寸由内容决定，服务那边按 wrap_content 摆放。 */
    fun bubble(context: Context): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = CutRectDrawable(
                fill = 0xE616171B.toInt(),
                stroke = Hud.ACCENT,
                strokePx = dpf(context, 1.5f),
                cutPx = dpf(context, 11f),
                codes = CutRectDrawable.TR or CutRectDrawable.BL
            )
            setPadding(dp(context, 13f), dp(context, 9f), dp(context, 15f), dp(context, 9f))
        }
        val dot = View(context).apply {
            background = CutRectDrawable(
                fill = Hud.ACCENT,
                cutPx = dpf(context, 2f),
                codes = CutRectDrawable.BL
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f)).apply {
                rightMargin = dp(context, 9f)
            }
        }
        val label = TextView(context).apply {
            text = "分析"
            setTextColor(Hud.ACCENT)
            textSize = 14f
            typeface = Hud.narrow(true)
            letterSpacing = 0.12f
        }
        pill.addView(dot)
        pill.addView(label)
        pill.tag = dot // 服务用它在不同状态下换颜色
        return pill
    }

    /** 悬浮球的指示灯：灰=待机，黄=抓屏，橙=有结论，绿=提示，红=错误。 */
    fun tintBubble(bubble: View, phase: WatchPhase) {
        val dot = bubble.tag as? View ?: return
        val color = colorOf(phase)
        dot.background = CutRectDrawable(
            fill = color,
            cutPx = dot.layoutParams.height * 0.28f,
            codes = CutRectDrawable.BL
        )
    }

    internal fun colorOf(phase: WatchPhase): Int = when (phase) {
        WatchPhase.IDLE -> Hud.TXT_MUTE
        WatchPhase.CAPTURING, WatchPhase.WAITING_STABLE -> Hud.ACCENT
        WatchPhase.ANALYZING -> Hud.WARN
        WatchPhase.READY -> Hud.INFO
        WatchPhase.PROBLEM -> Hud.ERR
    }

    /** 把裁角的那块底色抽出来，面板和卡片共用，免得两处描边深浅不一。 */
    internal fun panelBackground(context: Context): CutRectDrawable = CutRectDrawable(
        fill = 0xF216171A.toInt(),
        stroke = Hud.LINE_HI,
        strokePx = dpf(context, 1.2f),
        cutPx = dpf(context, 20f),
        codes = CutRectDrawable.TR or CutRectDrawable.BR
    )

    /** 面板的回调，全部由悬浮服务实现。 */
    interface Host {
        fun onRefresh()
        fun onTogglePause()
        fun onCycleSide()
        fun onDepthDelta(delta: Int)
        fun onIntervalDelta(delta: Int)
        fun onToggleBoard()

        /** 切「多帧确认 / 单帧即采」。两种都留着，是因为这件事取决于用户更忍不了哪一种。 */
        fun onCycleGate()
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
    private val miniBoard: MiniBoardView
    private val moveText: TextView
    private val scoreText: TextView
    private val pvText: TextView
    private val fenText: TextView
    private val pauseButton: TextView
    private val sideButton: TextView
    private val depthLabel: TextView
    private val intervalLabel: TextView
    private val gateLabel: TextView

    /**
     * 上一次「出结论」的 FEN 与着法。
     *
     * 用途只有一个：同一个局面被重算时（点重新识别、改深度、切轮次）沿用上一次的箭头。
     * 否则每次搜索都会闪成「分析中…」，箭头消失再出现 —— 用户看到的就是
     * 「这条线有时候给、有时候不给」。局面没变，上一次的结论就仍然成立。
     */
    private var lastReadyFen: String? = null
    private var lastReadyMove: Move? = null

    init {
        orientation = VERTICAL
        background = OverlayViews.panelBackground(context)
        setPadding(dp(14f), dp(13f), dp(14f), dp(13f))

        // ---- 标题行 ----
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            View(context).apply {
                setBackgroundColor(Hud.ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(3f), dp(15f)).apply { rightMargin = dp(9f) }
            }
        )
        header.addView(
            TextView(context).apply {
                text = "象棋助手"
                setTextColor(Hud.TXT)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
            }
        )
        header.addView(
            TextView(context).apply {
                text = "HUD"
                setTextColor(Hud.ACCENT_DIM)
                textSize = 9f
                typeface = Hud.narrow(true)
                letterSpacing = 0.2f
                setPadding(dp(7f), dp(2f), 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(iconButton("▦") { host.onToggleBoard() })
        header.addView(iconButton("─", host::onMinimize))
        header.addView(iconButton("×", host::onStop))
        addView(header)

        // ---- 状态行 ----
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, dp(7f))
        }
        statusDot = View(context).apply {
            background = CutRectDrawable(
                fill = Hud.TXT_MUTE,
                cutPx = dpf(2f),
                codes = CutRectDrawable.BL
            )
            layoutParams = LayoutParams(dp(7f), dp(7f)).apply { rightMargin = dp(8f) }
        }
        statusText = TextView(context).apply {
            setTextColor(Hud.TXT_DIM)
            textSize = 11f
            typeface = Hud.narrow(false)
            letterSpacing = 0.04f
            text = "待开始"
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        addView(statusRow)

        // ---- 小棋盘（可折叠，默认展开）----
        // 高度写死、宽度撑满：内部按"格子必须是正方形"自己算格距并居中，
        // 所以这里不需要和 dp 换算较劲。
        miniBoard = MiniBoardView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(200f)).apply {
                topMargin = dp(2f)
            }
        }
        addView(miniBoard)

        // ---- 推荐着法卡片 ----
        // 左侧一道亮色竖线：这是整个面板唯一的"结论"，视觉上要和参数区分开。
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = CutRectDrawable(
                fill = Hud.SURFACE_HI,
                cutPx = dpf(13f),
                codes = CutRectDrawable.BR
            )
            setPadding(dp(13f), dp(11f), dp(12f), dp(11f))
        }
        moveText = TextView(context).apply {
            setTextColor(Hud.ACCENT)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
            text = "—"
        }
        scoreText = TextView(context).apply {
            setTextColor(Hud.TXT_DIM)
            textSize = 12f
            typeface = Hud.narrow(false)
            letterSpacing = 0.04f
            text = "等待识别"
            setPadding(0, dp(5f), 0, 0)
        }
        pvText = TextView(context).apply {
            setTextColor(Hud.INFO)
            textSize = 11f
            typeface = Hud.narrow(false)
            setPadding(0, dp(5f), 0, 0)
            visibility = GONE
        }
        card.addView(moveText)
        card.addView(scoreText)
        card.addView(pvText)
        addView(card)

        // ---- FEN ----
        fenText = TextView(context).apply {
            setTextColor(Hud.TXT_MUTE)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(9f), 0, 0)
            text = ""
        }
        addView(fenText)

        // ---- 第一排按钮 ----
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(11f), 0, 0)
        }
        pauseButton = actionButton("暂停") { host.onTogglePause() }
        sideButton = actionButton("轮次 自动") { host.onCycleSide() }
        row1.addView(pauseButton)
        row1.addView(sideButton)
        row1.addView(actionButton("重新识别") { host.onRefresh() })
        addView(row1)

        // ---- 第二排：深度 ----
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        depthLabel = paramLabel()
        row2.addView(depthLabel)
        row2.addView(Hud.chipButton(context, "深度 −") { host.onDepthDelta(-1) }, chipParams())
        row2.addView(Hud.chipButton(context, "深度 +") { host.onDepthDelta(1) }, chipParams())
        addView(row2)

        // ---- 第三排：盯盘节奏 ----
        val row3 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7f), 0, 0)
        }
        intervalLabel = paramLabel()
        row3.addView(intervalLabel)
        row3.addView(Hud.chipButton(context, "慢") { host.onIntervalDelta(1) }, chipParams())
        row3.addView(Hud.chipButton(context, "快") { host.onIntervalDelta(-1) }, chipParams())
        addView(row3)

        // ---- 第四排：采纳方式（多帧确认 / 单帧即采）----
        // 做成一行「标签 + 切换」而不是设置页里的开关：这条是在**下棋时**才感觉到的东西，
        // 让人切出去改设置、回来再试，等于感觉不到了。
        val row4 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7f), 0, dp(2f))
        }
        gateLabel = paramLabel()
        row4.addView(gateLabel)
        row4.addView(Hud.chipButton(context, "切换") { host.onCycleGate() }, chipParams())
        addView(row4)
    }

    /** 显示/隐藏小棋盘。收起后面板矮一截，位置由服务重新摆一次。 */
    fun setBoardVisible(visible: Boolean) {
        miniBoard.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun render(
        snapshot: WatchSnapshot,
        sideMode: SideMode,
        paused: Boolean,
        depth: Int,
        intervalMs: Int,
        gateMode: GateMode
    ) {
        statusDot.background = CutRectDrawable(
            fill = OverlayViews.colorOf(snapshot.phase),
            cutPx = dp(2f).toFloat(),
            codes = CutRectDrawable.BL
        )
        statusText.text = buildString {
            append(snapshot.message)
            val timing = StringBuilder()
            if (snapshot.recognizeMs > 0) timing.append("识 ").append(snapshot.recognizeMs).append("ms")
            if (snapshot.analyzeMs > 0) timing.append(" · 算 ").append(snapshot.analyzeMs).append("ms")
            if (timing.isNotEmpty()) append("   ").append(timing)
        }

        if (snapshot.phase == WatchPhase.READY && snapshot.analysis != null) {
            lastReadyFen = snapshot.fen
            lastReadyMove = snapshot.analysis.bestMove
        }
        // 只在有结论时画箭头：分析中/出错时盘面可能已经变了，箭头会指错方向。
        // 例外是「同一个局面在重算」—— FEN 相同说明盘面与轮次都没变，
        // 上一次的着法依然成立，沿用它可以避免每次搜索都闪一下。
        val samePositionReanalyzing =
            snapshot.phase == WatchPhase.ANALYZING &&
                snapshot.fen != null &&
                snapshot.fen == lastReadyFen
        miniBoard.show(
            snapshot.board,
            when {
                snapshot.phase == WatchPhase.READY -> snapshot.analysis?.bestMove
                samePositionReanalyzing -> lastReadyMove
                else -> null
            },
            snapshot.side,
            snapshot.uncertain,
            // 按「画面视角」画：用户在平台上自己永远在下方，红在上时（黑方用户）
            // 面板也把红画在上面，这样面板和平台棋盘长得一模一样。
            flipped = snapshot.redOnTop
        )

        val analysis = snapshot.analysis
        if (analysis != null && snapshot.phase == WatchPhase.READY) {
            moveText.text = "${snapshot.side.displayName}走 " + MoveFormatter.moveText(snapshot.fen ?: "", analysis)
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
                WatchPhase.PROBLEM -> "见上方状态"
                WatchPhase.ANALYZING -> "引擎搜索中"
                else -> "等待识别"
            }
            pvText.visibility = GONE
        }

        fenText.text = snapshot.fen ?: ""
        pauseButton.text = if (paused) "继续" else "暂停"
        // "轮次" + "轮红" 会念成"轮次轮红"，把标签自带的「轮」去掉
        sideButton.text = "轮次 " + sideMode.label.removePrefix("轮")
        depthLabel.text = "搜索深度   $depth"
        intervalLabel.text = "盯盘间隔   ${intervalMs}ms"
        gateLabel.text = "采纳方式   ${gateMode.label}"
    }

    private fun dp(value: Float): Int = Hud.dp(context, value)

    private fun dpf(value: Float): Float = Hud.dpf(context, value)

    /** 行内小按钮的布局参数。挂实例上统一给左边距，省得每处 .apply 一遍。 */
    private fun chipParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6f)
        }

    private fun paramLabel(): TextView =
        Hud.tagText(context, "", Hud.TXT_DIM, 12f).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun actionButton(text: String, onClick: () -> Unit): TextView =
        Hud.ghostButton(context, text) { onClick() }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6f) }
            setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        }

    private fun iconButton(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Hud.TXT_DIM)
            textSize = 13f
            typeface = Hud.narrow(true)
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(11f), dp(4f), dp(2f), dp(4f))
            setOnClickListener { onClick() }
        }
}
