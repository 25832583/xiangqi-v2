package com.xiangqi.v2.pipeline

/**
 * 采纳盘面前的门槛：要不要等第二帧。
 *
 * 两种模式没有绝对优劣，只有取舍，所以做成开关而不是替用户决定：
 *
 *  - [STABLE] 慢一帧，但「走子动画中间态」这一类错不会被采信；
 *  - [FAST] 快一帧，判据只剩「这一帧本身讲不讲得通」，中间态偶尔会漏过去。
 *
 * 注意 [FAST] **不是**「只要不是物理上不可能就采纳」：象棋里「棋子停在半路上」
 * 照样能被 [com.xiangqi.v2.core.BoardSanity] 判成合法（那颗子会吸附到最近的格点），
 * 所以它仍然要过「一步可达」那道判据（见 [com.xiangqi.v2.core.rule.StepResolver]）。
 * 少了那一关，这个模式就是把上一轮修掉的「一步莫名其妙的着法」重新放回来。
 */
enum class GateMode(
    val label: String,
    /** 一句话说清代价。切模式时会提示给用户 —— 这不是纯技术选项。 */
    val note: String
) {

    /** 连续两帧识别结果完全相同才采纳（默认）。代价是多等一个扫描间隔。 */
    STABLE("多帧确认", "慢一帧，挡住走子动画的中间态"),

    /** 单帧即采：本帧合法、且能由上一帧走一步得到，就立刻采纳。 */
    FAST("单帧即采", "快一帧，中间态偶尔可能被采信");

    fun next(): GateMode = if (this == STABLE) FAST else STABLE
}
