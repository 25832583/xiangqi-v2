package com.xiangqi.v2.engine

import com.xiangqi.v2.core.Move

/**
 * 一次引擎分析的结论。
 *
 * @param bestMove 引擎给出的最佳走法；引擎没给出（无着可走/搜索失败）时为 null
 * @param scoreCp  评分，单位「厘兵」（centipawn），正数对红方有利
 * @param mateIn   杀棋步数，正数表示红方将杀，负数表示黑方将杀
 * @param pv       主变，从最佳走法开始的连续着法
 * @param depth    实际搜索到的深度
 */
data class EngineAnalysis(
    val bestMove: Move?,
    val bestMoveUci: String,
    val scoreCp: Int?,
    val mateIn: Int?,
    val pv: List<String>,
    val depth: Int
) {
    val hasMove: Boolean get() = !bestMoveUci.isNullOrEmpty()

    val scoreText: String
        get() = when {
            mateIn != null -> "杀 ${Math.abs(mateIn)} 步"
            scoreCp != null -> String.format(java.util.Locale.US, "%+.2f", scoreCp / 100.0)
            else -> ""
        }
}

/** 分析失败的原因，调用方据此给出不同提示。 */
open class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 权重与引擎版本对不上（或权重文件损坏），引擎加载时自杀。
 *
 * 单独成类是因为它的**处理方式不同**：别的启动失败只能往上报，这一类可以退一步 ——
 * 丢掉权重、以无权重模式把引擎跑起来，功能仍可用（棋力掉）。皮卡鱼的 nnue 与引擎
 * 是强绑定的（网络结构决定加载，换一个版本就拒绝），而「从官方 release 下最新权重」
 * 正是用户最自然的动作，撞上的概率不低。
 */
class WeightIncompatibleException(message: String, cause: Throwable? = null) :
    EngineException(message, cause)

/**
 * 局面分析引擎。
 *
 * 现有实现是本地跑皮卡鱼（UCI 协议）。抽成接口的意义：
 *  - 换成别的 UCI 引擎（旋风、佳佳）不改上层；
 *  - 换成**远程分析服务**也只是多一个实现 —— 移动端算力有限，这是很可能要走的一步；
 *  - 测试时可以塞一个「固定返回某走法」的假引擎，整条编排链路都能离线跑。
 */
interface AnalysisEngine {

    val name: String

    /** 引擎当前是否可用（进程已起、握手完成）。 */
    val ready: Boolean

    /**
     * 分析局面。
     * @param fen       完整 FEN（含走子方）
     * @param depth     目标深度
     * @param moveTimeMs 思考时间上限，兜底用
     */
    suspend fun analyze(fen: String, depth: Int, moveTimeMs: Int): EngineAnalysis

    /**
     * 让当前这次搜索**尽快收尾**（不是取消协程）。
     *
     * 存在的理由：盘面在下一次识别后已经变了，旧局面的结论再准也没用，但一次搜索的
     * 时间上限是秒级（默认 2500ms）。不打断的话，用户走一步棋之后要先把**上一手**的
     * 搜索等完，才有反应 —— 这比算得浅一点恼人得多。
     *
     * 实现约定：调完它，正在进行的 [analyze] 应当很快返回（可能是一个很浅的结果），
     * 由调用方丢弃。默认空实现，不支持中断的引擎继续按 moveTime 正常返回。
     */
    fun requestStop() {}

    fun close()
}
