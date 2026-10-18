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
        override val elapsedMs: Long
    ) : RecognitionOutcome()

    data class NoBoard(val boardCandidates: Int, override val elapsedMs: Long) : RecognitionOutcome()

    data class TooFewPieces(val count: Int, override val elapsedMs: Long) : RecognitionOutcome()

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
        val pieces = BoardBoxAnchor.clipToBoard(output.pieces, boardBox)
        if (pieces.size < config.minPieces) {
            return@withContext RecognitionOutcome.TooFewPieces(pieces.size, elapsed())
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
        RecognitionOutcome.Success(assembly, fitX, fitY, pieces.size, anchor, elapsed())
    }

    private fun aspectOk(box: Box): Boolean {
        if (box.width <= 0f || box.height <= 0f) return false
        val aspect = box.width / box.height
        return aspect in config.boardAspectMin..config.boardAspectMax
    }
}
