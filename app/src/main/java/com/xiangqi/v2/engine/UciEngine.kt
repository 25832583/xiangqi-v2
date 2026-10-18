package com.xiangqi.v2.engine

import android.util.Log
import com.xiangqi.v2.config.EngineConfig
import com.xiangqi.v2.core.Move
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.infra.SectionTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader

/**
 * 通用 UCI 引擎封装（皮卡鱼只是其中一个具体引擎，协议是通用的）。
 *
 * 与重构前的区别：
 *  - 线程数 / 哈希 / 深度 / 思考时间 / 各类超时全部来自 [EngineConfig]，不再写死；
 *  - 启动握手也加了超时 —— 原实现用 `readLine()` 死等 `uciok`，引擎要是起不来就永远挂住；
 *  - 阻塞的 IO 逻辑收在私有方法里，对外是 suspend，调用方不必关心线程。
 *
 * UCI 是一个「读写同一个管道的行协议」，所以所有交互必须串行化，这里统一 `@Synchronized`。
 */
class UciEngine(
    private val config: EngineConfig,
    private val enginePath: String,
    private val weightsPath: String?,
    private val tag: String = LogTags.ENGINE
) : AnalysisEngine {

    override val name: String = "uci:${File(enginePath).name}"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** 引擎最近输出的非 info/bestmove 行（报错、提示），故障时随异常一起抛给上层。 */
    private val recentLines = ArrayDeque<String>()

    @Volatile
    private var started = false

    override val ready: Boolean get() = started

    /** 启动引擎并完成 UCI 握手。失败抛 [EngineException]。 */
    @Synchronized
    fun start() {
        if (started) return
        // 引擎启动是整条链路上**最贵的一次操作**：起进程 + 加载 50MB NNUE 权重，
        // 而它本该只在第一次分析时发生一次（[AnalysisPipeline.obtainEngine] 会复用
        // ready 的实例）。分段量出来才能回答「到底是不是每次都在重建」——
        // 若日志里反复出现「引擎启动耗时」，那慢的不是识别，是引擎在反复死掉重来。
        val timer = SectionTimer()
        val executable = File(enginePath)
        if (!executable.exists()) throw EngineException("未找到引擎文件: $enginePath")
        runCatching { executable.setExecutable(true, true) }
        runCatching { executable.setReadable(true, true) }
        if (!executable.canExecute()) throw EngineException("引擎没有执行权限: $enginePath")

        val proc = try {
            ProcessBuilder(enginePath).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw EngineException("引擎进程启动失败: $enginePath", e)
        }
        process = proc
        writer = proc.outputStream.bufferedWriter()
        reader = BufferedReader(InputStreamReader(proc.inputStream))
        timer.mark("起进程")

        try {
            send("uci")
            if (!readUntil("uciok", config.startupTimeoutMs)) {
                throw EngineException("引擎未在 ${config.startupTimeoutMs}ms 内响应 uciok")
            }
            timer.mark("uciok")
            if (!weightsPath.isNullOrEmpty() && File(weightsPath).exists()) {
                send("setoption name EvalFile value $weightsPath")
            } else if (!weightsPath.isNullOrEmpty()) {
                Log.w(tag, "NNUE 权重不存在，引擎将以无权重模式运行：$weightsPath")
            }
            send("setoption name Threads value ${config.threads}")
            send("setoption name Hash value ${config.hashMb}")
            config.extraOptions.forEach { (key, value) ->
                send("setoption name $key value $value")
            }
            send("isready")
            if (!readUntil("readyok", config.startupTimeoutMs)) {
                throw EngineException("引擎未在 ${config.startupTimeoutMs}ms 内响应 readyok")
            }
            // `setoption name EvalFile` 只是登记路径，**真正的权重加载发生在
            // isready → readyok 之间**，所以这一段才是启动耗时的主项。
            timer.mark("加载权重+readyok")
            if (!proc.isAlive) {
                throw EngineException("引擎启动后立即退出，请确认手机为 arm64 架构")
            }
        } catch (e: Exception) {
            Log.w(tag, "引擎启动失败：${timer.describe()}（${e.message}）")
            close()
            throw if (e is EngineException) e else EngineException("引擎初始化失败", e)
        }
        started = true
        // 权重名先算出来：直接写进模板里会变成「模板里嵌 lambda 再嵌引号」，
        // 可读性差且容易被后续编辑改坏
        val weightName = weightsPath?.let { File(it).name } ?: "无"
        Log.i(
            tag,
            "引擎启动耗时：${timer.describe()}｜${File(enginePath).name}" +
                " 权重=$weightName threads=${config.threads} hash=${config.hashMb}MB"
        )
    }

    override suspend fun analyze(fen: String, depth: Int, moveTimeMs: Int): EngineAnalysis =
        withContext(Dispatchers.IO) { analyzeBlocking(fen, depth, moveTimeMs) }

    @Synchronized
    private fun analyzeBlocking(fen: String, depth: Int, moveTimeMs: Int): EngineAnalysis {
        check(started) { "引擎尚未启动" }
        val proc = process
        if (proc == null || !proc.isAlive) {
            throw engineFailure(fen, "引擎进程已退出")
        }
        val input = reader ?: throw engineFailure(fen, "引擎未就绪")
        recentLines.clear()
        send("position fen $fen")
        // movetime 兜底：复杂局面即使没算到指定深度也会按时返回，避免长时间卡住
        send("go depth $depth movetime $moveTimeMs")

        var bestMoveUci = ""
        var scoreCp: Int? = null
        var mateIn: Int? = null
        var pv = emptyList<String>()
        var depthSeen = 0
        val deadline = System.currentTimeMillis() + moveTimeMs + IDLE_GRACE_MS

        loop@ while (true) {
            // 用 ready() 轮询替代 readLine() 阻塞，保证整体可超时
            while (!input.ready()) {
                if (System.currentTimeMillis() > deadline) {
                    // 安全网：引擎异常卡死时停止搜索并丢弃到 bestmove 的残留输出
                    runCatching { send("stop") }
                    drainUntilBestMove()
                    break@loop
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            val line = input.readLine() ?: break
            if (line.startsWith("bestmove")) {
                bestMoveUci = line.trim().split(WHITESPACE).getOrNull(1) ?: ""
                break
            }
            val info = parseUciInfo(line)
            if (info != null && !info.isEmpty) {
                scoreCp = info.scoreCp ?: scoreCp
                mateIn = info.mateIn ?: mateIn
                depthSeen = info.depth ?: depthSeen
                pv = info.pv ?: pv
            } else {
                // 落到这里的包括 `info string …` —— 引擎的提示与**错误就在这一类里**
                // （权重不兼容、参数非法、内部异常）。老实现按行首 `info` 分流，把它们
                // 喂给解析器后丢弃，故障信息于是只剩「无输出」。
                remember(line)
            }
        }
        if (bestMoveUci.isEmpty()) {
            throw engineFailure(fen, if (proc.isAlive) "引擎未给出着法" else "引擎进程已退出")
        }
        return EngineAnalysis(
            bestMove = Move.parseUci(bestMoveUci),
            bestMoveUci = bestMoveUci,
            scoreCp = scoreCp,
            mateIn = mateIn,
            pv = pv,
            depth = depthSeen
        )
    }

    /**
     * 引擎一行 `bestmove` 都没给 —— 这**不是**「无着可走」。
     *
     * 真正的没着可走，引擎会明确打印 `bestmove (none)`；一条都不打印只可能是进程出了问题：
     * 被系统回收、自己报错退出（例如喂了它无法处理的不合法局面）、或卡死后被我们 stop 掉。
     * 老实现只写一条日志然后返回空结论，上层把它渲染成一个「—」，与「没有着法」长得一模一样，
     * 现场就是这么被误导的。
     *
     * 现在统一按故障处理：[close] 会把 [ready] 置回 false，下一次分析会重新握手起进程，
     * 用户不必重启 App；同时把引擎最近的输出一起带出去，日志里能直接看到它说了什么。
     */
    /**
     * 优先给出引擎**自己解释的原因**（`info string …`，错误就埋在这一类里），
     * 没有解释才退回原始输出 —— 这两者对排查的价值差得很远：
     * 前者能直接看出「权重不兼容」，后者只是一堆看不出所以然的行。
     */
    private fun engineFailure(fen: String, reason: String): EngineException {
        val explained = recentLines
            .filter { isUciEngineMessage(it) }
            .joinToString(" / ") { it.substringAfter("string ").trim() }
        val raw = recentLines.joinToString(" / ")
        val detail = explained.ifEmpty { raw }.ifEmpty { "无输出" }
        val message = "$reason（引擎输出：$detail）"
        Log.w(tag, "引擎未给出 bestmove，FEN=$fen，$message")
        close()
        return EngineException(message)
    }

    /**
     * 记下引擎最近输出的若干行，供故障时回溯。
     *
     * 收的是**搜索结果之外**的一切：`info string …`（引擎的提示与错误）、握手期间的非
     * `id`/`option` 行、以及任何不认识的输出（包括合并进来的 stderr，如原生崩溃的
     * `Illegal instruction`）。这些才是能说明「为什么没有 bestmove」的材料。
     */
    private fun remember(line: String) {
        if (line.isBlank()) return
        if (recentLines.size >= DIAGNOSTIC_LINES) recentLines.removeFirst()
        recentLines.addLast(line.trim())
    }

    // `info` 行的解析搬到了 UciOutput.kt 的 parseUciInfo()（纯函数，JVM 单测直接钉）。

    private fun send(command: String) {
        val out = writer ?: return
        runCatching {
            out.write(command)
            out.write("\n")
            out.flush()
        }.onFailure { Log.w(tag, "引擎写入失败: $command", it) }
    }

    /**
     * 读到出现 [token] 开头的行；超时返回 false（不抛异常，由调用方决定怎么处理）。
     *
     * 顺手把「引擎说的话」记进诊断缓冲：`uci` 握手会打一屏 `id` / `option` 行（已知、
     * 无信息量，滤掉），但引擎起不来时往往正是在这里报警 —— 比如 CPU 不支持、
     * EvalFile 路径不对。不记的话，「未在 5000ms 内响应 uciok」就只剩一句无输出。
     */
    private fun readUntil(token: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val input = reader ?: return false
            if (!input.ready()) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            val line = input.readLine() ?: return false
            if (line.startsWith(token)) return true
            if (!line.startsWith("id ") && !line.startsWith("option ")) remember(line)
        }
        return false
    }

    /** 超时兜底：把管道里到 bestmove 为止的残留输出丢掉，避免污染下一次分析。 */
    private fun drainUntilBestMove() {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val input = reader ?: return
            if (!input.ready()) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            val line = input.readLine() ?: return
            if (line.startsWith("bestmove")) return
        }
    }

    @Synchronized
    override fun close() {
        runCatching { send("quit") }
        runCatching { process?.destroy() }
        process = null
        writer = null
        reader = null
        recentLines.clear()
        started = false
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30L

        /** 引擎提前结束搜索时的宽限时间，避免因调度抖动误判超时 */
        const val IDLE_GRACE_MS = 3000L

        /** 收到 stop 之后最多再等多久 */
        const val DRAIN_TIMEOUT_MS = 1500L

        /**
         * 故障时最多回溯多少行引擎输出。
         *
         * 从 8 提到 16 的原因：现在 `info string …` 也进这个缓冲，而引擎每次起搜索会打
         * 几行无信息量的自述（`Available processors` / `Using N thread` / `NNUE evaluation
         * using …` / `Network replica N`）。真实故障信息通常是一个 5 行左右的 ERROR 块，
         * 缓冲太小会被自述挤掉 —— 而它正是报障时唯一能说明死因的东西。
         */
        const val DIAGNOSTIC_LINES = 16

        val WHITESPACE = Regex("\\s+")
    }
}
