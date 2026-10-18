package com.xiangqi.v2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别链路的可调参数。
 *
 * 这里不重复检测具体数字，只钉住那些「改错会直接造成用户报 bug」的约束：
 * - minPieces 不能低于 3（否则 AxisFitter 会返回 null，残局被误报遮挡）
 * - 置信度、IoU 门槛必须在合理区间（防配置被意外改崩）
 */
class RecognitionConfigTest {

    private val config = RecognitionConfig()

    @Test
    fun `minPieces 不能低于 3 否则拟合会失败`() {
        // AxisFitter.fit 要求 values.size >= 3，minPieces 如果更低，
        // 残局 2 子局面会进入拟合然后失败，反而被报“格线对不上”。
        assertTrue("minPieces=${config.minPieces} 小于 3 会导致 AxisFitter 直接失败", config.minPieces >= 3)
    }

    @Test
    fun `minPieces 默认值为 3 让正常残局不被当成遮挡`() {
        assertEquals(3, config.minPieces)
    }

    @Test
    fun `置信度门槛在合理区间`() {
        assertTrue(config.confidenceThreshold in 0.1f..0.5f)
    }

    @Test
    fun `IoU 门槛在合理区间`() {
        assertTrue(config.iouThreshold in 0.3f..0.7f)
    }
}
