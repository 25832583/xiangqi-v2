package com.xiangqi.v2.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面语言：切角块 + 1dp 细线 + 窄体小标 + 一点荧光黄绿。
 *
 * 借的是《明日方舟》那套 HUD 的视觉语法 —— 近黑底、硬边斜切、编号分区、
 * 大面积留白只让一处亮色发光。只借语法，不用它的任何素材、字体或图标。
 *
 * 为什么不用 XML style：整个界面都是代码搭的（[MainActivity] 与 [OverlayViews]），
 * 混进 res/values 反而多一层跳转。这里只放**可复用的零件**，具体排版留在各自界面里。
 *
 * 三条约束，改的时候别破坏：
 *  1. 亮色（[ACCENT]）只标「活的、可操作的、当前的」—— 状态点、主按钮、分区编号。
 *     处处铺满就不亮了，整套界面会退化成荧光绿噪音。
 *  2. 圆角一律为 0，改用斜切角表达「块」的边界。
 *  3. 不用小字灰色堆说明文案 —— 需要解释的东西应该消失或改交互，不是写小一点。
 */
object Hud {

    // ---- 底色 ----
    const val BG = 0xFF0D0E10.toInt()
    const val SURFACE = 0xFF16181B.toInt()
    const val SURFACE_HI = 0xFF1F2227.toInt()

    /** 分割线。比底色亮一档，够看出结构又不抢注意力。 */
    const val LINE = 0xFF2B2F35.toInt()
    const val LINE_HI = 0xFF454B53.toInt()

    // ---- 亮色 ----
    const val ACCENT = 0xFFDDF247.toInt()
    const val ACCENT_DIM = 0xFF6F7A22.toInt()

    // ---- 文字 ----
    const val TXT = 0xFFE8EAED.toInt()
    const val TXT_DIM = 0xFF8B9098.toInt()
    const val TXT_MUTE = 0xFF565B62.toInt()

    // ---- 状态色 ----
    const val OK = 0xFFDDF247.toInt()
    const val WARN = 0xFFFFA23A.toInt()
    const val ERR = 0xFFFF4A3D.toInt()
    const val INFO = 0xFF3FD2C7.toInt()

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        ).toInt()

    fun dpf(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        )

    /**
     * 窄体。Roboto Condensed 只对拉丁字母生效，中文会回落到系统字体 ——
     * 这是想要的：数字与英文标签走窄体显利落，中文保持正常骨架不显瘦。
     */
    fun narrow(bold: Boolean = false): Typeface =
        Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)

    // ---- 分区标题：`01 ▏权限 ─────────` ----

    /**
     * 一个分区容器：切角底块 + 编号标题行。内容自己往里 addView。
     * 右上与右下切角，左侧保持直角 —— 整列左边缘对齐成一条锋线。
     */
    fun section(context: Context, code: String, title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = CutRectDrawable(
                fill = SURFACE,
                stroke = LINE,
                strokePx = dpf(context, 1f),
                cutPx = dpf(context, 16f),
                codes = CutRectDrawable.TR or CutRectDrawable.BR
            )
            setPadding(dp(context, 14f), dp(context, 13f), dp(context, 14f), dp(context, 14f))
            addView(sectionHeader(context, code, title))
        }

    fun sectionHeader(context: Context, code: String, title: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 4f))
            addView(
                TextView(context).apply {
                    text = code
                    setTextColor(ACCENT)
                    textSize = 10f
                    typeface = narrow(true)
                    letterSpacing = 0.2f
                }
            )
            addView(
                View(context).apply {
                    setBackgroundColor(ACCENT)
                    layoutParams = LinearLayout.LayoutParams(dp(context, 2f), dp(context, 11f))
                        .apply {
                            marginStart = dp(context, 8f)
                            marginEnd = dp(context, 8f)
                        }
                }
            )
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(TXT)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.06f
                }
            )
            addView(
                View(context).apply {
                    setBackgroundColor(LINE)
                    layoutParams = LinearLayout.LayoutParams(0, dp(context, 1f), 1f)
                        .apply { marginStart = dp(context, 10f) }
                }
            )
        }

    /** 分区内的行分隔线。比外框细一档，不抢结构。 */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1f))
    }

    // ---- 按钮 ----

    /** 主操作：整块亮色填充 + 深色字。一屏只该有一个。 */
    fun primaryButton(context: Context, text: String, onClick: () -> Unit): TextView =
        button(context, text, 15f, true).apply {
            setTextColor(BG)
            background = pressable(
                CutRectDrawable(
                    fill = ACCENT,
                    cutPx = dpf(context, 12f),
                    codes = CutRectDrawable.TR or CutRectDrawable.BL
                )
            )
            setOnClickListener { onClick() }
        }

    /** 次操作：深底 + 亮描边。可多个并排。 */
    fun ghostButton(context: Context, text: String, onClick: () -> Unit): TextView =
        button(context, text, 12f, false).apply {
            setTextColor(TXT)
            background = pressable(
                CutRectDrawable(
                    fill = SURFACE_HI,
                    stroke = LINE_HI,
                    strokePx = dpf(context, 1f),
                    cutPx = dpf(context, 9f),
                    codes = CutRectDrawable.BL
                )
            )
            setOnClickListener { onClick() }
        }

    /** 极小的行内操作（深+/深-/切换这类），不描边，只靠底色区分。 */
    fun chipButton(context: Context, text: String, onClick: () -> Unit): TextView =
        button(context, text, 11f, true).apply {
            setTextColor(ACCENT)
            background = pressable(
                CutRectDrawable(
                    fill = SURFACE_HI,
                    cutPx = dpf(context, 7f),
                    codes = CutRectDrawable.BL
                )
            )
            setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
            setOnClickListener { onClick() }
        }

    private fun button(context: Context, text: String, size: Float, bold: Boolean): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            letterSpacing = 0.06f
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(context, 16f), dp(context, 13f), dp(context, 16f), dp(context, 13f))
        }

    /**
     * 按下反馈。Ripple 的圆角边界和切角对不齐，但只溢出角落几个像素，
     * 换成自绘按压态要多维护一套状态机，不值。
     */
    private fun pressable(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    /** 无色块的细线，用来在分区之间拉一刀。 */
    fun rule(context: Context, color: Int, thicknessDp: Float): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpf(context, thicknessDp).toInt()
        )
    }

    /** 小标签：参数名、状态值。窄体 + 宽字距，是这套界面里「机器语言」的部分。 */
    fun tagText(context: Context, text: String, color: Int = TXT_DIM, size: Float = 12f): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = narrow(false)
            letterSpacing = 0.06f
        }
}

/**
 * 斜切角的矩形。整套界面的「块」都是它。
 *
 * [codes] 用位掩码选切哪几个角：[CutRectDrawable.TL] / [TR] / [BR] / [BL]。
 * 只切一两个角是刻意的 —— 四角全切会变成八边形，看着像按钮贴纸，
 * 而切对角才有「被削出来的一块」的硬边感。
 */
class CutRectDrawable(
    private val fill: Int = Color.TRANSPARENT,
    private val stroke: Int = Color.TRANSPARENT,
    private val strokePx: Float = 0f,
    private val cutPx: Float = 0f,
    private val codes: Int = TR or BR,
    private val intrinsic: Int = 0,
    private val hatch: Int = Color.TRANSPARENT,
    private val hatchGapPx: Float = 0f
) : Drawable() {

    companion object {
        const val TL = 1
        const val TR = 2
        const val BR = 4
        const val BL = 8
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }

    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        buildPath(b)

        if (fill != Color.TRANSPARENT) {
            fillPaint.color = fill
            canvas.drawPath(path, fillPaint)
        }
        if (hatch != Color.TRANSPARENT && hatchGapPx > 0f) {
            hatchPaint.color = hatch
            hatchPaint.strokeWidth = (hatchGapPx / 8f).coerceAtLeast(1f)
            canvas.save()
            canvas.clipPath(path)
            val step = b.height().toFloat()
            var x = -step
            while (x < b.width() + step) {
                canvas.drawLine(
                    b.left + x, b.bottom.toFloat(),
                    b.left + x + step, b.top.toFloat(),
                    hatchPaint
                )
                x += hatchGapPx
            }
            canvas.restore()
        }
        if (stroke != Color.TRANSPARENT && strokePx > 0f) {
            strokePaint.color = stroke
            strokePaint.strokeWidth = strokePx
            // 描边以路径为中心画，路径内缩半个线宽，右/下边才不会被 bounds 裁掉
            val half = strokePx / 2f
            buildPath(
                android.graphics.Rect(
                    b.left + half.toInt(), b.top + half.toInt(),
                    b.right - half.toInt(), b.bottom - half.toInt()
                )
            )
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun buildPath(b: android.graphics.Rect) {
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom.toFloat()
        // 切角不得超过短边的一半，否则 10dp 的滑块会画成菱形
        val cut = cutPx.coerceAtMost(minOf(b.width(), b.height()) / 2f)
        val tl = if (codes and TL != 0) cut else 0f
        val tr = if (codes and TR != 0) cut else 0f
        val br = if (codes and BR != 0) cut else 0f
        val bl = if (codes and BL != 0) cut else 0f

        path.reset()
        path.moveTo(left + tl, top)
        path.lineTo(right - tr, top)
        if (tr > 0f) path.lineTo(right, top + tr)
        path.lineTo(right, bottom - br)
        if (br > 0f) path.lineTo(right - br, bottom)
        path.lineTo(left + bl, bottom)
        if (bl > 0f) path.lineTo(left, bottom - bl)
        path.lineTo(left, top + tl)
        if (tl > 0f) path.lineTo(left + tl, top)
        path.close()
    }

    override fun getIntrinsicWidth(): Int = if (intrinsic > 0) intrinsic else super.getIntrinsicWidth()

    override fun getIntrinsicHeight(): Int = if (intrinsic > 0) intrinsic else super.getIntrinsicHeight()

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("API 29 起改用 setColorFilter(ColorFilter?)")
    override fun getOpacity(): Int =
        if (fill == Color.TRANSPARENT) android.graphics.PixelFormat.TRANSPARENT
        else android.graphics.PixelFormat.TRANSLUCENT
}

/** 纯色块（斜纹/实填）的便捷构造，省掉到处 new ColorDrawable。 */
fun fillOf(color: Int): ColorDrawable = ColorDrawable(color)
