package com.xiangqi.v2.pipeline

/**
 * 「单帧即采」（[GateMode.FAST]）的采纳判据：一个纯状态机。
 *
 * 抽成独立类的理由和 [StableFrameGate] 一样 —— 它全是「第几帧才动手」的边界，
 * 在真机上根本观察不出来（表现一律是"偶尔闪一下"或"卡住不动"），
 * 只有单测能把每一条边钉住。
 *
 * ## 两条否决线，各给一格容忍
 *
 * 1. **盘面不合法**（[com.xiangqi.v2.core.BoardSanity]）：动画中间态有可能一瞬出现
 *    两个帅、过河兵回到底线这种不可能局面。第一帧就报红字，会让**每步棋都闪一次**，
 *    比不报更糟。所以第 [maxIllegalStreak] 帧才认定「不是中间态，是真认错了」。
 * 2. **与上一步对不上**（[com.xiangqi.v2.core.rule.StepResolver]）：换一局棋、把手机
 *    转 180°、或者识别持续出错，都会长期停在这条分支上。一直不放过 = 局面永不更新，
 *    而界面不给任何原因 —— 到 [maxRejectStreak] 帧就强制放行。
 *
 * ## 为什么 2 而不是多帧模式的 3
 *
 * 多帧模式本身已经先花掉一帧在等第二帧相同，所以它的「对不上」容忍给到 3（≈2 秒）。
 * 单帧模式存在的意义就是省掉那一帧，容忍再给 3 等于把它省下来的又赔回去。
 * 2 帧 × 700ms ≈ 1.4 秒，已经长过一个走子动画（<1 秒）。
 *
 * 这个类不是线程安全的，只由盯盘循环一个线程调用。
 */
class FastAcceptPolicy(
    private val maxIllegalStreak: Int = DEFAULT_MAX_ILLEGAL_STREAK,
    private val maxRejectStreak: Int = DEFAULT_MAX_REJECT_STREAK
) {

    sealed interface Decision {
        /** 可以采纳了。 */
        object Accept : Decision

        /** 本帧跳过，且**不推状态** —— 很可能只是动画的一瞬，闪红字反而误导。 */
        object Skip : Decision

        /** 连续多帧不合法，认错了：要带着原因告诉用户。 */
        data class ReportIllegal(val reason: String) : Decision

        /** 连续多帧对不上，强制放行（防局面永久卡死）。 */
        object ForceAccept : Decision
    }

    private var illegalStreak = 0
    private var rejectStreak = 0

    /**
     * 喂一帧。
     *
     * @param illegalReason 本帧盘面不合法的原因（[com.xiangqi.v2.core.BoardSanity.check]
     *        的返回值）；null 表示合法。
     * @param stepExplained 本帧能否由上一帧走一步得到；null 表示没有参照
     *        （首帧采纳、用户手动重新识别、或已决定跳过该判据）。
     */
    fun onFrame(illegalReason: String?, stepExplained: Boolean?): Decision {
        if (illegalReason != null) {
            illegalStreak++
            // 不合法与「对不上」是两种不同的错法，混在一起计数会让两条防线互相清零
            rejectStreak = 0
            return if (illegalStreak >= maxIllegalStreak) {
                Decision.ReportIllegal("识别异常：$illegalReason")
            } else {
                Decision.Skip
            }
        }
        illegalStreak = 0

        if (stepExplained == false) {
            rejectStreak++
            return if (rejectStreak >= maxRejectStreak) Decision.ForceAccept else Decision.Skip
        }
        rejectStreak = 0
        return Decision.Accept
    }

    /** 采纳成功后调用。不清零的话，下一帧会立刻又触发强制放行。 */
    fun onAccepted() = reset()

    fun reset() {
        illegalStreak = 0
        rejectStreak = 0
    }

    companion object {
        /** 连续几帧不合法才报给用户。2 帧 × 700ms ≈ 1.4 秒，够过掉一个走子动画。 */
        const val DEFAULT_MAX_ILLEGAL_STREAK = 2

        /** 连续几帧对不上之后强制放行。 */
        const val DEFAULT_MAX_REJECT_STREAK = 2
    }
}
