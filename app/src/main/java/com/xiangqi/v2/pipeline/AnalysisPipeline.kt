package com.xiangqi.v2.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.xiangqi.v2.config.EngineConfig
import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.BoardSanity
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.engine.AnalysisEngine
import com.xiangqi.v2.engine.EngineAnalysis
import com.xiangqi.v2.engine.EngineException
import com.xiangqi.v2.engine.EngineLocator
import com.xiangqi.v2.engine.UciEngineFactory
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.infra.SectionTimer
import com.xiangqi.v2.vision.BoardRecognizer
import com.xiangqi.v2.vision.RecognitionOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次分析的结论。用密封类而不是 null/异常，是为了让调用方能对每种情况给出不同提示。 */
sealed interface AnalysisOutcome {

    data class Success(
        val fen: String,
        val board: Board,
        val sideToMove: Side,
        val analysis: EngineAnalysis,
        val elapsedMs: Long
    ) : AnalysisOutcome

    /** 盘面本身不可信，交给引擎没意义。 */
    data class InvalidBoard(val reason: String) : AnalysisOutcome

    data class Timeout(val reason: String) : AnalysisOutcome

    data class Failed(val reason: String) : AnalysisOutcome

    /** 供 UI 直接显示的提示文案；成功时为空串（由调用方拼走法文字）。 */
    val displayMessage: String
        get() = when (this) {
            is Success -> ""
            is InvalidBoard -> reason
            is Timeout -> reason
            is Failed -> reason
        }
}

/**
 * 「帧 → 识别 → 合法性 → FEN → 引擎 → 可用结论」的唯一编排点。
 *
 * 它只依赖三个抽象：[BoardRecognizer]、[EngineLocator]、[EngineConfig] 提供器，
 * 每一环都能换实现而不改本类。识别层不做任何"修盘面"，引擎层也不猜 ——
 * 中间那道 [BoardSanity] 是唯一会拦下盘面的地方，而且只拦「不可能存在」的局面。
 */
class AnalysisPipeline(
    private val recognizer: BoardRecognizer,
    private val locator: EngineLocator,
    private val config: () -> EngineConfig,
    private val tag: String = LogTags.PANEL,
    /** 引擎因权重不可用而降级时回调，见 [WeightIncompatibleException]。 */
    private val onEngineDegraded: (String) -> Unit = {}
) {

    @Volatile
    private var engine: AnalysisEngine? = null

    /** 当前缓存的引擎是按哪一版参数起的（见 `SettingsStore.engineRevision`）。 */
    @Volatile
    private var engineRevision: Int = -1

    private val engineLock = Any()

    private val recognizeMutex = Mutex()

    /**
     * 预热模型：避免第一次识别时同时解压模型 + 建会话，把第一帧卡住好几秒。
     *
     * 引擎**不预热**：解压 53MB 权重 + 起进程要更久，而用户可能只是打开悬浮窗看一眼；
     * 等真正识别出盘面再起，代价被"识别"这一步盖住了。
     */
    fun loadModels() = runCatching { recognizer.load() }
        // 这里以前是裸的 runCatching：预热失败被彻底吞掉，用户只会在真正识别时
        // 收到一句「识别失败」，日志里连一条线索都没有。至少留一行。
        .onFailure { Log.w(tag, "模型预热失败（首次识别会重试）", it) }

    /**
     * 识别一帧。
     *
     * 串行化：盯盘循环与"手动重新识别"会同时要一帧结果，两个 ONNX 推理并行只会
     * 互相抢线程、把两次都拖慢，而且浪费电。排队比并发快。
     */
    suspend fun recognize(frame: Bitmap): RecognitionOutcome =
        recognizeMutex.withLock {
            withContext(Dispatchers.Default) { recognizer.recognize(frame) }
        }

    /**
     * 分析局面。
     *
     * @param revision 引擎参数版本号。和缓存引擎对不上就重建 —— 用户刚改过线程数时，
     *        旧进程里的 Threads 不会跟着变（UCI 的 Threads 只在握手时生效）。
     */
    suspend fun analyze(board: Board, sideToMove: Side, revision: Int): AnalysisOutcome {
        val cfg = config()
        val fen = Fen.encode(board, sideToMove)

        val problem = BoardSanity.check(board)
        if (problem != null) {
            Log.w(tag, "识别结果异常，跳过分析 FEN=$fen（$problem）")
            // 提示要给**能解决问题的下一步**：悬浮窗若正压在棋盘上，重新识别多少次都是同一结果
            return AnalysisOutcome.InvalidBoard("识别异常：$problem")
        }

        // 「取引擎」与「搜索」必须分开量：前者只要发生一次引擎重建就是**秒级**
        // （起进程 + 加载 53MB NNUE），而后者是 moveTimeMs 的设计值，两者差一个数量级。
        // 混成一个总数，就永远分不清"慢"是卡在哪一段 —— 修法完全不同。
        val reused = engine?.let { it.ready && engineRevision == revision } == true
        val started = System.currentTimeMillis()
        val timer = SectionTimer()
        val analysis = try {
            withTimeout(cfg.analysisTimeoutMs) {
                val active = obtainEngine(cfg, revision)
                timer.mark(if (reused) "取引擎(复用)" else "取引擎(新建)")
                active.analyze(fen, cfg.defaultDepth, cfg.moveTimeMs)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(tag, "分析超时 FEN=$fen")
            return AnalysisOutcome.Timeout("分析超时（局面较复杂），可调低深度或思考时间")
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            // 引擎进程已退出/未给出着法 —— UciEngine 已经把它关掉，下一次会重新握手
            Log.w(tag, "引擎异常（已关闭待重建）FEN=$fen：${e.message}")
            return AnalysisOutcome.Failed("引擎异常，已重建，请点重新识别")
        } catch (e: Exception) {
            Log.w(tag, "引擎分析失败 FEN=$fen", e)
            return AnalysisOutcome.Failed("分析失败：${e.message ?: e.javaClass.simpleName}")
        }

        val elapsedMs = System.currentTimeMillis() - started
        Log.i(
            tag,
            "分析 ${timer.mark("搜索").describe()} 合计 ${elapsedMs}ms｜depth ${cfg.defaultDepth} " +
                "movetime ${cfg.moveTimeMs}ms threads ${cfg.threads} → ${analysis.bestMoveUci}"
        )
        return AnalysisOutcome.Success(fen, board, sideToMove, analysis, elapsedMs)
    }

    /**
     * 取引擎。
     *
     * 必须用 [AnalysisEngine.ready] 再过滤一次：引擎进程可能已经死了（被系统回收、
     * 或自己 `CRITICAL ERROR` 退出），而缓存的对象自己不会知道。不复用死引擎，
     * 下一次分析就会重新走一遍 [UciEngineFactory.create]，用户不必重启 App。
     */
    private suspend fun obtainEngine(cfg: EngineConfig, revision: Int): AnalysisEngine =
        withContext(Dispatchers.IO) {
            engine?.takeIf { it.ready && engineRevision == revision } ?: synchronized(engineLock) {
                engine?.takeIf { it.ready && engineRevision == revision } ?: run {
                    runCatching { engine?.close() }
                    engine = null
                    val created = UciEngineFactory(locator, cfg, tag, onEngineDegraded).create()
                    engineRevision = revision
                    engine = created
                    created
                }
            }
        }

    /** 主动释放引擎进程（用户改线程数、或完全退出时）。模型会话保留。 */
    fun releaseEngine() {
        synchronized(engineLock) {
            runCatching { engine?.close() }
            engine = null
            engineRevision = -1
        }
    }

    /**
     * 打断正在进行的搜索。
     *
     * 盘面已经变了，旧局面的结论不再有参考价值，而一次搜索是秒级（默认 2500ms）。
     * 不打断的代价是：用户走一步棋之后，得先等**上一手**的搜索跑完才有反应。
     *
     * 拿到的是[浅结果]，调用方必须丢弃 —— 它不是对当前局面的分析。
     */
    fun interruptCurrent() {
        runCatching { engine?.requestStop() }
    }

    fun close() {
        releaseEngine()
        runCatching { recognizer.close() }
    }
}
