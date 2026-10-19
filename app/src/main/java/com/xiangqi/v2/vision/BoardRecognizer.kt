package com.xiangqi.v2.vision

import android.graphics.Bitmap
import com.xiangqi.v2.config.RecognitionConfig
import com.xiangqi.v2.core.Board
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 识别结果。失败路径也带耗时 —— 「慢在哪一段」必须能从日志直接读出来。 */
sealed class RecognitionOutcome {
    abstract val elapsedMs: Long

    data class Success(
        val assembly: Assembly,
        val fitX: AxisFit,
        val fitY: AxisFit,
        val pieceDetections: Int,
        val anchor: AnchorResult,
        /** 见 [RecognitionReport]：日志行 + 需要高亮的低置信格。由调用方在采纳时使用。 */
        val report: RecognitionReport.Report = RecognitionReport.Report.EMPTY,
        override val elapsedMs: Long
    ) : RecognitionOutcome()

    data class NoBoard(val boardCandidates: Int, override val elapsedMs: Long) : RecognitionOutcome()

    /**
     * @param count     剩下几颗
     * @param rejected  其中几颗是「尺寸不像棋子」被滤掉的（整盘误检等），排查时要看
     */
    data class TooFewPieces(
        val count: Int,
        override val elapsedMs: Long,
        val rejected: Int = 0
    ) : RecognitionOutcome()

    data class PoorGeometry(
        val residualX: Float,
        val residualY: Float,
        val pitchX: Float,
        val pitchY: Float,
        override val elapsedMs: Long
    ) : RecognitionOutcome()
}

/**
 * 识别主流程 —— 一次检测 + 两次一维拟合 + 一次吸附。
 *
 * 计时口径只有一段（总耗时），因为链路上只有**一次**神经网络推理。
 * 旧链路要分「找框 / 裁片 / 检子 / 拟合 / 逐格 / 组装」六个段，
 * 正是因为它有 90 次小推理 + 最多两遍整图推理。
 */
class BoardRecognizer(
    private val detector: OnnxDetector,
    private val config: RecognitionConfig = RecognitionConfig()
) {

    fun load() = detector.load()

    fun close() = detector.close()

    suspend fun recognize(frame: Bitmap): RecognitionOutcome = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        val output = detector.detect(frame)
        val boards = output.boards.filter { aspectOk(it.box) }
        val boardBox = boards.maxByOrNull { it.score }?.box
            ?: return@withContext RecognitionOutcome.NoBoard(output.boards.size, elapsed())

        // 先裁到棋盘框：棋盘上方的装饰棋子立牌、下方被吃子托盘都是合法棋子外观，
        // 它们离棋盘中心 3~6 个格距，混进周期拟合会把 pitch/phase 一起带偏。
        val clipped = BoardBoxAnchor.clipToBoard(output.pieces, boardBox)
        // 再挡「整块棋盘被当成一颗棋子」——JJ象棋比赛皮肤实测会出这个（7.4x8.2 格、
        // 置信 0.32、中心正好在棋盘中心），见 [PieceSizeFilter]。
        val pieces = PieceSizeFilter.keep(clipped, boardBox)
        if (pieces.size < config.minPieces) {
            return@withContext RecognitionOutcome.TooFewPieces(
                pieces.size, elapsed(), clipped.size - pieces.size
            )
        }

        val xs = FloatArray(pieces.size) { pieces[it].box.centerX }
        val ys = FloatArray(pieces.size) { pieces[it].box.centerY }
        val fitX = AxisFitter.fit(
            xs,
            boardBox.width / Board.COLS * config.pitchLoFactorX,
            boardBox.width / Board.COLS * config.pitchHiFactorX,
            config.pitchStep
        )
        val fitY = AxisFitter.fit(
            ys,
            boardBox.height / Board.ROWS * config.pitchLoFactorY,
            boardBox.height / Board.ROWS * config.pitchHiFactorY,
            config.pitchStep
        )
        if (fitX == null || fitY == null) {
            return@withContext RecognitionOutcome.PoorGeometry(1f, 1f, 0f, 0f, elapsed())
        }
        if (fitX.residual > config.maxFitResidual || fitY.residual > config.maxFitResidual) {
            return@withContext RecognitionOutcome.PoorGeometry(
                fitX.residual, fitY.residual, fitX.pitch, fitY.pitch, elapsed()
            )
        }

        val candidates = GridSnap.snap(pieces, fitX, fitY, config.maxSnapResidual)
        if (candidates.isEmpty()) {
            return@withContext RecognitionOutcome.PoorGeometry(
                fitX.residual, fitY.residual, fitX.pitch, fitY.pitch, elapsed()
            )
        }

        // 主锚定：棋盘框中心恒等于格坐标 (列 4, 行 4.5)，整数平移可直接解。
        // 规则锚定只在「框中心假设被破坏」时兜底 —— 残局里棋规约束大面积失效，
        // 用它当主锚定会让整盘错位 1~2 格（49 张残局实测 38/49 vs 49/49）。
        val geometric = BoardBoxAnchor.anchor(fitX, fitY, boardBox)
        val anchor = if (geometric.score <= BoardBoxAnchor.MAX_DEVIATION) {
            geometric
        } else {
            RulesAnchor.anchor(candidates, fitX, fitY, boardBox) ?: geometric
        }

        val assembly = BoardAssembler.assemble(candidates, anchor.colOffset, anchor.rowOffset)
        // 报告在这里算好、由调用方在「采纳新盘面」时打印 —— 识别层不知道这一帧会不会
        // 被上层丢掉，每帧都打印会把 logcat 刷满（700ms 一条）
        val report = RecognitionReport.build(
            assembly = assembly,
            candidates = candidates,
            fitX = fitX,
            fitY = fitY,
            frameWidth = frame.width,
            frameHeight = frame.height,
            colOffset = anchor.colOffset,
            rowOffset = anchor.rowOffset
        )
        RecognitionOutcome.Success(assembly, fitX, fitY, pieces.size, anchor, report, elapsed())
    }

    private fun aspectOk(box: Box): Boolean {
        if (box.width <= 0f || box.height <= 0f) return false
        val aspect = box.width / box.height
        return aspect in config.boardAspectMin..config.boardAspectMax
    }
}
