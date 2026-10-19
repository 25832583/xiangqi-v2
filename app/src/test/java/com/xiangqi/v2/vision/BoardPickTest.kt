package com.xiangqi.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 兜底模型给出的候选框里，「挑哪一个当棋盘」。
 *
 * 这些用例全部来自真实失败帧（JJ象棋「32强战」皮肤，1440x3168）：
 * 主模型对同一块棋盘同时给出一个正经框和若干残缺/横躺框，而**残缺框的置信度更高**。
 * 所以判据不能是「取最高分」，必须是「先按尺寸筛，再取最高分」。
 */
class BoardPickTest {

    private val board = Labels.BOARD
    private val pieceW = 145f
    private val pieceH = 145f

    private fun det(x0: Float, y0: Float, w: Float, h: Float, score: Float, cls: Int = board) =
        Detection(Box(x0, y0, x0 + w, y0 + h), score, cls)

    private fun pick(cands: List<Detection>) =
        BoardPick.best(cands, board, pieceW, pieceH, 0.6f, 1.6f)

    @Test
    fun `残缺框分数更高时也要选尺寸正确的那个`() {
        val broken = det(240f, 900f, 967f, 1104f, 0.62f)      // 6.7x7.6 格，配不上棋盘
        val good = det(90f, 780f, 1254f, 1363f, 0.31f)        // 8.6x9.4 格
        assertEquals(good, pick(listOf(broken, good)))
    }

    @Test
    fun `横躺的整块棋盘被尺寸判据挡掉`() {
        // 实测 1327x897：9.10x6.16 格，宽高比 1.48 仍落在形状窗口内，只能靠尺寸挡
        val lying = det(50f, 900f, 1327f, 897f, 0.55f)
        assertNull(pick(listOf(lying)))
    }

    @Test
    fun `单框尺寸正好是 8x9 颗子时可选`() {
        val ok = det(90f, 780f, 8 * pieceW, 9 * pieceH, 0.79f)
        assertEquals(ok, pick(listOf(ok)))
    }

    @Test
    fun `非棋盘类别再高分也不选`() {
        val rook = det(90f, 780f, 8 * pieceW, 9 * pieceH, 0.99f, Labels.ALL.indexOf('r'))
        assertNull(pick(listOf(rook)))
    }

    @Test
    fun `宽高比超窗口的框直接排除，哪怕尺寸对`() {
        val wide = det(0f, 900f, 8 * pieceW, 3 * pieceH, 0.90f)
        assertNull(pick(listOf(wide)))
    }

    @Test
    fun `没有棋子尺寸可参照时退化成只看形状`() {
        // 残局只剩两三颗子时 pieceW/H 会是 0，此时不该把所有框都拒掉
        val box = det(90f, 780f, 1254f, 1363f, 0.42f)
        assertEquals(box, BoardPick.best(listOf(box), board, 0f, 0f, 0.6f, 1.6f))
    }

    @Test
    fun `多个合格框时取分数最高的`() {
        val a = det(90f, 780f, 1254f, 1363f, 0.31f)
        val b = det(95f, 785f, 1250f, 1360f, 0.88f)
        assertEquals(b, pick(listOf(a, b)))
    }

    @Test
    fun `尺寸不合格且没有可参照棋子时也不乱给`() {
        // 有棋子参照但一个都不合格 -> 必须 null（宁可让上层继续兜底/报失败）
        val lying = det(50f, 900f, 1327f, 897f, 0.55f)
        assertNull(BoardPick.best(listOf(lying), board, pieceW, pieceH, 0.6f, 1.6f))
    }

    @Test
    fun `退化路径选出的框仍然要过形状判据`() {
        val tall = det(90f, 700f, 300f, 1800f, 0.90f)   // 宽高比 0.17
        assertNull(BoardPick.best(listOf(tall), board, 0f, 0f, 0.6f, 1.6f))
    }

    @Test
    fun `空输入返回 null`() {
        assertNull(pick(emptyList()))
    }

    @Test
    fun `零尺寸框不会除以零`() {
        val zero = det(100f, 100f, 0f, 0f, 0.99f)
        assertNull(pick(listOf(zero)))
    }

    @Test
    fun `真机那张被救回的框归一化前会被拒、归一化后能过`() {
        // 1a0ff692 实测兜底框 1441x1630、子尺寸 146x145 -> 9.88x11.21 倍子宽/子高。
        // 这是兜底模型标的「整块棋盘含木纹边框」，比格线框大 19%，直接拿尺寸判据去量必然被拒。
        val raw = det(0f, 700f, 1441f, 1630f, 0.794f)
        assertNull("归一化之前就该被拒——这是 sizePlausible 窗口按格线框标定的证据", pick(listOf(raw)))

        val grid = raw.copy(box = BoardBoxSynth.toGridBox(raw.box, pieceW, pieceH))
        assertEquals(
            "归一化后必须回到 8x9 倍的格线框",
            8 * pieceW,
            grid.box.width,
            1f
        )
        assertEquals(9 * pieceH, grid.box.height, 1f)
        assertEquals("归一化只绕中心缩放，中心不能动", raw.box.centerX, grid.box.centerX, 0.01f)
        assertEquals(raw.box.centerY, grid.box.centerY, 0.01f)
        assertEquals(grid, pick(listOf(grid)))
    }

    @Test
    fun `已经贴合格线的框归一化时保持原样`() {
        val tight = det(90f, 780f, 8 * pieceW, 9 * pieceH, 0.79f)
        val grid = BoardBoxSynth.toGridBox(tight.box, pieceW, pieceH)
        assertEquals(tight.box, grid)
    }
}
