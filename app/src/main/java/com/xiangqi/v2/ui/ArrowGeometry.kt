package com.xiangqi.v2.ui

/**
 * 箭头几何：**纯计算**，不碰 Canvas。
 *
 * 抽成纯函数是因为这里出过一个「有时候画、有时候不画」的 bug：
 * 小棋盘的格距由高度定死（200dp / 9 ≈ 21.8dp），棋子半径取 0.42 格 ≈ 9.2dp，
 * 两端各让出「半径 + 1dp」之后，走一格时净可见长度是 **21.8 − 2×10.2 = 1.4dp**，
 * 小于原来那道「可见长度不足 4dp 就放弃」的闸门 —— 于是整条箭头（连起点那个圈）
 * 一起被 return 掉，屏幕上什么都没有。
 *
 * 现象因此是选择性的：马走「日」、象走「田」、车炮走两格以上都正常，只有**走一格**
 * （兵、车、将、炮平移一格）什么都不画。
 *
 * 算式挪到这里之后 [ArrowGeometryTest] 就能把「一步棋也必须画出箭头」钉住 ——
 * Canvas 在 JVM 单测里画不出来，留在 View 里就永远测不到。
 */
internal data class ArrowLayout(
    /** 两端各让出多少像素（从起点 / 终点圆心算起） */
    val margin: Float,
    /** 让出之后，中线真正能看到的长度 */
    val visible: Float,
    /** 箭头三角的长度 */
    val head: Float,
    /** 箭头三角的半宽 */
    val halfWidth: Float
)

internal object ArrowGeometry {

    /**
     * @param length   两格中心距（像素）
     * @param radius   棋子圆半径（像素）
     * @param edgeGap  理想情况下线停在棋子外沿之外多少像素（不压字）
     * @param minShaft 中线至少要留多长，否则箭头读不出方向
     * @param maxHead  箭头三角的长度上限
     * @return null 表示这点距离画不出箭头（起终点重合）
     */
    fun layout(
        length: Float,
        radius: Float,
        edgeGap: Float,
        minShaft: Float,
        maxHead: Float
    ): ArrowLayout? {
        if (length <= 0f || length < minShaft) return null
        val ideal = radius + edgeGap
        // 关键：让出量必须**带上限**。按 ideal 让完，走一格时是负长度。
        // 上限取「(长度 − 最小中线) / 2」，于是可见长度恒 ≥ minShaft。
        val margin = minOf(ideal, (length - minShaft) / 2f)
        if (margin <= 0f) return null
        val visible = length - 2f * margin
        if (visible <= 0f) return null
        // 头长跟着可见长度缩：走一格时中线只有 8dp，塞 6dp 的头会把线吃光，
        // 看起来只剩一个孤零零的三角
        val head = minOf(maxHead, visible * HEAD_RATIO)
        return ArrowLayout(margin, visible, head, head * HEAD_HALF_RATIO)
    }

    /** 头长最多占中线 55% */
    private const val HEAD_RATIO = 0.55f

    /** 头宽 / 头长，略扁一点比正三角形更像箭头 */
    private const val HEAD_HALF_RATIO = 0.53f
}
