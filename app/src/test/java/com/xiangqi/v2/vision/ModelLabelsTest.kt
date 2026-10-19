package com.xiangqi.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLabelsTest {

    @Test
    fun `外部类序必须把 15 个类别一一对上本项目的字母`() {
        val foreign = ModelLabels.DEEP_XIANGQI
        assertEquals(Labels.COUNT, foreign.size)
        // 每个字母都要能落到本项目类序里，且**不重复** ——
        // 重复说明表写错了（例如把红黑两套 'p' 当同一个），后果是某两种子互相顶掉
        val localIndexes = foreign.map { Labels.ALL.indexOf(it) }
        assertTrue("有字母不在 Labels.ALL 里：$foreign", localIndexes.none { it < 0 })
        assertEquals("类别映射出现重复：$foreign", Labels.COUNT, localIndexes.toSet().size)
    }

    @Test
    fun `棋盘类下标按外部类序求解而不是写死 14`() {
        // DeepXiangQi 恰好也是 14，但这一点是巧合，不能当成约定
        assertEquals(14, ModelLabels.boardIndex(ModelLabels.DEEP_XIANGQI))
        // 换一个把 board 放在 0 号位的模型，索引必须跟着走
        val reordered = charArrayOf('0', 'a', 'b', 'c', 'k', 'n', 'p', 'r',
            'A', 'B', 'C', 'K', 'N', 'P', 'R')
        assertEquals(0, ModelLabels.boardIndex(reordered))
    }

    @Test
    fun `缺少棋盘类时返回 -1，调用方据此放弃兜底`() {
        val noBoard = charArrayOf('a', 'b', 'c')
        assertEquals(-1, ModelLabels.boardIndex(noBoard))
    }

    @Test
    fun `外部下标越界不会崩，只返回 -1`() {
        assertEquals(-1, ModelLabels.toLocal(ModelLabels.DEEP_XIANGQI, -1))
        assertEquals(-1, ModelLabels.toLocal(ModelLabels.DEEP_XIANGQI, 15))
    }

    @Test
    fun `映射方向不能反`() {
        // 外部 0 号 = 黑士 -> 本项目 'a'（下标 2）
        assertEquals(2, ModelLabels.toLocal(ModelLabels.DEEP_XIANGQI, 0))
        // 外部 4 号 = 黑马 -> 本项目 'n'（下标 0）
        assertEquals(0, ModelLabels.toLocal(ModelLabels.DEEP_XIANGQI, 4))
        // 外部 13 号 = 红车 -> 本项目 'R'（下标 7）
        assertEquals(7, ModelLabels.toLocal(ModelLabels.DEEP_XIANGQI, 13))
        // 反向核对：本项目 'p'（兵）在外部是 5 号
        assertEquals(5, ModelLabels.DEEP_XIANGQI.indexOf('p'))
    }
}
