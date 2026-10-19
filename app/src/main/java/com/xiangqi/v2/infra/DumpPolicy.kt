package com.xiangqi.v2.infra

/**
 * 失败帧存档的节流策略。
 *
 * 为什么需要节流：识别失败是**每 700ms 一次**的循环。用户如果把悬浮球压在棋盘上，
 * 一分钟能失败 80 多次 —— 无节制存图会塞满相册、还会让识别循环卡在 JPEG 压缩上。
 * 存图是给「复现一次就够」用的诊断手段，不是录制。
 *
 * 三条约束：
 *  - 一次会话最多 [maxCount] 张（用户点「重新识别」不清零，重开服务才清零）；
 *  - 两次间隔至少 [intervalMs]，保证存下来的是**不同时刻**；
 *  - 首张立即存（用户往往只复现几秒就放弃了）。
 *
 * 抽成纯逻辑是为了能单测 —— 真机上没法验证「第 6 张不存」。
 */
class DumpPolicy(
    private val maxCount: Int = 5,
    private val intervalMs: Long = 8_000L
) {

    private var count = 0
    private var lastAt = 0L

    /** 已存张数，日志用。 */
    val savedCount: Int get() = count

    /**
     * 判定这一帧要不要存，**并就地记账** —— 判定与记账必须是同一个原子操作，
     * 否则并发调用（手动识别 + 循环）会同时通过判定，多存一张。
     */
    @Synchronized
    fun tryTake(now: Long): Boolean {
        if (count >= maxCount) return false
        // 用 count 而不是 lastAt!=0 判断「有没有存过」：时间戳完全是可能取到 0 的
        // （单测里就是），拿 0 当哨兵会让第二张跳过间隔检查
        if (count > 0 && now - lastAt < intervalMs) return false
        count++
        lastAt = now
        return true
    }

    @Synchronized
    fun reset() {
        count = 0
        lastAt = 0L
    }
}
