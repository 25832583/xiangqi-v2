package com.xiangqi.v2.vision

/**
 * 按类别分组的非极大值抑制。
 *
 * 只抑制**同类别**的重叠框。跨类别一起抑制会让相邻的不同棋子互相吃掉
 * （车马相仕在底线挨得很近，跨类 NMS 是灾难）。
 *
 * 裁剪到 [Labels.limitOf]：某类多出来的框直接丢弃，而不是留到下游触发
 * "盘面非法 → 整帧作废"。**置信度排序是唯一的裁决依据**，不做额外猜测。
 */
object Nms {

    fun byClass(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val out = ArrayList<Detection>(detections.size)
        for ((classIndex, group) in detections.groupBy { it.classIndex }) {
            val limit = Labels.limitOf(Labels.ALL[classIndex])
            val pending = group.sortedByDescending { it.score }.toMutableList()
            var kept = 0
            while (pending.isNotEmpty() && kept < limit) {
                val current = pending.removeAt(0)
                out.add(current)
                kept++
                pending.removeAll { current.box.iou(it.box) >= iouThreshold }
            }
        }
        return out.sortedByDescending { it.score }
    }
}
