package com.xiangqi.v2.ui

/** 一条线段。抽出来是为了让几何算法能离开 Canvas 单测。 */
internal data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * 着法标记的几何：两端各一个「四角框」——纯计算，不碰 Canvas。
 *
 * ## 为什么不是箭头
 *
 * 一开始画的是「起点空心圈 + 中线 + 三角头」。问题是小棋盘的格距只有约 20dp，
 * 走一格时两端让完只剩 1.4dp 中线，于是加了一堆让出量上限、最小中线长度之类的补丁，
 * 才勉强不丢线。补丁越多，越容易再出「有时候画、有时候不画」。
 *
 * 参照 JJ象棋 自己的"上一手"标记：**两端各一个四角框，没有连线、没有三角头**。
 * 它比箭头好在两点：
 *  1. 形状与走法长度无关 —— 走一格和走三格画出来一样，不存在"太短画不出"这条路径；
 *  2. 框在棋子外面，不压字。
 *
 * 而且它本身就能说明方向：两格里**有我们棋子**的那一格是起点，另一格是落点。
 * 落点是敌方棋子（吃子）时按颜色分得清，落点空着时更不会有歧义。
 *
 * ## 比例是从参考图量出来的
 *
 * 框边长 ≈ 一个格距（58px / 60px），臂长 / 边长 = 16 / 58 ≈ 0.28。
 * 这里取 [HALF_SIDE_RATIO]=0.47（半边长，即 0.94 格），比棋子半径 0.42 格略大 ——
 * 框贴着棋子外沿，与参考图的观感一致；留的那点余量刚好让相邻两格的框不粘在一起。
 */
internal object MarkerGeometry {

    /** 半边长 / 格距。0.47 格 ⇒ 边长 0.94 格，与参考图量到的 0.97 差不多。 */
    const val HALF_SIDE_RATIO = 0.47f

    /** 臂长 / 边长。参考图 16/58 ≈ 0.276。 */
    const val ARM_RATIO = 0.28f

    fun halfSide(cell: Float): Float = cell * HALF_SIDE_RATIO

    /** 臂长。参数是**半**边长，免得调用方每次都要乘 2。 */
    fun armOf(halfSide: Float): Float = halfSide * 2f * ARM_RATIO

    /**
     * 四个角，每个角两条臂，共 8 条线段。
     *
     * 臂一律**朝中心方向**伸（就是取景框四个角的样子）。反了会变成向外的四个小爪子，
     * 远看像棋子周围长了刺 —— [MarkerGeometryTest] 专门钉住方向。
     */
    fun corners(cx: Float, cy: Float, halfSide: Float, arm: Float): List<Segment> {
        val segments = ArrayList<Segment>(8)
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * halfSide
                val y = cy + sy * halfSide
                segments += Segment(x, y, x - sx * arm, y)
                segments += Segment(x, y, x, y - sy * arm)
            }
        }
        return segments
    }
}
