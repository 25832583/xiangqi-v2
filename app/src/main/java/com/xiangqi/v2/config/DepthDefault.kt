package com.xiangqi.v2.config

/**
 * 深度默认值 12 → 18，以及**已有安装怎么跟上**。
 *
 * 只改常量是不够的：`prefs.getInt(KEY_DEPTH, DEFAULT_DEPTH)` 在用户动过一次滑块之后
 * 就再也读不到默认值了 —— 那批机器会一直停在 12，改了等于没改。但反过来无差别覆盖
 * 又会把「用户主动调到 8 图快」的人按回 18，那是不该替他做的决定。
 *
 * 所以判据只有一条：**存着的值正好等于旧默认值 12**，才认为它只是"没调过"。
 * 调过 8 / 10 / 16 的一律不动。迁移只做一次（用 [FLAG] 记住），之后用户再手动调回 12
 * 就是他自己的选择，不会被下次启动改回来。
 *
 * ## 为什么 12 → 18 是安全的
 *
 * 引擎收到的是 `go depth N movetime M`，**先到者生效**。深度只是上限，真正的兜底是
 * 思考时间（默认 2500ms）—— 所以调高深度不会让复杂局面卡更久，它只会让原本
 * "几十毫秒就算完"的简单局面把时间用满。实测 depth 12 只要 4 万节点（手机上约 0.1 秒），
 * 同一个 2.5 秒预算里 depth 20 能跑 250 万节点，中间那 60 倍算力原本是空转的。
 */
object DepthDefault {

    /** 旧的默认深度。只有等于它才视为"用户没动过"。 */
    const val LEGACY = 12

    /** 新默认深度。 */
    const val CURRENT = 18

    /** 迁移标志键。存下之后不再迁移，用户后续的选择才算数。 */
    const val FLAG = "depth_migrated_18"

    /**
     * 该把存着的深度改成多少；返回 null 表示不动。
     *
     * @param stored 已存的深度；null 表示从来没存过（新默认值自然生效，不必写）
     * @param migrated 是否已经迁移过
     */
    fun migrate(stored: Int?, migrated: Boolean): Int? = when {
        migrated -> null
        stored == null -> null
        stored == LEGACY -> CURRENT
        else -> null
    }
}
