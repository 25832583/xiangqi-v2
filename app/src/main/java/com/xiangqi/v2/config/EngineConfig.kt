package com.xiangqi.v2.config

/**
 * 引擎（皮卡鱼 / 任意 UCI 中国象棋引擎）参数。
 *
 * 重构前 Threads=6、Hash=32、depth=12、movetime=3000、超时 12000 全是硬编码在
 * `PikafishEngine` 里的字面量，换手机（核数不同）或换引擎都得改代码。
 */
data class EngineConfig(
    /** 搜索线程数；按手机大核数量调，过多反而互相抢核 */
    val threads: Int = 6,
    /** 置换表大小（MB） */
    val hashMb: Int = 32,
    /** 默认搜索深度 */
    val defaultDepth: Int = 12,
    /** 单次搜索的思考时间上限（毫秒），复杂局面靠它兜底返回 */
    val moveTimeMs: Int = 3000,
    /** 引擎启动握手（uciok/readyok）的超时 */
    val startupTimeoutMs: Long = 5000L,
    /** 一次分析在协程侧的整体超时，超过即放弃本帧 */
    val analysisTimeoutMs: Long = 12000L,
    /** assets 里的引擎文件名 */
    val assetEngine: String = "pikafish",
    /** assets 里的 NNUE 权重文件名 */
    val assetWeights: String = "pikafish.nnue",
    /** 系统解压出来的原生库名，优先于 assets 解压路径 */
    val nativeLibName: String = "libpikafish.so",
    /** 额外的 UCI setoption，键为选项名 */
    val extraOptions: Map<String, String> = emptyMap()
)
