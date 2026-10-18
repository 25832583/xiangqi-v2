package com.xiangqi.v2.config

import android.content.Context
import com.xiangqi.v2.pipeline.GateMode

/**
 * 用户可调项的唯一持久化处。
 *
 * 只有「用户真的会调、且调了效果肉眼可见」的几项：
 *  - 深度 / 思考时间 → 直接影响推荐质量和出招延迟；
 *  - 线程数 → 影响搜索速度，但超过大核数反而互相抢核；
 *  - 哈希 → 连续对局时复用置换表，越大越省重算。
 *
 * 其余参数（格距搜索范围、吸附残差上限、NMS 门槛…）属于**标定值**，不该暴露给用户：
 * 它们和模型/皮肤绑定，调错了表现为"识别不准"而不是"参数不对"，用户无从下手。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        // 老安装里存的 12 是"从没调过"的旧默认值，只把它抬到新默认，用户自己调过的不动。
        // 细节与判据见 [DepthDefault]。
        if (!prefs.getBoolean(DepthDefault.FLAG, false)) {
            val stored = if (prefs.contains(KEY_DEPTH)) depth else null
            val target = DepthDefault.migrate(stored, migrated = false)
            prefs.edit().apply {
                if (target != null) putInt(KEY_DEPTH, target)
                putBoolean(DepthDefault.FLAG, true)
            }.apply()
        }
    }

    /**
     * 搜索深度（**上限**，不是目标）。
     *
     * 引擎收到的是 `go depth N movetime M`，先到者生效：真正兜住"别卡太久"的是思考时间
     * （[moveTimeMs]），深度只决定"够快算完的局面能算多深"。所以调高深度不会让复杂局面
     * 更慢，只会让简单局面把时间用满 —— 这正是默认值从 12 提到 18 的原因：
     * 实测 depth 12 只要 4 万节点（手机约 0.1 秒），2500ms 的预算基本在空转。
     */
    var depth: Int
        get() = prefs.getInt(KEY_DEPTH, DEFAULT_DEPTH).coerceIn(MIN_DEPTH, MAX_DEPTH)
        set(value) = prefs.edit().putInt(KEY_DEPTH, value.coerceIn(MIN_DEPTH, MAX_DEPTH)).apply()

    /** 单次搜索的时间上限，深度没到时靠它兜底返回。 */
    var moveTimeMs: Int
        get() = prefs.getInt(KEY_MOVE_TIME, DEFAULT_MOVE_TIME).coerceIn(MIN_MOVE_TIME, MAX_MOVE_TIME)
        set(value) = prefs.edit()
            .putInt(KEY_MOVE_TIME, value.coerceIn(MIN_MOVE_TIME, MAX_MOVE_TIME)).apply()

    /**
     * 搜索线程数。改动**必须**重建引擎进程（Threads 只在握手时生效），
     * 所以 setter 里顺手把 [engineRevision] 推一格，让编排层知道缓存的那个引擎作废了。
     */
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, defaultThreads()).coerceIn(1, maxThreads())
        set(value) {
            val v = value.coerceIn(1, maxThreads())
            if (v == threads) return
            prefs.edit().putInt(KEY_THREADS, v).putInt(KEY_REVISION, engineRevision + 1).apply()
        }

    var hashMb: Int
        get() = prefs.getInt(KEY_HASH, DEFAULT_HASH).coerceIn(MIN_HASH, MAX_HASH)
        set(value) {
            val v = value.coerceIn(MIN_HASH, MAX_HASH)
            if (v == hashMb) return
            prefs.edit().putInt(KEY_HASH, v).putInt(KEY_REVISION, engineRevision + 1).apply()
        }

    /**
     * 引擎参数版本号。线程/哈希一变就 +1。
     *
     * 用它而不是逐字段比对，是因为比对要记住「这个引擎是用哪套参数起的」，
     * 那份状态一旦漏更新，就会出现"改了线程数但没生效"这种查半天的现象。
     */
    val engineRevision: Int get() = prefs.getInt(KEY_REVISION, 0)

    /** 自动盯盘的间隔。无障碍截图有系统级节流，低于 500ms 意义不大。 */
    var scanIntervalMs: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)).apply()

    /** 识别模型。默认用实测 100% 的那个；另一个留着做对照。 */
    val modelAsset: String get() = MODEL_V5

    /**
     * 采纳盘面的门槛：多帧确认 / 单帧即采。
     *
     * 这是**手感 vs 稳妥**的取舍，用户比我们更清楚自己在忍哪一种：
     *  - 多帧确认慢一个扫描间隔（默认 700ms），但走子动画的中间态不会被采信；
     *  - 单帧即采快，代价是中间态偶尔会变成一个闪一下的错着法。
     *
     * 默认多帧：错着的代价（用户照着走，白丢一子）比多等 0.7 秒大得多。
     * 读的时候做兜底 —— 枚举名改了或被人改坏了，不该让盯盘循环崩在设置上。
     */
    var gateMode: GateMode
        get() = runCatching {
            GateMode.valueOf(prefs.getString(KEY_GATE_MODE, null) ?: GateMode.STABLE.name)
        }.getOrDefault(GateMode.STABLE)
        set(value) = prefs.edit().putString(KEY_GATE_MODE, value.name).apply()

    /** 悬浮球位置（像素）。-1 表示还没拖过，由服务算一个默认位置。 */
    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    /**
     * 面板里是否显示小棋盘。
     *
     * 默认开：箭头比「炮二平五」直观得多，尤其残局里子少位偏。但它有代价 ——
     * 面板会高出约 200dp，小屏上会顶到状态栏，所以必须能一键收起，
     * 而且这个选择要记住（每次展开都要手动收一次很烦）。
     */
    var showBoard: Boolean
        get() = prefs.getBoolean(KEY_BOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_BOARD, value).apply()

    /** ONNX 会话线程数。和磁盘解压一样，属于整个应用共享的固定值，不做设置项。 */
    val sessionThreads: Int get() = 4

    fun engineConfig(): EngineConfig = EngineConfig(
        threads = threads,
        hashMb = hashMb,
        defaultDepth = depth,
        moveTimeMs = moveTimeMs
    )

    /** 本机推荐的默认线程数：一半核心，夹在 2..6 —— 全占满会让界面和截图卡顿。 */
    private fun defaultThreads(): Int = (maxThreads() / 2).coerceIn(2, 6)

    fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    companion object {
        const val MIN_DEPTH = 6
        /**
         * 上限从 20 提到 24：默认已经 18，若上限仍是 20，"深一点"只按得动两次。
         * 调高上限本身不会拖慢局面 —— `go depth N movetime M` 里 movetime 先到就先返回。
         */
        const val MAX_DEPTH = 24
        const val DEFAULT_DEPTH = DepthDefault.CURRENT

        const val MIN_MOVE_TIME = 300
        const val MAX_MOVE_TIME = 8000
        const val DEFAULT_MOVE_TIME = 2500

        const val MIN_HASH = 8
        const val MAX_HASH = 256
        const val DEFAULT_HASH = 32

        const val MIN_INTERVAL = 400
        const val MAX_INTERVAL = 3000
        const val DEFAULT_INTERVAL = 700

        /** 实测 49 张残局逐颗 100% 的权重（见 README「六之五」）。 */
        const val MODEL_V5 = "detector_xqlink_v5.onnx"

        private const val FILE = "xiangqi_v2_settings"
        private const val KEY_DEPTH = "depth"
        private const val KEY_THREADS = "threads"
        private const val KEY_HASH = "hash"
        private const val KEY_MOVE_TIME = "move_time"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_REVISION = "engine_revision"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BOARD = "show_board"
        private const val KEY_GATE_MODE = "gate_mode"
    }
}
