package com.xiangqi.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 箭头几何：守住「一步棋也必须画出箭头」。
 *
 * 现场问题是「画线有时给有时不给」，根因是**纯算术**：小棋盘的格距由 200dp 高度定死，
 * 两圆之间只剩 0.16 个格距，按「两端各让出半径 + 1dp」算出来是负长度，
 * 于是走一格时整条箭头（连起点圈）都被丢掉。马、象、车炮走两格以上都正常，
 * 只有兵/车/将走一格什么都没有 —— 现象正是**选择性的**。
 *
 * Canvas 在 JVM 单测里画不出来，所以算式抽到 [ArrowGeometry]，在这里钉住。
 * 数值按真实面板取：格距 ≈ 21.8dp、棋子半径 0.42 格、density 3。
 */
class ArrowGeometryTest {

    private val density = 3f
    private fun px(dp: Float) = dp * density

    private val cell = px(21.8f)          // (200dp 高度 - 4dp 内边距) / 9
    private val radius = cell * 0.42f
    private val edgeGap = px(1f)
    private val minShaft = px(8f)
    private val maxHead = px(6f)

    private fun layout(length: Float) =
        ArrowGeometry.layout(length, radius, edgeGap, minShaft, maxHead)

    @Test
    fun `走一格必须画出箭头`() {
        val l = layout(cell)
        assertNotNull("相邻一格（兵进一、车退一、将进一）不能是空的", l)
        l!!
        assertTrue("中线至少留 8dp，实际 ${l.visible / density}dp", l.visible >= minShaft - 0.01f)
        assertTrue("箭头头长必须为正", l.head > 0f)
    }

    @Test
    fun `走一格时线仍落在汉字外沿`() {
        val l = layout(cell)!!
        // 压进圆边的距离 = 半径 − 让出量。汉字大致占以圆心为中心的 0.62 格见方，
        // 半个字宽 ≈ 0.31 格。线头必须停在这个框**外面**，否则会划在字上。
        val intrusion = radius - l.margin
        val halfGlyph = cell * 0.31f
        assertTrue(
            "压进圆边 ${intrusion / density}dp，超过半个字宽 ${halfGlyph / density}dp 就会糊字",
            intrusion < halfGlyph
        )
        assertTrue(
            "线头 ${l.margin / density}dp 已经进到字框里（半字宽 ${halfGlyph / density}dp）",
            l.margin >= halfGlyph * 0.95f
        )
        assertTrue("不能插到圆心附近", l.margin >= radius * 0.5f)
    }

    @Test
    fun `走两格以上恢复到标准让出量`() {
        val l = layout(cell * 2f)!!
        assertEquals("两格时不需要压进圆里", radius + edgeGap, l.margin, 0.01f)
        assertTrue("头长不能超过上限", l.head <= maxHead + 0.01f)
    }

    @Test
    fun `中线长度随距离单调不减，且永不低于下限`() {
        var previous = 0f
        // 从略大于下限处起步：恰好等于 minShaft 时让出量为 0，按约定返回 null（见下一条）
        var length = minShaft * 1.2f
        while (length <= cell * 9f) {
            val l = layout(length)
            assertNotNull("距离 ${length / density}dp 不该画不出来", l)
            l!!
            assertTrue("可见长度跌破下限：${l.visible / density}dp", l.visible >= minShaft - 0.01f)
            assertTrue("可见长度不该随距离变短", l.visible >= previous - 0.01f)
            previous = l.visible
            length += px(0.5f)
        }
    }

    @Test
    fun `头长永不吃掉整条中线`() {
        var length = minShaft * 1.2f
        while (length <= cell * 9f) {
            val l = layout(length)!!
            assertTrue("头长 ${l.head} 应明显短于中线 ${l.visible}", l.head <= l.visible * 0.6f)
            length += px(0.5f)
        }
    }

    @Test
    fun `起终点重合或近到没有意义时不画`() {
        assertNull("同一格", layout(0f))
        assertNull("比最小中线还短", layout(px(4f)))
    }
}
