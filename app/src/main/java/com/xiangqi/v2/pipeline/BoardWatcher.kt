package com.xiangqi.v2.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.xiangqi.v2.capture.FrameSource
import com.xiangqi.v2.config.SettingsStore
import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.core.rule.StepResolver
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
    val analyzeMs: Long = 0,
    /**
     * 置信度偏低的格子（`row * 9 + col`），界面用小虚线圈出来。
     *
     * 存在的原因是「错得很自信」真的会发生：把「相」字形的下半遮掉 40%，
     * 模型会翻成「红炮」，置信度从 0.93 掉到 0.49 —— 用户没有任何办法察觉。
     * 既然不能保证不错，至少要让它**看得见**。
     */
    val uncertain: Set<Int> = emptySet()
)

/**
 * 盯盘循环：抓帧 → 识别 → 稳定帧门槛 → 一步可达校验 → 排队分析。
 *
 * 四条设计约束，都是被真实问题逼出来的：
 *
 * 1. **稳定帧门槛**（连续两帧识别结果完全相同才采纳）。走子动画的中间态里，棋子停在
 *    半路上——那个位置**不是**任何合法点位，但识别出来的盘面"错得很合理"，能一路走
 *    到引擎，用户看到的是一步莫名其妙的着法。等一帧就够，代价 700ms。
 *    画面逐帧都在抖时这道门槛永远凑不满，所以它带一条超时兜底（见 [StableFrameGate]）。
 * 2. **盘面没变就不重算**。引擎一次搜索是秒级，而画面在两步棋之间几秒到几十秒不变，
 *    不做这个判断会让 CPU 与电量全烧在重复计算同一个局面上。
 * 3. **同一局面只报一次失败**。识别出的盘面不合法时若每轮都重试，用户会看到提示
 *    不停闪动，而结果其实不会变（除非画面变了）。
 * 4. **最新盘面优先**。循环不 await 搜索：新盘面一到达就打断上一轮搜索、丢掉旧结论。
 *    否则对手走完棋，用户得先把上一手的搜索等完才有反应（默认 2500ms）。
 */
class BoardWatcher(
    private val source: FrameSource,
    private val pipeline: AnalysisPipeline,
    private val settings: SettingsStore,
    /**
     * 抓帧前把悬浮窗藏起来 —— **不传就会把自己的控件拍进每一帧**。
     *
     * 这不是可选项。无障碍截图拍的是系统合成结果，悬浮球/面板都在里面：
     * 控件压在棋子上，那颗子就只剩半个字形。实测「相」的下半被遮掉 40%，
     * 模型会翻成「红炮」（置信度 0.93 → 0.49），看起来像识别模型坏了，
     * 实际是我们的窗口挡住了它。
     */
    private val captureShield: CaptureShield? = null,
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

    /** 已采纳盘面里置信度偏低的格子，随盘面一起交给界面。 */
    private var acceptedUncertain: Set<Int> = emptySet()

    /** 稳定帧门槛（连带抖动兜底）。只由盯盘循环一个线程调用。 */
    private val stableGate = StableFrameGate(REQUIRED_STABLE_HITS, STALL_FORCE_MS)

    // ---- 分析队列：最新盘面优先 ----
    //
    // 一次搜索是秒级（默认 2500ms）。循环里直接 await 它，等于把「采帧」这件事也停掉了：
    // 对手走完棋，得先把**上一手**的搜索等完才开始识别新盘面 —— 这是用户能直接感觉到
    // 的延迟，而且完全没必要，旧局面的结论在盘面变了之后一文不值。
    //
    // 所以拆成「循环只管采帧识别」+「一条串行队列只管算」，队列永远只认最新的盘面：
    // 新盘面进来时先让引擎 stop（见 AnalysisPipeline.interruptCurrent），旧结论直接丢弃。
    // 不并行开两个搜索 —— UCI 是同一个管道上的行协议，并发搜索会把输出搅在一起。

    private val queueLock = Any()

    /** 等着算的盘面；null 表示队列空。 */
    private var queuedBoard: Board? = null
    private var queuedSide: Side = Side.RED
    private var queuedRecognizeMs: Long = 0
    private var queuedUncertain: Set<Int> = emptySet()

    /** 队列消费者是否在跑。与 [queuedBoard] 一起在同一把锁里改，避免"排了没人处理"。 */
    private var queueRunning = false

    /**
     * 正在搜索的那一局的指纹（**盘面 + 走子方**）。
     *
     * 存在的理由：循环每 700ms 一轮，盘面没变时会反复请求分析同一个局面。没有它，
     * 每次请求都会「中断当前搜索 → 重开」，一次都算不完。
     *
     * 必须带上走子方：同一盘面「轮红」「轮黑」是**两个完全不同的问题**，
     * 只按盘面判重会让「切轮次」这条路被自己的去重逻辑吃掉 —— 点了没反应。
     */
    private var analyzingKey: String? = null

    private fun analysisKey(board: Board, side: Side): String =
        Fen.boardPart(board) + " " + side.fenLetter

    /**
     * 连续「与上一步对不上」的次数。到 [MAX_REJECT_STREAK] 就强制放行。
     *
     * 没有这条兜底，一次持续误判会让局面**永远不更新** —— 换一局棋、把手机转 180°、
     * 或者识别本身坏掉，全都会走到「差异太大、无法用一步棋解释」这条分支。
     * 那比偶尔采信一帧坏数据更糟：用户完全不知道发生了什么，只会觉得"它卡住了"。
     */
    private var rejectStreak: Int = 0

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
        // 循环停了但引擎可能还在算上一步：让它尽快收尾，否则关掉悬浮窗之后
        // CPU 还会被一个没人要的结果占用一两秒
        pipeline.interruptCurrent()
        synchronized(queueLock) {
            queuedBoard = null
            analyzingKey = null
            queueRunning = false
        }
        stableGate.reset()
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

    /**
     * 点一下在「自动 / 轮红 / 轮黑」之间循环。
     *
     * 不再是 suspend：盘面分析的等待从调用方移进了队列（见 [requestAnalysis]），
     * 点了立刻回，界面不必等一次搜索。
     */
    fun cycleSideMode(): SideMode {
        sideMode = sideMode.next()
        sideMode.sideOrNull()?.let { currentSide = it }
        val board = acceptedBoard
        if (board != null) {
            // 同一个盘面、不同走子方，最佳着法完全不同，必须重算
            failedKey = null
            requestAnalysis(board, currentSide, uncertain = acceptedUncertain)
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
        stableGate.reset()
        rejectStreak = 0
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
                        requestAnalysis(board, currentSide, recognizeMs, acceptedUncertain)
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

                // 稳定帧门槛，连带「画面一直在抖」的兜底。
                // 手动重新识别（force）不走这道：用户点它时等的就是一次新的判断。
                var bypassStepCheck = force
                if (!force) {
                    when (val decision = stableGate.offer(key, System.currentTimeMillis())) {
                        is StableFrameGate.Decision.Waiting -> {
                            emit(
                                WatchSnapshot(
                                    WatchPhase.WAITING_STABLE,
                                    "画面变化中…（${decision.hits}/${decision.required}）",
                                    fen = Fen.encode(board, currentSide),
                                    board = board,
                                    side = currentSide,
                                    recognizeMs = recognizeMs
                                )
                            )
                            return
                        }

                        is StableFrameGate.Decision.Forced -> {
                            // 逐帧都在抖（走子动画、吃子粒子、倒计时数字）时指纹每帧都变，
                            // 永远凑不出两帧相同。卡在这里等于局面永不更新、界面又不说原因。
                            // 强制采纳，并且**跳过**下面的时序校验 —— 帧本来就不一样，
                            // 用过一次就会被判成"与上一步对不上"，等于白采。
                            bypassStepCheck = true
                            Log.w(
                                tag,
                                "画面已持续抖动 ${decision.waitedMs}ms 仍凑不满 $REQUIRED_STABLE_HITS 帧相同，" +
                                    "强制采纳本帧（防局面卡死）"
                            )
                        }

                        is StableFrameGate.Decision.Stable -> Unit
                    }
                }

                // 稳定门槛过了，但这只说明「连续两帧长得一样」，不说明这个盘面是对的。
                // 一直把某个仕认成马时，两帧当然完全一样。所以再过一道：
                // **这一帧能不能由上一帧走一步棋得到？**
                // 认错种类（残留差异里出现「两格都有子但种类不同」）、凭空多一个子、
                // 凭空少一个子 —— 都找不到任何走法能解释。
                val previous = acceptedBoard
                var movedSide: Side? = null
                if (!bypassStepCheck && previous != null) {
                    when (val verdict = StepResolver.explain(previous, board, MAX_STRAY_DIFF)) {
                        is StepResolver.Verdict.Explained -> {
                            rejectStreak = 0
                            // 走出这一步的一方确定无疑 ⇒ 现在轮到它的对手
                            movedSide = verdict.side.opponent
                            Log.i(
                                tag,
                                "与上一步吻合：${verdict.side.displayName}" +
                                    "${verdict.piece.displayName(verdict.side)} ${verdict.move.toUci()}" +
                                    (if (verdict.strayDiff > 0) "（另有 ${verdict.strayDiff} 格在容差内）" else "") +
                                    (if (verdict.alternatives > 1) "（${verdict.alternatives} 种走法都吻合）" else "")
                            )
                        }

                        // 调用方已判 key 不同，正常到不了这里；真到了也不拦（宁可放行，不要卡住）。
                        StepResolver.Verdict.NoChange -> Unit

                        is StepResolver.Verdict.Unexplained -> {
                            rejectStreak++
                            if (rejectStreak < MAX_REJECT_STREAK) {
                                // 不推状态：这是内部判据，闪一下只会让人以为识别坏了。
                                // 已采纳的盘面与文案原样不动，用户看到的仍是上一次结论。
                                Log.w(
                                    tag,
                                    "与上一步对不上（差 ${verdict.diffCells} 格，" +
                                        "最接近的走法还差 ${verdict.closestStrayDiff} 格），" +
                                        "先不采纳 $rejectStreak/$MAX_REJECT_STREAK"
                                )
                                return
                            }
                            // 到顶必须放行 —— 换一局棋、把手机转 180° 都会走到这条分支，
                            // 一直卡着比偶尔采信一帧坏数据更糟（用户不知道发生了什么）。
                            Log.w(tag, "连续 $rejectStreak 帧与上一步对不上，强制采纳（防局面卡死）")
                        }
                    }
                }

                // 采纳本帧
                currentSide = sideMode.sideOrNull()
                    ?: movedSide
                    ?: SideInference.infer(previous, board, currentSide)
                acceptedBoard = board
                acceptedKey = key
                acceptedUncertain = outcome.report.uncertain
                failedKey = null
                rejectStreak = 0
                // 采纳的那一刻才打印识别内容：界面从此显示的就是这一盘，
                // 用户以后报「某颗子认成别的子」，一行日志就能对上号
                outcome.report.lines.firstOrNull()?.let { Log.i(LogTags.RECOGNIZER, it) }
                outcome.report.lines.drop(1).forEach { Log.w(LogTags.RECOGNIZER, it) }
                requestAnalysis(board, currentSide, recognizeMs, acceptedUncertain)
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
        if (captureShield != null) captureShield.withHidden { source.grab(GRAB_TIMEOUT_MS) }
        else source.grab(GRAB_TIMEOUT_MS)

    /**
     * 请队列分析 [board]，**立即返回**。
     *
     * 三条语义，都是为了避免「为了一个新结果把旧结果也等完」：
     *  - 同一盘面正在算 → 什么都不做（否则循环每 700ms 一轮会把搜索反复重启）；
     *  - 排队的已经是这个盘面 → 什么都不做；
     *  - 有别的盘面正在算 / 已排队 → 覆盖它，并让引擎 stop 掉当前搜索（旧结论作废）。
     */
    private fun requestAnalysis(
        board: Board,
        side: Side,
        recognizeMs: Long = 0,
        uncertain: Set<Int> = emptySet()
    ) {
        val key = analysisKey(board, side)
        val superseding = synchronized(queueLock) {
            if (analyzingKey == key) return
            if (queuedBoard != null && analysisKey(queuedBoard!!, queuedSide) == key) return
            val inFlight = analyzingKey != null
            queuedBoard = board
            queuedSide = side
            queuedRecognizeMs = recognizeMs
            queuedUncertain = uncertain
            inFlight
        }
        if (superseding) {
            Log.i(tag, "盘面已更新，打断上一轮搜索（旧结论作废）")
            pipeline.interruptCurrent()
        }
        val launch = synchronized(queueLock) {
            if (queueRunning) false else {
                queueRunning = true
                true
            }
        }
        if (!launch) return
        val owner = scope
        if (owner == null) {
            // 没启动过（或被 stop 了）：不能留一个"排着但没人处理"的盘面
            synchronized(queueLock) { queueRunning = false }
            Log.w(tag, "盯盘未启动，这一次分析被丢弃")
            return
        }
        owner.launch { drainQueue() }
    }

    /**
     * 队列消费者：串行地算，只算最新的。
     *
     * 串行是硬要求 —— UCI 是**同一个管道上的行协议**，两条搜索同时跑会把 `info` 与
     * `bestmove` 搅在一起，读出来的结论可能属于另一个局面。
     */
    private suspend fun drainQueue() {
        while (true) {
            val next = synchronized(queueLock) {
                val board = queuedBoard
                if (board == null) {
                    // 「队列空」与「清运行标志」必须在同一把锁里，否则会漏掉刚排进来的盘面
                    queueRunning = false
                    null
                } else {
                    queuedBoard = null
                    // 取走时就把指纹钉上：这一轮结束前若同一个盘面又被排进来，
                    // requestAnalysis 会看到 analyzingKey 相同而直接略过
                    analyzingKey = analysisKey(board, queuedSide)
                    AnalysisRequest(board, queuedSide, queuedRecognizeMs, queuedUncertain)
                }
            } ?: return

            emit(
                WatchSnapshot(
                    WatchPhase.ANALYZING,
                    "分析中…",
                    fen = Fen.encode(next.board, next.side),
                    board = next.board,
                    side = next.side,
                    recognizeMs = next.recognizeMs,
                    uncertain = next.uncertain
                )
            )

            val outcome = try {
                pipeline.analyze(next.board, next.side, settings.engineRevision)
            } catch (e: CancellationException) {
                synchronized(queueLock) { analyzingKey = null }
                throw e
            } catch (e: Exception) {
                Log.w(tag, "分析过程抛异常", e)
                AnalysisOutcome.Failed("分析失败：${e.message ?: e.javaClass.simpleName}")
            }

            val superseded = synchronized(queueLock) {
                if (queuedBoard != null) {
                    true
                } else {
                    analyzingKey = null
                    false
                }
            }
            if (superseded) {
                Log.i(tag, "上一轮结论已过期（盘面又变了），丢弃")
                continue
            }
            publishAnalysis(next.board, next.side, next.recognizeMs, next.uncertain, outcome)
        }
    }

    /** 把结论推给界面。失败类结论按「同一局面只报一次」记进 [failedKey]。 */
    private fun publishAnalysis(
        board: Board,
        side: Side,
        recognizeMs: Long,
        uncertain: Set<Int>,
        outcome: AnalysisOutcome
    ) {
        when (outcome) {
            is AnalysisOutcome.Success -> emit(
                WatchSnapshot(
                    WatchPhase.READY,
                    if (uncertain.isEmpty()) "已识别" else "已识别 ⚠ ${uncertain.size} 格不确定",
                    fen = outcome.fen,
                    board = board,
                    side = side,
                    analysis = outcome.analysis,
                    recognizeMs = recognizeMs,
                    analyzeMs = outcome.elapsedMs,
                    uncertain = uncertain
                )
            )

            is AnalysisOutcome.InvalidBoard -> {
                failedKey = Fen.boardPart(board)
                failedReason = outcome.reason
                emit(failureSnapshot(outcome.reason, recognizeMs))
            }

            is AnalysisOutcome.Timeout -> emit(problem(outcome.reason, recognizeMs))
            is AnalysisOutcome.Failed -> emit(problem(outcome.reason, recognizeMs))
        }
    }

    /** 队列里的一条请求。 */
    private data class AnalysisRequest(
        val board: Board,
        val side: Side,
        val recognizeMs: Long,
        val uncertain: Set<Int>
    )

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
        /**
         * 连续几帧识别结果相同才进入下一步判定。2 就够挡住走子动画的中间态。
         *
         * 注意它**只**保证「这两帧长得一样」，不保证「这个盘面是对的」——
         * 稳定地认错时两帧同样一样。所以它后面还有一道 [StepResolver] 的
         * 「一步可达」判据，两者分工不同，缺一不可。
         */
        const val REQUIRED_STABLE_HITS = 2

        /** 允许几格「一边有一边无」的检出抖动。见 [StepResolver.explain]。 */
        const val MAX_STRAY_DIFF = 1

        /**
         * 画面持续抖动多久之后强制采纳当前帧。
         *
         * 门槛要求「两帧相同」，但逐帧都在抖时永远凑不出来，局面会卡在「画面变化中」
         * 而且界面上看不出原因。定 3 秒：正常走子动画不到 1 秒，留一倍余量。
         */
        const val STALL_FORCE_MS = StableFrameGate.DEFAULT_STALL_MS

        /** 连续几帧与上一步对不上之后强制采纳。3 轮 × 700ms ≈ 2 秒。 */
        const val MAX_REJECT_STREAK = 3

        const val GRAB_TIMEOUT_MS = 1500L

        const val PAUSE_POLL_MS = 200L
    }
}
