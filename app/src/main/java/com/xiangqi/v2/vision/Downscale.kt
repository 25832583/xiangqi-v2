package com.xiangqi.v2.vision

/**
 * 分步降采样 —— **这是「真机认错、离线认对」的根因修复**。
 *
 * 1280x2816 的整屏帧要 letterbox 到 640，倍率只有 `640/2816 ≈ 0.227`，
 * 也就是一次降采样 4.4 倍。而 Android 的 `FILTER_BITMAP` 只有 2x2 双线性：
 * 4.4 倍降采样时它只看到 4 个源像素，剩下 90% 的像素直接被扔掉 —— 混叠。
 * 棋子字迹（尤其「俥」「相」这类笔画密的红字）在 30px 直径上被采样得七零八落，
 * 类别就飘了：实测同一张图、同一个 ONNX，
 *
 * | 降采样方式 | (3,5) 红车 的判定 |
 * |---|---|
 * | 抗混叠（area，PIL 的 BILINEAR 缩小也等效） | 红车 0.897 |
 * | 单次双线性（≈ Android Canvas） | **红炮 0.706** |
 * | 最近邻 | **红相 0.490** |
 *
 * 修法不是换重采样算法（Skia 只给双线性），而是**分步减半**：
 * 每次只缩 2 倍时，2x2 双线性恰好覆盖完整的 2x2 邻域，等于一次 box 平均，
 * 不会漏像素。到最后一步比例已接近 1，再插值也不损失。
 */
object Downscale {

    /** 单步允许的最大缩小倍数。2 是双线性核的天然上限。 */
    const val MAX_STEP_RATIO: Float = 2f

    /**
     * 分步缩放的尺寸序列（不含源尺寸，末项恒等于目标尺寸）。
     *
     * 判据用两个方向的**较小**缩小倍数：只要短边这一步还没到 2 倍以内就继续减半。
     * 用 `&&` 会漏掉「一边要缩 4 倍、另一边只缩 1.2 倍」的情形（例如方形小图
     * 只缩宽度），那种情况会退化回一次大比例缩放。
     */
    fun plan(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): List<IntArray> {
        require(srcWidth > 0 && srcHeight > 0) { "源尺寸非法：${srcWidth}x$srcHeight" }
        require(dstWidth > 0 && dstHeight > 0) { "目标尺寸非法：${dstWidth}x$dstHeight" }

        val steps = ArrayList<IntArray>(8)
        var w = srcWidth
        var h = srcHeight
        while (minOf(w.toFloat() / dstWidth, h.toFloat() / dstHeight) > MAX_STEP_RATIO) {
            val nw = maxOf(w / 2, dstWidth)
            val nh = maxOf(h / 2, dstHeight)
            if (nw == w && nh == h) break
            w = nw
            h = nh
            steps.add(intArrayOf(w, h))
        }
        // 末项恒为精确目标尺寸：上面的减半会落在偶数尺寸上，收尾要落到精确值
        if (w != dstWidth || h != dstHeight) steps.add(intArrayOf(dstWidth, dstHeight))
        return steps
    }
}
