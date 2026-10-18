package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Piece
import com.xiangqi.v2.core.PieceType
import com.xiangqi.v2.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OPENING = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

private fun fakeDetection(label: Char, score: Float): Detection = Detection(
    box = Box(0f, 0f, 10f, 10f),
    score = score,
    classIndex = Labels.ALL.indexOf(label)
)

/** 标准开局（或 180° 翻转后）的候选集合，坐标是"画面方向"下的 (row, col)。 */
private fun openingCandidates(redOnTop: Boolean): List<CellCandidate> {
    val board = Board.fromFenBoardPart(OPENING)
    val out = ArrayList<CellCandidate>(32)
    for (r in 0 until Board.ROWS) {
        for (c in 0 until Board.COLS) {
            val piece = board[r, c] ?: continue
            val sr = if (redOnTop) Board.ROWS - 1 - r else r
            val sc = if (redOnTop) Board.COLS - 1 - c else c
            out.add(CellCandidate(sr, sc, fakeDetection(piece.fenChar, 0.9f), 0.02f))
        }
    }
    return out
}

class LetterboxTest {

    @Test
    fun `等比缩放后坐标可以还原`() {
        val lb = Letterbox.of(1080, 2400, 640)
        // 1080x2400 竖屏：高度受限（2400 是长边），宽度方向两侧留边
        assertEquals(640f / 2400f, lb.scale, 1e-4f)
        assertEquals((640f - 1080f * lb.scale) / 2f, lb.padX, 1e-3f)
        assertEquals(0f, lb.padY, 1e-3f)

        val back = lb.toFrameX(lb.padX + 100f * lb.scale)
        assertEquals(100f, back, 1e-3f)
    }

    @Test
    fun `横屏时上下留边`() {
        val lb = Letterbox.of(2400, 1080, 640)
        assertEquals(0f, lb.padX, 1e-3f)
        assertTrue(lb.padY > 0f)
    }
}

class YoloDecoderTest {

    private fun v5Output(): FloatArray {
        val data = FloatArray(2 * (5 + Labels.COUNT))
        // anchor 0：中心 (100,200)，40x40，obj 0.9，第 10 类 0.8
        data[0] = 100f; data[1] = 200f; data[2] = 40f; data[3] = 40f
        data[4] = 0.9f
        data[5 + 10] = 0.8f
        return data
    }

    private fun v8Output(): FloatArray {
        val anchors = 2
        val width = 4 + Labels.COUNT
        val data = FloatArray(width * anchors)
        data[0 * anchors + 0] = 100f
        data[1 * anchors + 0] = 200f
        data[2 * anchors + 0] = 40f
        data[3 * anchors + 0] = 40f
        data[(4 + 10) * anchors + 0] = 0.8f
        return data
    }

    @Test
    fun `v5 与 v8 两种布局解出同一个框`() {
        val v5 = YoloDecoder.decode(v5Output(), intArrayOf(1, 2, 5 + Labels.COUNT), Labels.COUNT, 0.25f)
        val v8 = YoloDecoder.decode(v8Output(), intArrayOf(1, 4 + Labels.COUNT, 2), Labels.COUNT, 0.25f)

        assertEquals(1, v5.size)
        assertEquals(1, v8.size)
        assertEquals(10, v5[0].classIndex)
        assertEquals(10, v8[0].classIndex)
        assertEquals(v5[0].box.x0, v8[0].box.x0, 1e-3f)
        assertEquals(v5[0].box.centerY, v8[0].box.centerY, 1e-3f)
        assertEquals(0.9f * 0.8f, v5[0].score, 1e-4f)
        assertEquals(0.8f, v8[0].score, 1e-4f)
    }

    @Test
    fun `形状决定布局`() {
        assertEquals(YoloLayout.V5_ANCHOR_MAJOR, YoloDecoder.layoutOf(intArrayOf(1, 25200, 20), 15))
        assertEquals(YoloLayout.V8_CHANNEL_MAJOR, YoloDecoder.layoutOf(intArrayOf(1, 19, 8400), 15))
        assertEquals(YoloLayout.V8_ANCHOR_MAJOR, YoloDecoder.layoutOf(intArrayOf(1, 8400, 19), 15))
    }
}

class AxisFitterTest {

    @Test
    fun `能从带噪声的格点恢复格距与相位`() {
        val pitch = 153f
        val phase = 57f
        val values = FloatArray(9) { i -> phase + i * pitch + if (i % 2 == 0) 1.2f else -0.8f }
        val fit = AxisFitter.fit(values, pitchLo = 140f, pitchHi = 170f)
        assertNotNull(fit)
        assertEquals(pitch, fit!!.pitch, 0.2f)
        assertEquals(1f, fit.concentration, 0.05f)
        assertTrue("残差应远小于半格，实际 ${fit.residual}", fit.residual < 0.02f)
    }

    @Test
    fun `点太少不给结果`() {
        assertEquals(null, AxisFitter.fit(floatArrayOf(10f, 20f), 5f, 30f))
    }

    /** 这是 [RecognitionConfig.minPieces] 不能低于 3 的数学依据。 */
    @Test
    fun `3 个点可以拟合`() {
        val values = floatArrayOf(10f, 30f, 50f) // pitch=20, phase=10
        val fit = AxisFitter.fit(values, 15f, 25f)
        assertNotNull(fit)
        assertTrue("3 点拟合残差应接近 0，实际 ${fit!!.residual}", fit.residual < 0.01f)
    }

    @Test
    fun `5 个点拟合残差接近 0`() {
        val values = floatArrayOf(10f, 30f, 50f, 70f, 90f) // pitch=20, phase=10
        val fit = AxisFitter.fit(values, 18f, 22f)
        assertNotNull(fit)
        assertTrue("5 点拟合残差应接近 0，实际 ${fit!!.residual}", fit.residual < 0.01f)
    }
}

class GridSnapTest {

    @Test
    fun `框中心落在交点附近时吸到该交点`() {
        val fitX = AxisFit(pitch = 100f, phase = 50f, residual = 0f, concentration = 1f)
        val fitY = AxisFit(pitch = 100f, phase = 50f, residual = 0f, concentration = 1f)
        val det = Detection(Box(140f, 240f, 160f, 260f), 0.9f, Labels.ALL.indexOf('R'))
        val snapped = GridSnap.snap(listOf(det), fitX, fitY)
        assertEquals(1, snapped.size)
        // center = (150, 250) -> fx = 1.0, fy = 2.0
        assertEquals(1, snapped[0].col)
        assertEquals(2, snapped[0].row)
        assertTrue(snapped[0].residual < 1e-4f)
    }

    @Test
    fun `偏离交点太远的框被丢弃`() {
        val fitX = AxisFit(100f, 50f, 0f, 1f)
        val fitY = AxisFit(100f, 50f, 0f, 1f)
        // center = (200, 250) -> fx = 1.5，残差 0.5 > 0.35
        val det = Detection(Box(190f, 240f, 210f, 260f), 0.9f, Labels.ALL.indexOf('R'))
        assertEquals(0, GridSnap.snap(listOf(det), fitX, fitY).size)
    }
}

class BoardAssemblerTest {

    @Test
    fun `红方在下时不需要翻转`() {
        val assembly = BoardAssembler.assemble(openingCandidates(redOnTop = false))
        assertEquals(false, assembly.redOnTop)
        assertEquals(OPENING, assembly.board.toString())
        assertEquals(32, assembly.pieceCount)
        assertTrue(assembly.geometryComplete)
    }

    @Test
    fun `红方在上时翻转成标准方向`() {
        val assembly = BoardAssembler.assemble(openingCandidates(redOnTop = true))
        assertEquals(true, assembly.redOnTop)
        assertEquals(OPENING, assembly.board.toString())
        assertEquals(32, assembly.pieceCount)
    }

    @Test
    fun `同一交点上的两个框保留高分并记录冲突`() {
        val base = openingCandidates(redOnTop = false)
        val contested = listOf(
            CellCandidate(5, 4, fakeDetection('R', 0.30f), 0.01f),
            CellCandidate(5, 4, fakeDetection('N', 0.95f), 0.01f)
        )
        val assembly = BoardAssembler.assemble(base + contested)
        assertEquals(1, assembly.conflicts.size)
        assertEquals('N', assembly.conflicts[0].kept.label)
        assertEquals(1, assembly.conflicts[0].dropped.size)
        // 多出来的是马：红马上限 2 个，开局已有 2 个 —— 超限按分数裁剪时 0.95 会顶掉 0.9
        assertEquals(2, assembly.board.countOf(PieceType.HORSE, Side.RED))
    }

    @Test
    fun `同类超过物理上限时按置信度裁剪`() {
        val base = openingCandidates(redOnTop = false)
        val extra = listOf(
            CellCandidate(5, 0, fakeDetection('R', 0.40f), 0.01f),
            CellCandidate(5, 2, fakeDetection('R', 0.35f), 0.01f),
            CellCandidate(5, 6, fakeDetection('R', 0.30f), 0.01f)
        )
        val assembly = BoardAssembler.assemble(base + extra)
        assertEquals(2, assembly.board.countOf(PieceType.CHARIOT, Side.RED))
        assertEquals(2, assembly.board.countOf(PieceType.CHARIOT, Side.BLACK))
        assertEquals(3, assembly.overLimit.size)
    }

    @Test
    fun `只有半个棋盘时不认为几何完整`() {
        // 新链路不再靠"检出跨度是否等于 9/10"来报警——锚定由 RulesAnchor 解出，与跨度无关。
        // 但半盘仍然必须被判定为 geometryComplete = false。
        val base = openingCandidates(redOnTop = false).filter { it.row <= 4 }
        val assembly = BoardAssembler.assemble(base)
        assertEquals(false, assembly.geometryComplete)
        assertTrue("下半盘无子，rowTo 应小于 ${Board.ROWS - 1}", assembly.rowTo < Board.ROWS - 1)
    }

    @Test
    fun `将帅判方向比开局匹配更稳`() {
        // 中局：红方只剩帅在下方，其余全空
        val board = Board.builder()
            .set(9, 4, Piece(PieceType.KING, Side.RED))
            .set(0, 4, Piece(PieceType.KING, Side.BLACK))
            .build()
        assertEquals(false, BoardAssembler.redSideOnTop(board))
        assertEquals(true, BoardAssembler.redSideOnTop(board.flip180()))
    }

    @Test
    fun `组装层不重复判断吸附残差`() {
        val base = openingCandidates(redOnTop = false)
        // 残差 0.99 的候选：拦截责任在 GridSnap，组装层不该再造第二套判据
        val highScore = CellCandidate(5, 4, fakeDetection('P', 0.99f), 0.99f)
        val assembly = BoardAssembler.assemble(base + highScore)
        assertEquals('P', assembly.board[5, 4]?.fenChar)
        // 红兵上限 5：新兵分数最高，挤掉最低分的旧兵
        assertEquals(5, assembly.board.countOf(PieceType.PAWN, Side.RED))
        assertEquals(1, assembly.overLimit.size)
        assertTrue(assembly.maxResidual > 0.9f)
    }
}

/**
 * 锚定：周期拟合的相位只是「模格距的等价类」，行列号带着未知整数平移。
 * 旧做法（最小行列当 0 位移）在边缘缺子时会整盘错位，这里把它钉死在测试里。
 */
class RulesAnchorTest {

    @Test
    fun `边缘列缺子时仍能解出正确锚定`() {
        // 场景：真实棋盘坐标 (r,c) 被整体平移到 (r+3, c+2)，
        // 且最左一列（c=0 的车马）恰好没检出 —— min 平移会把整盘错开一列。
        val colOff = 2
        val rowOff = 3
        val board = Board.fromFenBoardPart(OPENING)
        val cands = ArrayList<CellCandidate>()
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) {
                if (c == 0) continue               // 整列丢失
                val piece = board[r, c] ?: continue
                cands.add(
                    CellCandidate(r + rowOff, c + colOff,
                        fakeDetection(piece.fenChar, 0.9f), 0.02f)
                )
            }
        }

        // 棋盘框中心应落在格坐标 (colOff+4, rowOff+4.5)
        val pitch = 100f
        val phase = 50f
        val cx = phase + (colOff + 4f) * pitch
        val cy = phase + (rowOff + 4.5f) * pitch
        val box = Box(cx - 4.25f * pitch, cy - 4.65f * pitch,
                      cx + 4.25f * pitch, cy + 4.65f * pitch)
        val fx = AxisFit(pitch, phase, 0.02f, 0.99f)
        val fy = AxisFit(pitch, phase, 0.02f, 0.99f)

        val anchor = RulesAnchor.anchor(cands, fx, fy, box)
        assertNotNull(anchor)
        assertEquals(colOff, anchor!!.colOffset)
        assertEquals(rowOff, anchor.rowOffset)

        // 套用锚定后，盘面应与开局一致（除丢掉的那一列）
        val assembly = BoardAssembler.assemble(cands, anchor.colOffset, anchor.rowOffset)
        assertEquals(0, assembly.overLimit.size)
        for (r in 0 until Board.ROWS) {
            for (c in 1 until Board.COLS) {
                assertEquals(
                    "($r,$c) 应一致",
                    board[r, c]?.fenChar,
                    assembly.screenBoard[r, c]?.fenChar
                )
            }
        }
    }

    @Test
    fun `红方在上时锚定同样正确`() {
        val colOff = 1
        val rowOff = 6
        val board = Board.fromFenBoardPart(OPENING)
        val cands = ArrayList<CellCandidate>()
        // 画面方向翻转：红在上
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) {
                val piece = board[r, c] ?: continue
                val sr = Board.ROWS - 1 - r
                val sc = Board.COLS - 1 - c
                cands.add(
                    CellCandidate(sr + rowOff, sc + colOff,
                        fakeDetection(piece.fenChar, 0.9f), 0.02f)
                )
            }
        }
        val pitch = 100f
        val phase = 50f
        val cx = phase + (colOff + 4f) * pitch
        val cy = phase + (rowOff + 4.5f) * pitch
        val box = Box(cx - 4.25f * pitch, cy - 4.65f * pitch,
                      cx + 4.25f * pitch, cy + 4.65f * pitch)
        val fx = AxisFit(pitch, phase, 0.02f, 0.99f)
        val fy = AxisFit(pitch, phase, 0.02f, 0.99f)

        val anchor = RulesAnchor.anchor(cands, fx, fy, box)
        assertNotNull(anchor)
        assertEquals(colOff, anchor!!.colOffset)
        assertEquals(rowOff, anchor.rowOffset)

        val assembly = BoardAssembler.assemble(cands, anchor.colOffset, anchor.rowOffset)
        assertTrue("红方应在上", assembly.redOnTop)
        assertEquals(OPENING, Fen.boardPart(assembly.board))
    }

    @Test
    fun `棋盘外的误检不影响锚定`() {
        val colOff = 1
        val rowOff = 6
        val board = Board.fromFenBoardPart(OPENING)
        val cands = ArrayList<CellCandidate>()
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) {
                val piece = board[r, c] ?: continue
                cands.add(
                    CellCandidate(r + rowOff, c + colOff,
                        fakeDetection(piece.fenChar, 0.9f), 0.02f)
                )
            }
        }
        // 玩家头像被认成黑炮，落在棋盘上方很远处
        cands.add(CellCandidate(rowOff - 3, colOff + 4, fakeDetection('c', 0.88f), 0.05f))

        val pitch = 100f
        val phase = 50f
        val cx = phase + (colOff + 4f) * pitch
        val cy = phase + (rowOff + 4.5f) * pitch
        val box = Box(cx - 4.25f * pitch, cy - 4.65f * pitch,
                      cx + 4.25f * pitch, cy + 4.65f * pitch)
        val anchor = RulesAnchor.anchor(
            cands, AxisFit(pitch, phase, 0.02f, 0.99f), AxisFit(pitch, phase, 0.02f, 0.99f), box
        )
        assertNotNull(anchor)
        assertEquals(colOff, anchor!!.colOffset)
        assertEquals(rowOff, anchor.rowOffset)

        val assembly = BoardAssembler.assemble(cands, anchor.colOffset, anchor.rowOffset)
        assertEquals(OPENING, Fen.boardPart(assembly.screenBoard))
        assertTrue("越界项应被记录", assembly.notes.any { it.contains("越界") })
    }
}

/**
 * 框中心锚定 —— 针对 49 张 JJ象棋**残局**截图暴露的问题。
 *
 * 残局实测的失效模式：棋盘最左列、最下几行常空着，帅也可能已经离开中线。
 * 规则锚定（[RulesAnchor]）在这类局面上会整体错位 1~2 格，
 * 错位后连将都被挤出棋盘，表现为「黑将缺失」。框中心锚定不依赖子力分布。
 */
class BoardBoxAnchorTest {

    /** 残局：红方只剩帅在 (0,4)、相仕在顶端，黑方只剩将象士在底线，左列全空。 */
    private val ENDGAME = "2BAKA3/9/4C4/9/P2NC3P/9/3R5/6P2/3k5/2ba2b2"

    private fun endgameCandidates(colOff: Int, rowOff: Int): List<CellCandidate> {
        val board = Board.fromFenBoardPart(ENDGAME)
        val out = ArrayList<CellCandidate>()
        for (r in 0 until Board.ROWS) {
            for (c in 0 until Board.COLS) {
                val piece = board[r, c] ?: continue
                out.add(
                    CellCandidate(r + rowOff, c + colOff,
                        fakeDetection(piece.fenChar, 0.9f), 0.03f)
                )
            }
        }
        return out
    }

    /** 框中心恒等于格坐标 (4, 4.5)；构造一个 8 格距宽、9 格距高的框。 */
    private fun boardBox(pitch: Float, phase: Float, colOff: Int, rowOff: Int): Box {
        val cx = phase + (colOff + 4f) * pitch
        val cy = phase + (rowOff + 4.5f) * pitch
        val hw = 4.25f * pitch          // 左右各留 0.25 格的边框
        val hh = 4.65f * pitch
        return Box(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    @Test
    fun `残局整盘错位两行一列时框锚定仍能解对`() {
        val pitch = 137.6f
        val phase = 88.3f
        val colOff = 1
        val rowOff = 6

        val anchor = BoardBoxAnchor.anchor(
            AxisFit(pitch, phase, 0.03f, 0.98f),
            AxisFit(pitch, phase, 0.03f, 0.98f),
            boardBox(pitch, phase, colOff, rowOff)
        )
        assertEquals(colOff, anchor.colOffset)
        assertEquals(rowOff, anchor.rowOffset)
        assertTrue("正常框的几何偏差应≈0", anchor.score < 0.05f)

        val assembly = BoardAssembler.assemble(
            endgameCandidates(colOff, rowOff), anchor.colOffset, anchor.rowOffset
        )
        assertEquals(ENDGAME, Fen.boardPart(assembly.screenBoard))
    }

    @Test
    fun `锚定只由棋盘框决定 与子力分布无关`() {
        val pitch = 137.6f
        val phase = 88.3f
        val box = boardBox(pitch, phase, 1, 6)
        // 把框整体往右挪 1 整格：锚定必须跟着框走一格，而不是被棋子"拉"住
        val shifted = Box(box.x0 + pitch, box.y0, box.x1 + pitch, box.y1)
        val anchor = BoardBoxAnchor.anchor(
            AxisFit(pitch, phase, 0.0f, 1f), AxisFit(pitch, phase, 0.0f, 1f), shifted
        )
        assertEquals(2, anchor.colOffset)
        assertEquals(6, anchor.rowOffset)
        // 整格平移不产生几何偏差；偏差恒在半个格子以内，是 round 的定义使然
        assertTrue(anchor.score <= BoardBoxAnchor.MAX_DEVIATION)
    }

    @Test
    fun `棋盘上方的装饰棋子被裁掉`() {
        val pitch = 137.6f
        val box = boardBox(pitch, 88.3f, 0, 0)
        val onBoard = Detection(Box(box.centerX - 50f, box.centerY - 50f,
                                    box.centerX + 50f, box.centerY + 50f), 0.9f,
                                Labels.ALL.indexOf('K'))
        // 棋盘上方的装饰立牌：离框顶 3 个格距
        val decorY = box.y0 - 3f * pitch
        val decor = Detection(Box(box.centerX - 50f, decorY - 50f,
                                  box.centerX + 50f, decorY + 50f), 0.88f,
                              Labels.ALL.indexOf('R'))
        val kept = BoardBoxAnchor.clipToBoard(listOf(onBoard, decor), box)
        assertEquals(1, kept.size)
        assertEquals('K', kept[0].label)
    }
}
