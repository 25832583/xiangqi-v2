package com.xiangqi.v2.vision

import android.graphics.Bitmap
import com.xiangqi.v2.config.RecognitionConfig
import com.xiangqi.v2.core.Board
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 这一帧的棋盘框**从哪来**。
 *
 * 三种来源都产出等价的框，下游（裁框 / 尺寸过滤 / 格距范围 / 锚定）一行都不用改；
 * 但排查时必须能区分 —— 出现 [LOCATOR] 说明主模型在这套皮肤上已经开始失手，
 * 出现 [SYNTH] 说明连兜底模型也没给出来，只剩棋子包络反推。
 */
enum class BoardSource {
    /** 主检测器检出，且通过形状 + 尺寸校验 */
    MODEL,
    /** 主模型给不出合格框，由兜底定位模型给出 */
    LOCATOR,
    /** 两个模型都没给出来，用棋子中心包络反推 */
    SYNTH
}

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
        override val elapsedMs: Long,
        /**
         * 这一帧的棋盘框**从哪来**。日志里要打出来：频繁出现 [BoardSource.LOCATOR]
         * 说明这套皮肤已经退化到主模型给不出框，该考虑补训练样本而不是继续加几何兜底。
         */
        val boardSource: BoardSource = BoardSource.MODEL
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
    private val config: RecognitionConfig = RecognitionConfig(),
    /**
     * 棋盘框的第二意见。为 null（或它自己不可用）时退回纯几何兜底，
     * 行为与加这个参数之前完全一致。
     */
    private val locator: BoardLocator? = null
) {

    fun load() = detector.load()

    fun close() = detector.close()

    suspend fun recognize(frame: Bitmap): RecognitionOutcome = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        val output = detector.detect(frame)
        val (pieceW, pieceH) = BoardBoxSynth.medianSize(output.pieces)

        // 棋盘框分两道：形状（宽高比）+ 尺寸（框宽该是 8 颗子宽、框高该是 9 颗子高）。
        // 只放宽门槛不做尺寸校验不行 —— JJ象棋那张失败图上模型给的「棋盘框」是
        // 967x1104 的残缺框（真实约 1250x1360），置信 0.214，比真框还高一点点。
        val boxes = output.boards.filter { aspectOk(it.box) }
        val plausible = boxes.filter { BoardBoxSynth.sizePlausible(it.box, pieceW, pieceH) }
        val picked = plausible.maxByOrNull { it.score }?.box
        // 主模型给不出合格框时，问第二意见（懒兜底：正常帧根本不会走到这里）。
        // 它的框同样要过尺寸判据 —— 放进来的不是"信任"，是"候选"。
        val located = if (picked == null) locator?.locate(frame, pieceW, pieceH) else null
        // 两个模型都没有，最后用棋子中心包络反推（首末列/行有子时它**就是**棋盘框）
        val boardBox = picked
            ?: located
            ?: BoardBoxSynth.synth(output.pieces)
            ?: return@withContext RecognitionOutcome.NoBoard(output.boards.size, elapsed())
        val source = when {
            picked != null -> BoardSource.MODEL
            located != null -> BoardSource.LOCATOR
            else -> BoardSource.SYNTH
        }

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
        RecognitionOutcome.Success(assembly, fitX, fitY, pieces.size, anchor, report, elapsed(), source)
    }

    private fun aspectOk(box: Box): Boolean {
        if (box.width <= 0f || box.height <= 0f) return false
        val aspect = box.width / box.height
        return aspect in config.boardAspectMin..config.boardAspectMax
    }
}
