package com.xiangqi.v2.pipeline

/**
 * 「连续 N 帧识别结果相同才采纳」这道门槛，外加一条**防卡死**的兜底。
 *
 * 为什么单独抽成一个类：它是纯函数式的时间判据（只需要「盘面指纹 + 当前时刻」），
 * 抽出来就能在 JVM 单测里把每一条边界都钉住 —— 而它恰恰是最难在真机上观察的一段：
 * 出错时表现为「局面不动了」，用户看不出是识别坏了还是门槛卡住了。
 *
 * ## 它在防什么
 *
 * 走子动画的中间态里棋子停在半路上，那个位置**不是**任何合法点位，但识别出来的盘面
 * 「错得很合理」，能一路走到引擎，用户看到的是一步莫名其妙的着法。等一帧就够。
 *
 * ## 它没在防什么
 *
 * 「稳定地错」—— 比如一直把某个仕认成马。那种情况两帧完全一样，本判据直接放行。
 * 那是 [com.xiangqi.v2.core.rule.StepResolver] 的职责，两者分工不同。
 *
 * ## 兜底：为什么必须有一条超时强制
 *
 * 门槛要求「两帧相同」，可画面**逐帧都在抖**（走子动画、吃子粒子、倒计时数字、
 * 屏幕录制指示器）时，指纹每帧都变，永远凑不满两帧相同。旧实现在这种情况下会
 * **一直停在「画面变化中」，局面永不更新，而界面上没有任何原因说明** ——
 * 卡住比偶尔采信一帧坏数据更糟。所以超过 [stallMs] 仍凑不满，就强制采纳当前帧。
 *
 * 注意一条容易写错的语义：**换指纹不重置计时起点**。抖动的定义就是指纹老在变，
 * 若换指纹就把计时清零，兜底永远不会触发 —— 那就等于没写。
 *
 * 这个类不是线程安全的，只由盯盘循环一个线程调用。
 */
class StableFrameGate(
    private val required: Int = 2,
    private val stallMs: Long = DEFAULT_STALL_MS
) {

    sealed interface Decision {
        /** 凑够了，可以进入下一步判定。 */
        data class Stable(val hits: Int) : Decision

        /** 还在等，[hits] 是当前指纹已连续出现的次数。 */
        data class Waiting(val hits: Int, val required: Int) : Decision

        /** 抖动太久凑不满，强制采纳。调用方应**跳过**后续的时序校验（帧本来就不一样）。 */
        data class Forced(val waitedMs: Long, val hits: Int) : Decision
    }

    private var pendingKey: String? = null
    private var hits: Int = 0

    /** 开始等第一帧的时刻；-1 表示当前没有在等。 */
    private var startedAt: Long = -1L

    fun offer(key: String, nowMs: Long): Decision {
        if (startedAt < 0L) startedAt = nowMs

        // 换指纹只把连续计数归 1，**不动** startedAt —— 见类注释最后一段
        if (key == pendingKey) hits++ else {
            pendingKey = key
            hits = 1
        }

        if (hits >= required) {
            val hit = hits
            reset()
            return Decision.Stable(hit)
        }

        val waited = nowMs - startedAt
        if (waited >= stallMs) {
            val hit = hits
            reset()
            return Decision.Forced(waited, hit)
        }
        return Decision.Waiting(hits, required)
    }

    /** 采纳了盘面、或用户手动重新识别时调用。 */
    fun reset() {
        pendingKey = null
        hits = 0
        startedAt = -1L
    }

    companion object {
        /**
         * 抖动多久之后强制采纳。
         *
         * 定 3 秒的依据：正常走子动画不到 1 秒，盯盘间隔 700ms × 两帧 ≈ 1.4 秒；
         * 留一倍余量，既不误伤正常走子，也不会让用户盯着一个不动的结论等太久。
         */
        const val DEFAULT_STALL_MS = 3000L
    }
}
