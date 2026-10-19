package com.xiangqi.v2.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * 帧 -> 模型输入张量。主检测器与棋盘兜底定位器共用这一段。
 *
 * 抽出来不是因为「代码重复不好看」，而是因为这段预处理**本身是结论**：
 * 逐级减半（[Downscale]）、letterbox 到 114 灰边、绝不二次缩放（[letterboxed] 里
 * 1:1 贴合那一行）。任何一个模型只要换成「直接拉伸」「一次双线性缩到目标」，
 * 都会重演「红车认成红炮」。两个模型各写一份，迟早有一份被改坏而没人发现 ——
 * 离线脚本 `tools/_locator_ab.py` 里也是逐行复刻这一份，三处必须保持一致。
 */
object DetectorInput {

    /** 预处理一张帧，返回 NCHW + RGB + 0..1 的浮点数组。 */
    fun tensor(frame: Bitmap, lb: Letterbox): FloatArray {
        val square = letterboxed(frame, lb)
        val chw = toChw(square)
        if (square !== frame) square.recycle()
        return chw
    }

    private fun letterboxed(frame: Bitmap, lb: Letterbox): Bitmap {
        val size = lb.inputSize
        val scaled = downscale(frame, lb)
        val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(Color.rgb(Letterbox.FILL, Letterbox.FILL, Letterbox.FILL))
        // 分步缩放后尺寸已精确等于目标，这里 1:1 贴合 —— 绝不能让 Canvas 再缩第二次，
        // 那会把 [Downscale] 省下来的采样精度重新丢掉
        val dst = RectF(
            lb.padX,
            lb.padY,
            lb.padX + lb.scaledWidth,
            lb.padY + lb.scaledHeight
        )
        c.drawBitmap(scaled, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== frame) scaled.recycle()
        return canvas
    }

    /**
     * 逐级减半缩到 letterbox 的目标尺寸。
     *
     * 整屏 1280x2816 -> 640 是 4.4 倍降采样，直接交给 Canvas 的 2x2 双线性会丢掉九成
     * 源像素（混叠），红方笔画密的棋子会被认成别的种类。见 [Downscale] 的实测表。
     */
    private fun downscale(frame: Bitmap, lb: Letterbox): Bitmap {
        var cur = frame
        for (step in Downscale.plan(frame.width, frame.height, lb.scaledWidth, lb.scaledHeight)) {
            val next = Bitmap.createScaledBitmap(cur, step[0], step[1], true)
            if (cur !== frame) cur.recycle()
            cur = next
        }
        return cur
    }

    /** NCHW + RGB + 0..1，与导出 ONNX 时的预处理一致。 */
    private fun toChw(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val color = pixels[i]
            out[i] = ((color shr 16) and 0xff) / 255f
            out[n + i] = ((color shr 8) and 0xff) / 255f
            out[2 * n + i] = (color and 0xff) / 255f
        }
        return out
    }
}
