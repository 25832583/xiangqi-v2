package com.xiangqi.v2.vision

/**
 * 棋盘框「检不出来」时的两条兜底，以及「检到了但不像棋盘」的判据。
 *
 * ## 为什么需要
 * JJ象棋「32强战」皮肤上棋盘框置信度只有 0.31~0.47（常规皮肤 0.83~0.94）。
 * 7 张真实截图实测：
 *
 * | 图 | 最高棋盘框 conf | 框是否正确 |
 * |---|---|---|
 * | 5 张 | 0.31 ~ 0.47 | 正确 |
 * | da53b33b | **0.229** | 正确（1254x1363），只差 0.021 就过门槛 |
 * | 1a0ff692 | 0.214 | **残缺**（967x1104，真实约 1250x1360） |
 *
 * 前 6 张靠「放宽门槛 + 尺寸校验」即可，最后一张只能靠 [synth] 从棋子反推。
 *
 * ## 为什么可以用棋子中心合成棋盘框
 * 棋盘框的四条边正好压在**首/末列、首/末行的中心线**上：
 * 实测框 x[90,1344] 对应列中心 95..1354（pitch 156.5），框高 1362 = 9 x 151.3。
 * 所以首末列/行都有子时，「棋子中心的外接矩形」**就是**棋盘框，误差 1~2 像素。
 *
 * ## 必须先按尺寸剔异常框
 * 棋盘上方的装饰立牌、玩家头像会被检成棋子，常见是真实棋子的 2 倍大
 * （1a0ff692 实测：conf 0.52、尺寸 2.06x2.04 格、中心在棋盘上方 250px）。
 * 直接用 min/max 求包络，一个离群框就能把框顶拉高两格，锚定随即整盘错位。
 */
object BoardBoxSynth {

    /** 少于这么多颗棋子，包络不足以代表棋盘，宁可报「找不到棋盘」 */
    const val MIN_PIECES = 6

    /** 只保留尺寸落在中位值这个倍率内的框（真实棋子 0.98~1.04 倍，装饰立牌 2 倍以上） */
    const val SIZE_LO = 0.70f
    const val SIZE_HI = 1.40f

    /**
     * 框宽 / 子宽中位 的合理区间。
     *
     * 棋盘 9 列 8 个间隔，框宽恒等于 8 个格距，而一颗子的直径 ≈ 1 个格距
     * （实测 1440x3168：框宽 1254、子宽中位 145.7 → 8.61；框高 1363、子高 145.9 → 9.34）。
     * 下界留到 6.8 能容忍子框偏大，上界 9.5 挡住「横躺的整块棋盘」（实测 9.10x6.16 与 6.63x7.59 都会被挡）。
     */
    const val WIDTH_RATIO_LO = 6.8f
    const val WIDTH_RATIO_HI = 9.5f

    /** 框高 / 子高中位 的合理区间：棋盘 10 行 9 个间隔，恒等于 9 个格距 */
    const val HEIGHT_RATIO_LO = 7.8f
    const val HEIGHT_RATIO_HI = 10.5f

    /** 包络至少要有这么宽（格数）才可信 —— 少了说明最外圈大面积没子，框会明显小于棋盘 */
    private const val SPAN_MIN_X = 7.0f
    private const val SPAN_MIN_Y = 8.0f

    /**
     * 这个框看起来像不像棋盘 —— 只做尺寸一致性，不看置信度。
     *
     * @param pieceW 棋子宽的中位值（帧像素），传 0 或负数时直接放行（没有棋子可供比对）
     */
    fun sizePlausible(box: Box, pieceW: Float, pieceH: Float): Boolean {
        if (pieceW <= 0f || pieceH <= 0f) return true
        if (box.width <= 0f || box.height <= 0f) return false
        val rx = box.width / pieceW
        val ry = box.height / pieceH
        return rx in WIDTH_RATIO_LO..WIDTH_RATIO_HI && ry in HEIGHT_RATIO_LO..HEIGHT_RATIO_HI
    }

    /** 棋子尺寸的中位值。中位数本身抗离群，所以先把装饰框剔掉再求也不影响结果。 */
    fun medianSize(pieces: List<Detection>): Pair<Float, Float> {
        if (pieces.isEmpty()) return 0f to 0f
        val ws = FloatArray(pieces.size) { pieces[it].box.width }
        val hs = FloatArray(pieces.size) { pieces[it].box.height }
        return median(ws) to median(hs)
    }

    /**
     * 用棋子中心包络合成一个棋盘框；棋子太少或尺寸太散时返回 null。
     *
     * 只用于「棋盘框检不出来」的帧。合成出来的框与真实框等价，
     * 所以下游（裁框 / 尺寸过滤 / 格距范围 / 锚定）一行都不用改。
     */
    fun synth(pieces: List<Detection>): Box? {
        if (pieces.size < MIN_PIECES) return null
        val (wMed, hMed) = medianSize(pieces)
        if (wMed <= 0f || hMed <= 0f) return null

        val kept = pieces.filter {
            val rx = it.box.width / wMed
            val ry = it.box.height / hMed
            rx in SIZE_LO..SIZE_HI && ry in SIZE_LO..SIZE_HI
        }
        if (kept.size < MIN_PIECES) return null

        val x0 = kept.minOf { it.box.centerX }
        val x1 = kept.maxOf { it.box.centerX }
        val y0 = kept.minOf { it.box.centerY }
        val y1 = kept.maxOf { it.box.centerY }

        // 跨度太小说明最外圈大面积没子，此时包络既不是棋盘框、也无法判断缺的是哪一侧。
        // **不去猜**：宁可不合成（上层报「找不到棋盘」），也不要给出一个偏半格的框 ——
        // 那会让锚定整体错位，比直接失败更难排查。
        if ((x1 - x0) / wMed < SPAN_MIN_X || (y1 - y0) / hMed < SPAN_MIN_Y) return null

        return Box(x0, y0, x1, y1)
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.clone()
        sorted.sort()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
    }

    /** 供日志/诊断用：把尺寸比值说清楚 */
    fun describe(box: Box, pieceW: Float, pieceH: Float): String =
        "%.0fx%.0f 尺寸比 %.2fx%.2f".format(
            box.width, box.height,
            if (pieceW > 0f) box.width / pieceW else 0f,
            if (pieceH > 0f) box.height / pieceH else 0f
        )
}
