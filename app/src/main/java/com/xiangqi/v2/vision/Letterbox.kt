package com.xiangqi.v2.vision

import kotlin.math.min

/**
 * 等比缩放 + 灰边填充（letterbox）。
 *
 * **这是本项目第一个决定性修正。** 旧产线用「直接拉伸到 640x640」，1080x2400 的截图
 * 纵向被压掉 55%，同一个模型在同一批真实截图上的表现（实测，`tools/probe_detector.py`）：
 *
 * | 预处理 | 检出 | 类别 | 中位置信 |
 * |---|---|---|---|
 * | 拉伸 | 35 个，红方 4 个"马"7 个"兵" | **错乱** | 0.763 |
 * | letterbox | 33 个，黑方 16/16 全对 | 正确 | **0.930** |
 *
 * 模型训练用的是正方形画布（棋盘居中、四周背景），拉伸把比例破坏掉，
 * 棋子成了椭圆 —— 类别随之崩掉。换预处理不改一行模型代码就恢复了。
 */
data class Letterbox(val inputSize: Int, val scale: Float, val padX: Float, val padY: Float) {

    fun toFrameX(x: Float): Float = (x - padX) / scale
    fun toFrameY(y: Float): Float = (y - padY) / scale

    /** 模型输入空间的框 -> 帧像素的框。 */
    fun toFrame(box: Box): Box = Box(
        toFrameX(box.x0), toFrameY(box.y0), toFrameX(box.x1), toFrameY(box.y1)
    )

    companion object {
        /** 填充色 114 是 YOLO 官方默认值，与训练集预处理一致。 */
        const val FILL: Int = 114

        fun of(frameWidth: Int, frameHeight: Int, inputSize: Int): Letterbox {
            require(frameWidth > 0 && frameHeight > 0) { "帧尺寸非法：${frameWidth}x$frameHeight" }
            require(inputSize > 0) { "模型输入尺寸非法：$inputSize" }
            val s = min(inputSize.toFloat() / frameWidth, inputSize.toFloat() / frameHeight)
            return Letterbox(
                inputSize = inputSize,
                scale = s,
                padX = (inputSize - frameWidth * s) * 0.5f,
                padY = (inputSize - frameHeight * s) * 0.5f
            )
        }
    }
}
