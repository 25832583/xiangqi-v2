package com.xiangqi.v2.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.xiangqi.v2.config.RecognitionConfig
import com.xiangqi.v2.infra.OnnxModel

data class DetectorOutput(
    /** 坐标已换算到**帧像素** */
    val detections: List<Detection>,
    val letterbox: Letterbox
) {
    val boards: List<Detection> get() = detections.filter { it.isBoard }
    val pieces: List<Detection> get() = detections.filter { !it.isBoard }
}

/**
 * ONNX 检测器：一次推理拿到「棋盘框 + 所有棋子框 + 类别」。
 *
 * 与旧产线的本质区别不是模型，而是**不再丢信息**：
 * 旧链路把这里的类别扔掉、只拿位置，再花 90 次推理逐格重判；
 * 这里类别和位置同源，直接进几何映射。
 */
class OnnxDetector(
    private val model: OnnxModel,
    private val config: RecognitionConfig
) {

    fun load() = model.load()

    fun close() = model.close()

    fun detect(frame: Bitmap, threshold: Float = config.confidenceThreshold): DetectorOutput {
        val lb = Letterbox.of(frame.width, frame.height, config.inputSize)
        val square = letterboxed(frame, lb)
        val chw = toChw(square)
        if (square !== frame) square.recycle()

        val raw = model.run(chw)
        val shape = model.lastOutputShape
        // 走到这里说明推理本身已经跑完了，所以「形状未知」跟 load() 成没成功无关 ——
        // 是形状的读取路径出了问题（真机上踩过一次：ORT 1.19 的 getOutputInfo()
        // 返回的是 NodeInfo 包装，老代码拆包失败，静默拿到空形状）。
        require(shape.isNotEmpty()) {
            "推理已返回 ${raw.size} 个浮点，但输出形状为空 —— 张量形状也没有被读出来，" +
                "检查 ORT 版本与 OnnxModel.readOutputShape 的假设是否还一致"
        }

        val candidates = YoloDecoder.decode(raw, shape, Labels.COUNT, threshold)
        val kept = Nms.byClass(candidates, config.iouThreshold)
        return DetectorOutput(kept.map { it.copy(box = lb.toFrame(it.box)) }, lb)
    }

    private fun letterboxed(frame: Bitmap, lb: Letterbox): Bitmap {
        val size = config.inputSize
        val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(Color.rgb(Letterbox.FILL, Letterbox.FILL, Letterbox.FILL))
        val dst = RectF(
            lb.padX,
            lb.padY,
            lb.padX + frame.width * lb.scale,
            lb.padY + frame.height * lb.scale
        )
        c.drawBitmap(frame, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return canvas
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
