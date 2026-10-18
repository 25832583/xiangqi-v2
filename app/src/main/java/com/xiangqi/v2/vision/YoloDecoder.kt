package com.xiangqi.v2.vision

/**
 * 检测模型三种输出布局。
 *
 * - [V5_ANCHOR_MAJOR]：YOLOv5，`[1, anchors, 5+nc]`，每行 `cx,cy,w,h,obj,cls0..`，
 *   置信度 = `obj × max(cls)`。现有 `detector.onnx` 是这种（`[1,25200,20]`）。
 * - [V8_CHANNEL_MAJOR]：YOLOv8 官方导出，`[1, 4+nc, anchors]`，**没有 obj 通道**，
 *   置信度 = `max(cls)`。换 YOLOv8 权重时走这条，识别主流程一行都不用改。
 * - [V8_ANCHOR_MAJOR]：部分第三方导出会转置成 `[1, anchors, 4+nc]`。
 *
 * 布局由**张量形状**判定，不看模型版本字符串 —— 形状是唯一可靠的事实。
 */
enum class YoloLayout { V5_ANCHOR_MAJOR, V8_CHANNEL_MAJOR, V8_ANCHOR_MAJOR }

object YoloDecoder {

    fun layoutOf(shape: IntArray, classCount: Int): YoloLayout {
        require(shape.size >= 2) { "输出张量至少 2 维，实际 ${shape.toList()}" }
        val a = shape[shape.size - 2]
        val b = shape[shape.size - 1]
        val v5Width = 5 + classCount
        val v8Width = 4 + classCount
        return when {
            b == v5Width -> YoloLayout.V5_ANCHOR_MAJOR
            a == v8Width && b != v8Width -> YoloLayout.V8_CHANNEL_MAJOR
            b == v8Width && a != v8Width -> YoloLayout.V8_ANCHOR_MAJOR
            a == v8Width -> YoloLayout.V8_CHANNEL_MAJOR
            else -> throw IllegalArgumentException(
                "无法识别的输出形状 ${shape.toList()}（类别数 $classCount，" +
                    "既不是 ${v5Width} 也不是 ${v8Width} 宽）"
            )
        }
    }

    /**
     * 解析模型原始输出，产出**未做 NMS** 的候选框。
     *
     * 坐标保持模型输入空间（如 640x640），由调用方用 [Letterbox] 换算到帧像素。
     */
    fun decode(
        data: FloatArray,
        shape: IntArray,
        classCount: Int,
        confidenceThreshold: Float
    ): List<Detection> {
        val layout = layoutOf(shape, classCount)
        val rows = shape[shape.size - 2]
        val cols = shape[shape.size - 1]
        val out = ArrayList<Detection>(128)

        when (layout) {
            YoloLayout.V5_ANCHOR_MAJOR -> {
                for (anchor in 0 until rows) {
                    val base = anchor * cols
                    val objectness = data[base + 4]
                    if (objectness <= 0f) continue
                    var bestScore = -1f
                    var bestClass = 0
                    for (c in 0 until classCount) {
                        val s = data[base + 5 + c]
                        if (s > bestScore) { bestScore = s; bestClass = c }
                    }
                    val score = objectness * bestScore
                    if (score < confidenceThreshold) continue
                    out.add(boxAt(data, base, bestClass, score))
                }
            }
            YoloLayout.V8_CHANNEL_MAJOR -> {
                for (anchor in 0 until cols) {
                    var bestScore = -1f
                    var bestClass = 0
                    for (c in 0 until classCount) {
                        val s = data[(4 + c) * cols + anchor]
                        if (s > bestScore) { bestScore = s; bestClass = c }
                    }
                    if (bestScore < confidenceThreshold) continue
                    out.add(
                        Detection(
                            box = Box(
                                data[anchor] - data[2 * cols + anchor] * 0.5f,
                                data[cols + anchor] - data[3 * cols + anchor] * 0.5f,
                                data[anchor] + data[2 * cols + anchor] * 0.5f,
                                data[cols + anchor] + data[3 * cols + anchor] * 0.5f
                            ),
                            score = bestScore,
                            classIndex = bestClass
                        )
                    )
                }
            }
            YoloLayout.V8_ANCHOR_MAJOR -> {
                for (anchor in 0 until rows) {
                    val base = anchor * cols
                    var bestScore = -1f
                    var bestClass = 0
                    for (c in 0 until classCount) {
                        val s = data[base + 4 + c]
                        if (s > bestScore) { bestScore = s; bestClass = c }
                    }
                    if (bestScore < confidenceThreshold) continue
                    out.add(
                        Detection(
                            box = Box(
                                data[base] - data[base + 2] * 0.5f,
                                data[base + 1] - data[base + 3] * 0.5f,
                                data[base] + data[base + 2] * 0.5f,
                                data[base + 1] + data[base + 3] * 0.5f
                            ),
                            score = bestScore,
                            classIndex = bestClass
                        )
                    )
                }
            }
        }
        return out
    }

    private fun boxAt(data: FloatArray, base: Int, classIndex: Int, score: Float): Detection {
        val cx = data[base]
        val cy = data[base + 1]
        val w = data[base + 2]
        val h = data[base + 3]
        return Detection(
            box = Box(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f),
            score = score,
            classIndex = classIndex
        )
    }
}
