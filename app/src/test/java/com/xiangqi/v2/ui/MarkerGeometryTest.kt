package com.xiangqi.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四角框的几何。
 *
 * 这几条守的都是「画出来会不会难看/看不见」：
 * 臂的方向反了会变成向外的小爪子；半边长取 0.5 格会让相邻两格的框粘成一条线；
 * 臂长取到半边长会把框画成十字。这些在真机上表现为"标记怪怪的"，说不出哪里错。
 */
class MarkerGeometryTest {

    private val cell = 20f

    @Test
    fun `四个角共八条臂`() {
        val h = MarkerGeometry.halfSide(cell)
        assertEquals(8, MarkerGeometry.corners(100f, 100f, h, MarkerGeometry.armOf(h)).size)
    }

    @Test
    fun `每个角的两条臂都朝中心伸`() {
        val h = MarkerGeometry.halfSide(cell)
        val arm = MarkerGeometry.armOf(h)
        val segments = MarkerGeometry.corners(100f, 100f, h, arm)
        // 左上角 (-h, -h) 起点的两条臂：一条 +x、一条 +y，都是往中心去
        val fromTopLeft = segments.filter { it.x1 == 100f - h && it.y1 == 100f - h }
        assertEquals(2, fromTopLeft.size)
        assertTrue(fromTopLeft.any { it.x2 == 100f - h + arm && it.y2 == 100f - h })
        assertTrue(fromTopLeft.any { it.x2 == 100f - h && it.y2 == 100f - h + arm })
    }

    @Test
    fun `角点都在半边长上`() {
        val h = MarkerGeometry.halfSide(cell)
        val arm = MarkerGeometry.armOf(h)
        val corners = MarkerGeometry.corners(100f, 100f, h, arm).map { it.x1 to it.y1 }.toSet()
        assertEquals(
            setOf(
                100f - h to 100f - h, 100f + h to 100f - h,
                100f - h to 100f + h, 100f + h to 100f + h
            ),
            corners
        )
    }

    @Test
    fun `臂长等于算出来的值`() {
        val h = MarkerGeometry.halfSide(cell)
        val arm = MarkerGeometry.armOf(h)
        val seg = MarkerGeometry.corners(0f, 0f, h, arm).first { it.x1 == -h && it.y1 == -h }
        val len = kotlin.math.hypot(seg.x2 - seg.x1, seg.y2 - seg.y1)
        assertEquals(arm, len, 1e-4f)
    }

    @Test
    fun `框比棋子大一圈，但不压到相邻的格`() {
        val h = MarkerGeometry.halfSide(cell)
        val radius = cell * 0.42f
        assertTrue("框要在棋子圆外：$h vs $radius", h > radius)
        // 半边长必须小于半格，否则相邻两格的框在中间粘成一条线，看起来像一格
        assertTrue("相邻两格的框要留缝：$h", h < cell / 2f)
    }

    @Test
    fun `臂长比半边长短，框不会被画成十字`() {
        val h = MarkerGeometry.halfSide(cell)
        assertTrue(MarkerGeometry.armOf(h) < h)
    }
}
