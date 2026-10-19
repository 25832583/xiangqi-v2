package com.xiangqi.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 棋盘框兜底：尺寸校验 + 用棋子中心反推棋盘框。
 *
 * 全部数字来自 JJ象棋「32强战」皮肤的 7 张真实截图（1440x3168）：
 * 真实棋子 145~152px（≈1 格），棋盘框 1246~1254 x 1336~1363，格距 156.4x151.2。
 */
class BoardBoxSynthTest {

    private val cls = Labels.ALL.indexOf('R')

    /** 真机实测：子宽中位 145.7、棋盘框 1254 x 1363 */
    private fun piece(cx: Float, cy: Float, size: Float = 146f) =
        Detection(Box(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), 0.9f, cls)

    @Test
    fun `真实棋盘框通过尺寸校验`() {
        val board = Box(90f, 944f, 1344f, 2307f)
        assertTrue(BoardBoxSynth.sizePlausible(board, 145.7f, 145.9f))
    }

    @Test
    fun `残缺框与横躺框都过不了尺寸校验`() {
        // 1a0ff692 实测：conf 0.214，967x1104 —— 比真框置信还低一点点，但其实是残废框
        assertFalse(BoardBoxSynth.sizePlausible(Box(258f, 1121f, 1225f, 2225f), 145.8f, 145.5f))
        // 同一张图上另一个候选：1327x897，横躺
        assertFalse(BoardBoxSynth.sizePlausible(Box(47f, 761f, 1374f, 1658f), 145.8f, 145.5f))
    }

    @Test
    fun `没有棋子可比对时放行`() {
        // 棋子一个都没检出时无法判断尺寸，此时不该额外加一道否决
        assertTrue(BoardBoxSynth.sizePlausible(Box(0f, 0f, 10f, 10f), 0f, 0f))
    }

    @Test
    fun `棋子中心包络就是棋盘框`() {
        // 列中心 95 + 156.5*k，行中心 893 + 151.3*k，首末列/行都有子
        val xs = listOf(95f, 251.5f, 408f, 564.5f, 721f, 877.5f, 1034f, 1190.5f, 1347f)
        val ys = listOf(893f, 1044.3f, 1195.6f, 1346.9f, 1498.2f, 1649.5f, 1800.8f, 1952.1f,
            2103.4f, 2254.7f)
        val pieces = ArrayList<Detection>()
        for (y in ys) for (x in xs) pieces += piece(x, y)

        val box = BoardBoxSynth.synth(pieces)
        assertNotNull(box)
        assertEquals(xs.first(), box!!.x0, 0.5f)
        assertEquals(xs.last(), box.x1, 0.5f)
        assertEquals(ys.first(), box.y0, 0.5f)
        assertEquals(ys.last(), box.y1, 0.5f)
    }

    @Test
    fun `棋盘上方的装饰立牌被剔除`() {
        // 1a0ff692 实测：棋盘上方 conf 0.52、尺寸 300x296（2.06 倍）、中心 y=472
        val xs = listOf(95f, 251.5f, 408f, 564.5f, 721f, 877.5f, 1034f, 1190.5f, 1347f)
        val ys = listOf(893f, 1044.3f, 1195.6f, 1346.9f, 1498.2f, 1649.5f, 1800.8f, 1952.1f,
            2103.4f, 2254.7f)
        val pieces = ArrayList<Detection>()
        for (y in ys) for (x in xs) pieces += piece(x, y)
        pieces += Detection(Box(721f - 150f, 472f - 148f, 721f + 150f, 472f + 148f), 0.52f, cls)

        val box = BoardBoxSynth.synth(pieces)!!
        // 有离群框时框顶会被拉高两格多，这里的断言就是防这个
        assertEquals(893f, box.y0, 0.5f)
    }

    @Test
    fun `最外两列没子时放弃合成`() {
        // 只剩列 2..8（跨度 6.4 格），既不是棋盘框、也不知道缺的是哪一侧 —— 不猜
        val xs = listOf(408f, 564.5f, 721f, 877.5f, 1034f, 1190.5f, 1347f)
        val ys = listOf(893f, 1044.3f, 1195.6f, 1346.9f, 1498.2f, 1649.5f, 1800.8f, 1952.1f,
            2103.4f, 2254.7f)
        val pieces = ArrayList<Detection>()
        for (y in ys) for (x in xs) pieces += piece(x, y)

        assertNull(BoardBoxSynth.synth(pieces))
    }

    @Test
    fun `只缺最外一列时仍能用实际包络`() {
        // 列 1..8（跨度 7.5 格）：包络比真框窄一格，但仍在可接受范围，
        // 后续的格距拟合会把 pitch 找回来（拟合范围由框宽推出，7.5 格宽足以覆盖真 pitch）
        val xs = listOf(251.5f, 408f, 564.5f, 721f, 877.5f, 1034f, 1190.5f, 1347f)
        val ys = listOf(893f, 1044.3f, 1195.6f, 1346.9f, 1498.2f, 1649.5f, 1800.8f, 1952.1f,
            2103.4f, 2254.7f)
        val pieces = ArrayList<Detection>()
        for (y in ys) for (x in xs) pieces += piece(x, y)

        val box = BoardBoxSynth.synth(pieces)!!
        assertEquals(251.5f, box.x0, 0.5f)
        assertEquals(1347f, box.x1, 0.5f)
    }

    @Test
    fun `棋子太少时放弃合成`() {
        val pieces = (0 until 5).map { piece(100f + it * 150f, 500f) }
        assertNull(BoardBoxSynth.synth(pieces))
    }

    @Test
    fun `尺寸全是离群值时放弃合成`() {
        // 只剩大立牌那种 2 倍框：中位尺寸被带成 2 倍，但自身也"正常"了，
        // 这里构造一半正常一半离群，正常的那半不足 MIN_PIECES
        val pieces = ArrayList<Detection>()
        repeat(3) { pieces += piece(100f + it * 150f, 500f) }
        repeat(5) { pieces += Detection(Box(0f, 0f, 300f, 300f), 0.9f, cls) }
        // 5 个 300px 的框成为中位（300），3 个 146px 被当成离群剔除，剩下 5 < 6
        assertNull(BoardBoxSynth.synth(pieces))
    }

    @Test
    fun `中位尺寸抗离群`() {
        val pieces = ArrayList<Detection>()
        repeat(29) { pieces += piece(100f + it * 150f, 500f) }
        pieces += Detection(Box(0f, 0f, 300f, 300f), 0.9f, cls)
        val (w, h) = BoardBoxSynth.medianSize(pieces)
        assertEquals(146f, w, 0.5f)
        assertEquals(146f, h, 0.5f)
    }

    // ---------- toGridBox：把「整块棋盘框」归一成「格线框」 ----------

    @Test
    fun `挤进 19 百分之一的棋盘外框会被归一成 8x9 格`() {
        // 兜底模型实测：1441x1630 的框配 146x145 的子（注意 Box 存的是四边坐标）
        val frame = Box(0f, 700f, 1441f, 700f + 1630f)
        val grid = BoardBoxSynth.toGridBox(frame, 146f, 145f)
        assertEquals(8 * 146f, grid.width, 1f)
        assertEquals(9 * 145f, grid.height, 1f)
        assertEquals(frame.centerX, grid.centerX, 0.01f)
        assertEquals(frame.centerY, grid.centerY, 0.01f)
    }

    @Test
    fun `贴合格线的框不动`() {
        val tight = Box(90f, 780f, 90f + 8 * 146f, 780f + 9 * 146f)
        assertEquals(tight, BoardBoxSynth.toGridBox(tight, 146f, 146f))
    }

    @Test
    fun `没有棋子尺寸时原样返回，不做猜测`() {
        val frame = Box(0f, 700f, 1441f, 1630f)
        assertEquals(frame, BoardBoxSynth.toGridBox(frame, 0f, 0f))
    }

    @Test
    fun `离谱大的框最多只缩到 MIN_SCALE，剩下交给尺寸判据拒掉`() {
        // 15 格宽：归一化需要缩 0.53，被 MIN_SCALE 夹住 -> 只剩 0.78，最终仍不合格
        val huge = Box(0f, 0f, 15 * 146f, 17 * 146f)
        val grid = BoardBoxSynth.toGridBox(huge, 146f, 146f)
        assertEquals(15 * 146f * BoardBoxSynth.MIN_SCALE, grid.width, 1f)
        assertFalse(BoardBoxSynth.sizePlausible(grid, 146f, 146f))
    }
}
