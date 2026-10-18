package com.xiangqi.v2.infra

import java.util.Locale

/**
 * 分段计时器 —— 把「这一步花了多久」变成日志里的一行数字。
 *
 * ## 为什么需要它
 *
 * 用户报「识别完后给出推荐出招的速度变慢了」。当时这条链路上**一个耗时打点都没有**：
 * 只有用来兜底的超时阈值（deadline），没有任何「这次实际花了多久」。而这条链路上
 * 两端的时间尺度差着三个数量级，靠感觉根本分不出来：
 *
 * | 环节 | 量级 |
 * | --- | --- |
 * | 识别（找框 + 检子 + 90 格逐格分类） | 数百毫秒 |
 * | 引擎搜索（[com.xiangqi.v2.config.EngineConfig.moveTimeMs]） | 默认 **3000ms** |
 * | **引擎重建**（起进程 + 加载 50MB NNUE 权重 + 握手） | **秒级**，且这一帧完全是白等 |
 *
 * 没有数字，就分不清「引擎隔几次就重建一回」和「识别真的变慢了」—— 而这两者的
 * 修法完全不同（前者要查引擎为什么死，后者要查识别链路）。打点之后，用户给一行
 * 日志就能定位，符合本工程的老规矩：**日志里没有的东西，等于不存在**。
 *
 * ## 时钟可注入
 *
 * [nowMs] 默认取单调时钟（`nanoTime`，不受系统时间被调整的影响），单测可以喂假时钟
 * 逐项断言输出，不必真的等 —— 与 `GridFitter` 抽纯函数是同一个理由：
 * 让「算得对不对」能在 JVM 上被钉住。
 *
 * ## 用法
 *
 *     val timer = SectionTimer()
 *     val bounds = findBoardBounds(frame)
 *     timer.mark("找框")
 *     ...
 *     Log.i(TAG, "识别耗时：${timer.mark("组装").describe()}")
 *
 * [describe] 输出的每一项与 `总 N ms` 都是**毫秒**。
 */
class SectionTimer(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private val startMs = nowMs()
    private var lastMs = startMs
    private val marks = ArrayList<Pair<String, Long>>()

    /**
     * 记下「上一次 mark 到现在」的耗时（毫秒）。
     *
     * 返回自身，方便直接写进日志：`timer.mark("组装").describe()`。
     * 段名由调用方给 —— 名字要能直接看出是哪一步，别用「阶段1」这种。
     */
    fun mark(name: String): SectionTimer {
        val now = nowMs()
        marks.add(name to (now - lastMs))
        lastMs = now
        return this
    }

    /** 从创建到现在的总耗时（毫秒）。可能大于各分段之和（mark 之间还有未计的部分）。 */
    fun totalMs(): Long = nowMs() - startMs

    /** 一行式描述，例：`总 412ms｜找框 38｜裁片 2｜检子 96｜拟合 1｜逐格 271｜组装 4`。 */
    fun describe(): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "总 %dms", totalMs()))
        for ((name, ms) in marks) {
            sb.append('｜').append(name).append(' ').append(ms)
        }
        return sb.toString()
    }
}
