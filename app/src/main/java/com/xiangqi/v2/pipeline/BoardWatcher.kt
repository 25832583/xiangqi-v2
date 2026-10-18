package com.xiangqi.v2.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.xiangqi.v2.capture.FrameSource
import com.xiangqi.v2.config.SettingsStore
import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.engine.EngineAnalysis
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.vision.RecognitionOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 走子方来源。 */
enum class SideMode(val label: String) {
    /** 靠"哪方刚走过"自动推。 */
    AUTO("自动"),
    RED("轮红"),
    BLACK("轮黑");

    fun next(): SideMode = when (this) {
        AUTO -> RED
        RED -> BLACK
        BLACK -> AUTO
    }

    fun sideOrNull(): Side? = when (this) {
        AUTO -> null
        RED -> Side.RED
        BLACK -> Side.BLACK
    }
}

enum class WatchPhase { IDLE, CAPTURING, WAITING_STABLE, ANALYZING, READY, PROBLEM }

/**
 * 悬浮窗唯一的界面状态源。
 *
 * data class 的 equals 让相同状态的重复推送被自动丢掉 —— 循环每 700ms 跑一次，
 * 但盘面没变、文案也没变时不会引起重绘。
 */
data class WatchSnapshot(
    val phase: WatchPhase,
    val message: String,
    val fen: String? = null,
    val board: Board? = null,
    val side: Side = Side.RED,
    val analysis: EngineAnalysis? = null,
    val recognizeMs: Long = 0,
    val analyzeMs: Long = 0
)

/**
 * 盯盘循环：抓帧 → 识别 → 稳定帧门槛 → 分析。
 *
 * 三条设计约束，都是被真实问题逼出来的：
 *
 * 1. **稳定帧门槛**（连续两帧识别结果完全相同才采纳）。走子动画的中间态里，棋子停在
 *    半路上——那个位置**不是**任何合法点位，但识别出来的盘面"错得很合理"，能一路走
 *    到引擎，用户看到的是一步莫名其妙的着法。等一帧就够，代价 700ms。
 * 2. **盘面没变就不重算**。引擎一次搜索是秒级，而画面在两步棋之间几秒到几十秒不变，
 *    不做这个判断会让 CPU 与电量全烧在重复计算同一个局面上。
 * 3. **同一局面只报一次失败**。识别出的盘面不合法时若每轮都重试，用户会看到提示
 *    不停闪动，而结果其实不会变（除非画面变了）。
 */
class BoardWatcher(
    private val source: FrameSource,
    private val pipeline: AnalysisPipeline,
    private val settings: SettingsStore,
    /** 抓帧前把悬浮窗藏起来（面板/悬浮球被拍进去会变成"棋子"）。 */
    private val shield: CaptureShield? = null,
    private val tag: String = LogTags.PANEL
) {

    private val _state = MutableStateFlow(WatchSnapshot(WatchPhase.IDLE, "待开始"))
    val state: StateFlow<WatchSnapshot> = _state.asStateFlow()

    private var job: Job? = null

    /** 循环所在的作用域。一次性任务（手动识别、改走子方）也挂在这里，随服务一起取消。 */
    private var scope: CoroutineScope? = null

    @Volatile
    private var userPaused: Boolean = false

    @Volatile
    private var uiPaused: Boolean = false

    /** 手动识别进行中，循环让路。 */
    @Volatile
    private var refreshing: Boolean = false

    /** 循环是否停下来：用户暂停 或 面板展开（面板会挡住棋盘）。 */
    val paused: Boolean get() = userPaused || uiPaused

    @Volatile
    var sideMode: SideMode = SideMode.AUTO
        private set

    /** 用户手动指定的走子方最高优先级，没指定时才用推断值。 */
    private var currentSide: Side = Side.RED

    private var acceptedBoard: Board? = null
    private var acceptedKey: String? = null
    private var pendingKey: String? = null
    private var pendingHits: Int = 0

    /** 上一次分析失败的盘面，避免同一局面反复重试、反复提示。 */
    private var failedKey: String? = null
    private var failedReason: String? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        this.scope = scope
        job = scope.launch {
            Log.i(tag, "盯盘开始 间隔=${settings.scanIntervalMs}ms 深度=${settings.depth} 线程=${settings.threads}")
            while (isActive) {
                if (paused || refreshing) {
                    delay(PAUSE_POLL_MS)
                    continue
                }
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "盯盘循环异常", e)
                    emit(WatchSnapshot(WatchPhase.PROBLEM, "内部错误：${e.message ?: e.javaClass.simpleName}"))
                }
                delay(settings.scanIntervalMs.toLong())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope = null
    }

    /** 用户按的「暂停」。 */
    fun togglePause(): Boolean {
        userPaused = !userPaused
        if (userPaused) {
            emit(_state.value.copy(phase = WatchPhase.IDLE, message = "已暂停（点「继续」恢复）"))
        }
        return paused
    }

    /**
     * 界面自己要求的暂停（面板展开时）。
     *
     * 与用户暂停分开记，是因为两者语义不同：面板收起后必须**自动恢复**，
     * 而用户按了暂停就该一直停——共用同一个标志会出现「收起面板把用户的暂停解掉了」。
     */
    fun setUiPaused(value: Boolean) {
        uiPaused = value
    }

    /** 点一下在「自动 / 轮红 / 轮黑」之间循环。suspend：改完要立刻重算一次。 */
    suspend fun cycleSideMode(): SideMode {
        sideMode = sideMode.next()
        sideMode.sideOrNull()?.let { currentSide = it }
        val board = acceptedBoard
        if (board != null) {
            // 同一个盘面、不同走子方，最佳着法完全不同，必须重算
            failedKey = null
            analyze(board)
        } else {
            emit(_state.value.copy(side = currentSide, message = hintForPhase(_state.value.phase, sideMode)))
        }
        return sideMode
    }

    /**
     * 手动「重新识别」。
     *
     * 不复用循环里那套"盘面没变就不算"：用户点这个按钮时往往正因为结果不对，
     * 等的就是一次**新的**判断。所以先清掉已采纳状态，否则新结果与旧结果相同会被直接吞掉。
     */
    suspend fun refreshOnce() {
        acceptedKey = null
        failedKey = null
        pendingHits = 0
        // 让循环先让路：否则用户点的这次识别会和循环里那次同时抓帧、同时送引擎，
        // 白烧一份 CPU，还可能把两次结果按不同顺序推给界面
        refreshing = true
        try {
            tick(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "手动识别失败", e)
            emit(WatchSnapshot(WatchPhase.PROBLEM, "识别失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            refreshing = false
        }
    }

    // ---- 循环主体 ----

    private suspend fun tick(force: Boolean = false) {
        if (_state.value.phase != WatchPhase.READY) {
            emit(WatchSnapshot(WatchPhase.CAPTURING, "正在抓屏…"))
        }
        val frame = grabFrame()
        if (frame == null) {
            emit(WatchSnapshot(WatchPhase.PROBLEM, noFrameMessage()))
            return
        }

        val recognizeStarted = System.currentTimeMillis()
        val outcome = pipeline.recognize(frame)
        val recognizeMs = System.currentTimeMillis() - recognizeStarted

        when (outcome) {
            is RecognitionOutcome.Success -> {
                val board = outcome.assembly.board
                val key = Fen.boardPart(board)

                if (!force && key == failedKey) {
                    emit(failureSnapshot(failedReason ?: "识别结果不可用", recognizeMs))
                    return
                }
                if (!force && key == acceptedKey) {
                    // 盘面没变：不重算，也不重复推状态 —— 每 700ms 推一次会让面板和通知
                    // 一直重绘（通知的 notify 尤其贵）。只有还没有结论时才补一次分析。
                    if (_state.value.analysis == null) {
                        analyze(board, recognizeMs)
                    } else if (_state.value.phase != WatchPhase.READY) {
                        // 上一轮可能是「抓不到画面/找不到棋盘」的临时故障。画面恢复了、
                        // 盘面又和已采纳的一致，就该把结论还回去，而不是让错误提示一直挂着
                        emit(
                            _state.value.copy(
                                phase = WatchPhase.READY,
                                message = "已识别（画面恢复）",
                                recognizeMs = recognizeMs
                            )
                        )
                    }
                    return
                }

                if (!force && key == pendingKey) pendingHits++ else {
                    pendingKey = key
                    pendingHits = 1
                }
                if (!force && pendingHits < REQUIRED_STABLE_HITS) {
                    emit(
                        WatchSnapshot(
                            WatchPhase.WAITING_STABLE,
                            "画面变化中…（$pendingHits/$REQUIRED_STABLE_HITS）",
                            fen = Fen.encode(board, currentSide),
                            board = board,
                            side = currentSide,
                            recognizeMs = recognizeMs
                        )
                    )
                    return
                }

                // 采纳本帧
                currentSide = sideMode.sideOrNull()
                    ?: SideInference.infer(acceptedBoard, board, currentSide)
                acceptedBoard = board
                acceptedKey = key
                failedKey = null
                analyze(board, recognizeMs)
            }

            is RecognitionOutcome.NoBoard -> {
                emit(problem("找不到棋盘 · 请让九宫整盘完整显示", recognizeMs))
            }

            is RecognitionOutcome.TooFewPieces -> {
                emit(problem("只认出 ${outcome.count} 颗子 · 棋盘可能被悬浮窗或弹窗遮挡", recognizeMs))
            }

            is RecognitionOutcome.PoorGeometry -> {
                emit(
                    problem(
                        "棋盘格线对不上（残差 %.2f/%.2f 格）· 请退掉弹窗或换一张棋盘".format(
                            outcome.residualX, outcome.residualY
                        ),
                        recognizeMs
                    )
                )
            }
        }
    }

    private suspend fun grabFrame(): Bitmap? =
        if (shield != null) shield.withHidden { source.grab(GRAB_TIMEOUT_MS) }
        else source.grab(GRAB_TIMEOUT_MS)

    private suspend fun analyze(board: Board, recognizeMs: Long = 0) {
        emit(
            WatchSnapshot(
                WatchPhase.ANALYZING,
                "分析中…",
                fen = Fen.encode(board, currentSide),
                board = board,
                side = currentSide,
                recognizeMs = recognizeMs
            )
        )
        when (val outcome = pipeline.analyze(board, currentSide, settings.engineRevision)) {
            is AnalysisOutcome.Success -> emit(
                WatchSnapshot(
                    WatchPhase.READY,
                    "已识别",
                    fen = outcome.fen,
                    board = board,
                    side = currentSide,
                    analysis = outcome.analysis,
                    recognizeMs = recognizeMs,
                    analyzeMs = outcome.elapsedMs
                )
            )

            is AnalysisOutcome.InvalidBoard -> {
                failedKey = acceptedKey
                failedReason = outcome.reason
                emit(failureSnapshot(outcome.reason, recognizeMs))
            }

            is AnalysisOutcome.Timeout -> emit(problem(outcome.reason, recognizeMs))
            is AnalysisOutcome.Failed -> emit(problem(outcome.reason, recognizeMs))
        }
    }

    // ---- 状态拼装 ----

    private fun problem(message: String, recognizeMs: Long = 0) = WatchSnapshot(
        WatchPhase.PROBLEM,
        message,
        fen = _state.value.fen,
        board = _state.value.board,
        side = currentSide,
        analysis = _state.value.analysis,
        recognizeMs = recognizeMs
    )

    private fun failureSnapshot(reason: String, recognizeMs: Long) = WatchSnapshot(
        WatchPhase.PROBLEM,
        "$reason · 点「重新识别」；若反复出现请把悬浮球拖离棋盘",
        fen = _state.value.fen,
        board = _state.value.board,
        side = currentSide,
        recognizeMs = recognizeMs
    )

    private fun hintForPhase(phase: WatchPhase, mode: SideMode): String = when (phase) {
        WatchPhase.READY -> "已按「${mode.label}」重算"
        WatchPhase.PROBLEM -> _state.value.message
        else -> "走子方：${mode.label}"
    }

    private fun noFrameMessage(): String = when {
        !source.available ->
            "抓不到画面 · 无障碍服务未开启，请到系统设置里打开「象棋助手 v2」"
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ->
            "抓不到画面 · 本机是 Android 11 以下，无障碍截图不可用（该能力需要 Android 11+）"
        else ->
            "抓不到画面 · 截图被系统拒绝，稍后自动重试"
    }

    private fun emit(snapshot: WatchSnapshot) {
        _state.value = snapshot
    }

    /** 抓帧时临时隐藏悬浮窗的能力，由悬浮服务实现。 */
    interface CaptureShield {
        suspend fun <T> withHidden(block: suspend () -> T): T
    }

    private companion object {
        /** 连续几帧识别结果相同才采纳。2 就够挡住走子动画的中间态。 */
        const val REQUIRED_STABLE_HITS = 2

        const val GRAB_TIMEOUT_MS = 1500L

        const val PAUSE_POLL_MS = 200L
    }
}
