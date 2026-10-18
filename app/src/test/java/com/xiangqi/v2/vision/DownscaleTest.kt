package com.xiangqi.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分步降采样的尺寸计划。
 *
 * 这些数字不是随便取的：1280x2816（本机实测分辨率）与 1080x2400（常见机型）
 * 都是「整屏帧 -> 640 letterbox」的场景，倍率 4.4 / 3.75，**必须**走多步。
 */
class DownscaleTest {

    @Test
    fun `整屏竖屏帧要拆成多步减半`() {
        val steps = Downscale.plan(1280, 2816, 291, 640)
        assertEquals(3, steps.size)
        assertEquals(640, steps[0][0])
        assertEquals(1408, steps[0][1])
        assertEquals(320, steps[1][0])
        assertEquals(704, steps[1][1])
        // 末项必须精确等于目标，否则贴到画布上会再被缩一次
        assertEquals(291, steps.last()[0])
        assertEquals(640, steps.last()[1])
    }

    @Test
    fun `每一步的缩小倍数都不超过 2`() {
        for ((sw, sh) in listOf(1280 to 2816, 1080 to 2400, 1440 to 3200, 720 to 1600)) {
            val dw = Math.round(sw * minOf(640f / sw, 640f / sh))
            val dh = Math.round(sh * minOf(640f / sw, 640f / sh))
            val steps = Downscale.plan(sw, sh, dw, dh)

            var prevW = sw
            var prevH = sh
            for (step in steps) {
                val rx = prevW.toFloat() / step[0]
                val ry = prevH.toFloat() / step[1]
                assertTrue("${sw}x$sh 出现了 ${rx}x 的单步缩放", rx <= 2.0001f)
                assertTrue("${sw}x$sh 出现了 ${ry}x 的单步缩放", ry <= 2.0001f)
                prevW = step[0]
                prevH = step[1]
            }
            assertEquals(dw, prevW)
            assertEquals(dh, prevH)
        }
    }

    @Test
    fun `源尺寸等于目标时不产生任何步骤`() {
        assertTrue(Downscale.plan(640, 640, 640, 640).isEmpty())
    }

    @Test
    fun `放大不拆步`() {
        val steps = Downscale.plan(320, 320, 640, 640)
        assertEquals(1, steps.size)
        assertEquals(640, steps[0][0])
    }

    @Test
    fun `letterbox 取整后坐标仍能还原`() {
        // 1280x2816 的实际取整：scale 0.2272...，scaledWidth 291、scaledHeight 640
        val lb = Letterbox.of(1280, 2816, 640)
        assertEquals(291, lb.scaledWidth)
        assertEquals(640, lb.scaledHeight)
        // 用回算的 scaleX/scaleY 换算，来回一趟误差必须远小于 1px
        val x = 1000.5f
        assertEquals(x, lb.toFrameX(lb.padX + x * lb.scaleX), 1e-2f)
        val y = 2345.25f
        assertEquals(y, lb.toFrameY(lb.padY + y * lb.scaleY), 1e-2f)
    }
}
