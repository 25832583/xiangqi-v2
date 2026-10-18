package com.xiangqi.v2.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一个检测框吸附到棋盘交点后的候选。
 *
 * [residual] 是中心相对交点的最大轴向偏差（单位：格）。正常帧 ≤ 0.1；
 * 超过 [MAX_RESIDUAL] 说明这个框不在任何交点上（多半是误检），直接不要。
 */
data class CellCandidate(
    val row: Int,
    val col: Int,
    val detection: Detection,
    val residual: Float
)

object GridSnap {

    /** 吸附残差上限（格）。实测 5 张真实截图最大 0.09；0.35 只放行极端斜拍。 */
    const val MAX_RESIDUAL: Float = 0.35f

    /**
     * 把每个棋子框中心投到 (row, col)。
     *
     * 行号/列号此刻还是**任意平移**的（取决于最靠边的子在哪），
     * 归一化由 [BoardAssembler] 统一处理 —— 这一步只管"落在哪条线"。
     */
    fun snap(
        detections: List<Detection>,
        fitX: AxisFit,
        fitY: AxisFit,
        maxResidual: Float = MAX_RESIDUAL
    ): List<CellCandidate> {
        val out = ArrayList<CellCandidate>(detections.size)
        for (d in detections) {
            val fx = fitX.indexOf(d.box.centerX)
            val fy = fitY.indexOf(d.box.centerY)
            val col = fx.roundToInt()
            val row = fy.roundToInt()
            val residual = max(abs(fx - col), abs(fy - row))
            if (residual > maxResidual) continue
            out.add(CellCandidate(row, col, d, residual))
        }
        return out
    }
}
