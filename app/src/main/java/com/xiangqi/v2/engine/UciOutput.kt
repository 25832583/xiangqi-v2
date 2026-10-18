package com.xiangqi.v2.engine

/**
 * UCI 输出行的**纯解析**：只吃 String，不吃进程。
 *
 * 抽成纯函数的理由是本工程的老规矩 —— 原先这段逻辑是 [UciEngine] 的私有方法，而宿主类要
 * 吃 `ProcessBuilder` 和 `android.util.Log`，JVM 单测碰不到它，于是缺陷只能靠真机撞见。
 * 2026-10-02 就真撞见了：换棋盘皮肤那类问题之外，引擎自己报的错也一直没进过日志。
 *
 * ## `info string` 是引擎在「说话」，不是搜索结果
 *
 * UCI 里 `info` 行有两类，长得像但含义完全不同：
 *
 * - `info depth 12 score cp 30 pv h2e2 …` —— **搜索结果**，字段可解析；
 * - `info string ERROR: Network … was not loaded.` —— **引擎对操作者说的话**
 *   （权重加载失败、启动参数不对、内部异常），**没有任何可解析字段**。
 *
 * 老实现按「行首是不是 `info`」分流，把后者也丢进解析器；解析器对缺字段的行返回一个
 * 「字段全空」的对象而不报错，于是这条**唯一说明死因的行被静默丢弃** —— 上层拿到的是
 * `引擎未给出 bestmove（引擎输出：无输出）`，与「引擎一声不响地死了」长得一模一样，
 * 排查退化成猜测。实测复现（PC 版皮卡鱼加载不兼容的权重）：
 *
 * ```
 * info string ERROR: Network evaluation parameters compatible with the engine must be available.
 * info string ERROR: The network file … was not loaded.
 * info string ERROR: The engine will be terminated now.
 * ```
 *
 * 现在这类行与其它意外输出一起进诊断缓冲，故障信息里能直接看到引擎说了什么。
 */

/** 一条 `info` 行解析出的字段；缺哪个就是 null。 */
data class UciInfo(
    val scoreCp: Int? = null,
    val mateIn: Int? = null,
    val depth: Int? = null,
    val pv: List<String>? = null
) {
    /**
     * 一个字段都没解析出来 —— 说明这不是一条搜索结果。
     * `info string …` 与未知格式的 info 行都会落到这里，调用方应把它当「引擎发言」记下来。
     */
    val isEmpty: Boolean
        get() = scoreCp == null && mateIn == null && depth == null && pv == null
}

private val WHITESPACE = Regex("\\s+")

/**
 * 解析一条 UCI 输出行。
 *
 * @return 不是 `info` 行时返回 null（调用方按「其它输出」处理）；
 *         是 `info` 行时**总是**返回对象，解析不出字段则 [UciInfo.isEmpty] 为 true。
 */
fun parseUciInfo(line: String): UciInfo? {
    if (!line.startsWith("info")) return null
    val scoreCp = if (line.contains(" score cp ")) {
        line.substringAfter(" score cp ").substringBefore(' ').toIntOrNull()
    } else null
    val mateIn = if (line.contains(" score mate ")) {
        line.substringAfter(" score mate ").substringBefore(' ').toIntOrNull()
    } else null
    val depth = if (line.contains(" depth ")) {
        line.substringAfter(" depth ").substringBefore(' ').toIntOrNull()
    } else null
    val pv = if (line.contains(" pv ")) {
        line.substringAfter(" pv ").trim().split(WHITESPACE).filter { it.isNotEmpty() }
    } else null
    return UciInfo(scoreCp, mateIn, depth, pv)
}

/**
 * `info string …` —— 引擎的**人话**（提示 / 错误 / 权重加载结果）。
 *
 * 单独判一下的原因：这类行要进诊断缓冲，而且报障时它是第一顺位要看到的东西，
 * 与「有字段的 info」必须分开。空格数按 UCI 惯例是一个，但也容忍两个。
 */
fun isUciEngineMessage(line: String): Boolean =
    line.startsWith("info string ") || line.startsWith("info  string ")
