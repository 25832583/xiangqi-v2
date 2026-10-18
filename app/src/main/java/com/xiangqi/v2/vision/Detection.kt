package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType

/** 轴对齐矩形。坐标含义由调用方所在的空间决定（模型输入空间 / 帧像素）。 */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    val centerX: Float get() = (x0 + x1) * 0.5f
    val centerY: Float get() = (y0 + y1) * 0.5f
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    fun iou(other: Box): Float {
        val iw = (minOf(x1, other.x1) - maxOf(x0, other.x0)).coerceAtLeast(0f)
        val ih = (minOf(y1, other.y1) - maxOf(y0, other.y0)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * 一个检测结果。
 *
 * [classIndex] 是模型输出通道下标，必须与训练时的类别顺序一致（见 [Labels]）。
 * 这里**保留类别**——旧版本只取框位置、把类别丢掉再逐格重判，是本项目最大的坑。
 */
data class Detection(val box: Box, val score: Float, val classIndex: Int) {
    val label: Char get() = Labels.ALL[classIndex]
    val piece: Piece? get() = Piece.fromFen(label)
    val isBoard: Boolean get() = classIndex == Labels.BOARD
}

/**
 * 类别表 —— 与 `data/xiangqi.yaml` 的顺序逐字一致，改一处必须同时改两边。
 *
 * 小写 = 黑方，大写 = 红方，`0` = 棋盘。这是 xqlink 的 YOLOv5 15 类模型；
 * 换成 14 类（不要棋盘）或 YOLOv8 时，只需改 [ALL] 与 [BOARD]。
 */
object Labels {
    val ALL: CharArray = charArrayOf('n', 'b', 'a', 'k', 'r', 'c', 'p', 'R', 'N', 'A', 'K', 'B', 'C', 'P', '0')
    const val BOARD: Int = 14
    val COUNT: Int get() = ALL.size

    /** 单类保留上限：直接复用 [PieceType.limit]，避免与棋子定义各自维护一份。 */
    fun limitOf(label: Char): Int =
        if (label == '0') 1 else PieceType.fromLetter(label)?.limit ?: 1
}
