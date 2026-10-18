package com.xiangqi.v2.vision

import kotlin.math.min
import kotlin.math.roundToInt

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
 *
 * [!] 缩放本身必须是**分步**做的，见 [Downscale]：整屏帧到 640 是 4 倍以上降采样，
 * 一次双线性会严重混叠，红方笔画密的子会认成别的子（实测「红车→红炮」）。
 * 这里保存取整后的 [scaledWidth]/[scaledHeight]，坐标换算用回算的 [scaleX]/[scaleY]，
 * 而不是名义倍率 —— 否则 0.5px 的取整误差除以 0.227 会被放大成 2px。
 */
data class Letterbox(
    val inputSize: Int,
    /** 名义倍率（长边正好贴合），只用于日志与展示。 */
    val scale: Float,
    /** 实际源->模型 的横向倍率 = scaledWidth / frameWidth。 */
    val scaleX: Float,
    /** 实际源->模型 的纵向倍率 = scaledHeight / frameHeight。 */
    val scaleY: Float,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Float,
    val padY: Float
) {

    fun toFrameX(x: Float): Float = (x - padX) / scaleX
    fun toFrameY(y: Float): Float = (y - padY) / scaleY

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
            val sw = (frameWidth * s).roundToInt().coerceIn(1, inputSize)
            val sh = (frameHeight * s).roundToInt().coerceIn(1, inputSize)
            return Letterbox(
                inputSize = inputSize,
                scale = s,
                scaleX = sw.toFloat() / frameWidth,
                scaleY = sh.toFloat() / frameHeight,
                scaledWidth = sw,
                scaledHeight = sh,
                padX = (inputSize - sw) * 0.5f,
                padY = (inputSize - sh) * 0.5f
            )
        }
    }
}
