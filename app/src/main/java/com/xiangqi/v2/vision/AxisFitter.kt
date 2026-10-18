package com.xiangqi.v2.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一条轴的网格拟合结果。
 *
 * 语义：棋盘上第 k 条线的坐标是 `phase + k * pitch`。
 * [residual] 是各点到最近整数格点的 RMS 偏差，单位是「格」；
 * [concentration] 是圆均值的模长（1.0 = 全部点精确落在格点上）。
 *
 * 这两个数就是"这一帧的几何可不可信"的唯一出口 —— 不再靠肉眼看图。
 */
data class AxisFit(
    val pitch: Float,
    val phase: Float,
    val residual: Float,
    val concentration: Float
) {
    /** 任意坐标换算成「格」单位的浮点下标（四舍五入即落点）。 */
    fun indexOf(value: Float): Float = (value - phase) / pitch
}

/**
 * 一维周期拟合：从一堆棋子框中心反解真实格距与相位。
 *
 * **为什么不能用棋盘框反推格距**：实测（5 张真实截图）模型给的棋盘框
 * 宽 ≈ 8.04~8.36 个列距、高 ≈ 9.01~9.22 个行距，都不是整齐的 9 / 10。
 * 按「框宽/9」算格距会低估 13%，误差累积到棋盘两端就偏半格 —— 旧项目的
 * `GridFitter` + `cropSideRatio` 那一串补丁正是在补这个。
 *
 * 而棋子**本身就是站在格点上的**，这是最强的几何证据，且与棋盘皮肤无关。
 *
 * 算法：穷举 pitch，用圆均值求相位（对缺失格点不敏感），取 RMS 残差最小者。
 * 与离线验证脚本 `tools/assemble_fit.py` 逐行等价 —— 参数必须两边一致，
 * 那边跑出来的数字才能用来验收这边。
 */
object AxisFitter {

    /**
     * @param values  框中心在某一轴上的坐标
     * @param pitchLo 搜索下界
     * @param pitchHi 搜索上界
     * @param step    搜索步长（像素）。0.05px 对 1080p 帧已远超需求
     */
    fun fit(values: FloatArray, pitchLo: Float, pitchHi: Float, step: Float = 0.05f): AxisFit? {
        if (values.size < 3) return null
        if (pitchLo <= 0f || pitchHi <= pitchLo) return null
        val n = values.size.toDouble()
        var best: AxisFit? = null
        var pitch = pitchLo
        while (pitch <= pitchHi) {
            var sumCos = 0.0
            var sumSin = 0.0
            for (v in values) {
                val angle = 2.0 * PI * v / pitch
                sumCos += cos(angle)
                sumSin += sin(angle)
            }
            val concentration = hypot(sumCos, sumSin) / n
            if (concentration > 1e-6) {
                var phase = (atan2(sumSin, sumCos) / (2.0 * PI) * pitch).toFloat()
                phase = ((phase % pitch) + pitch) % pitch
                var squareSum = 0.0
                for (v in values) {
                    var r = ((v - phase) / pitch).toDouble()
                    r -= r.roundToLong().toDouble()
                    squareSum += r * r
                }
                val residual = sqrt(squareSum / n).toFloat()
                if (best == null || residual < best.residual) {
                    best = AxisFit(pitch, phase, residual, concentration.toFloat())
                }
            }
            pitch += step
        }
        return best
    }

    /** 逐点偏差的最大值（格单位），用于判断有没有个别离群框。 */
    fun maxDeviation(values: FloatArray, fit: AxisFit): Float {
        var worst = 0f
        for (v in values) {
            var r = fit.indexOf(v)
            r -= r.toDouble().roundToLong().toFloat()
            worst = maxOf(worst, abs(r))
        }
        return worst
    }
}
