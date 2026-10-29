package com.xiangqi.v2.di

import android.content.Context
import com.xiangqi.v2.capture.AccessibilityFrameSource
import com.xiangqi.v2.capture.FrameSource
import com.xiangqi.v2.config.EngineConfig
import com.xiangqi.v2.config.RecognitionConfig
import com.xiangqi.v2.config.SettingsStore
import com.xiangqi.v2.engine.DefaultEngineLocator
import com.xiangqi.v2.engine.EngineLocator
import com.xiangqi.v2.infra.AssetStore
import com.xiangqi.v2.infra.OnnxModel
import com.xiangqi.v2.infra.WeightsStore
import com.xiangqi.v2.pipeline.AnalysisPipeline
import com.xiangqi.v2.service.CaptureAccessibilityService
import com.xiangqi.v2.vision.BoardLocator
import com.xiangqi.v2.vision.BoardRecognizer
import com.xiangqi.v2.vision.OnnxDetector
import java.io.File

/**
 * 应用级单例图谱。
 *
 * 为什么不是「每次进服务新建一套」：解压 28MB 模型 + 建 ONNX 会话要 1~3 秒，
 * 起引擎进程 + 加载 53MB NNUE 要更久。而悬浮服务会因为「改设置 / 用户手动停止再启动 /
 * 系统回收」被反复重启 —— 每次重建都让第一帧白等好几秒。
 *
 * 这里所有的 `by lazy` 都是**首次真正用到才建**，所以只点开主界面看看设置不会付这些代价。
 */
class AppGraph(context: Context) {

    private val app = context.applicationContext

    val assets = AssetStore(app)

    /** NNUE 权重的落地与导入（权重不随分发包走，见 [WeightsStore] 的类注释）。 */
    val weights = WeightsStore(app)

    val settings = SettingsStore(app)

    /** 引擎有没有可用权重：包里带的，或用户导进去的，都算。 */
    fun weightsReady(): Boolean = weights.ready() || assets.has(EngineConfig().assetWeights)

    /** 识别参数是标定值，不跟用户设置走。 */
    private val recognitionConfig = RecognitionConfig()

    /**
     * 识别器。
     *
     * 模型选择固定为实测 100% 的那个权重（`detector_xqlink_v5.onnx`）。
     * 为什么不把「v5 / 自训 v8」做成开关：v8 在真实残局上只有 73.8%，
     * 摆出来只会让人以为是设置问题。换模型应该在离线用 `tools/eval_gt.py` 比完再换这一行。
     */
    val recognizer: BoardRecognizer by lazy {
        val asset = settings.modelAsset
        val path = assets.extractTo(asset, "models/$asset").absolutePath
        val model = OnnxModel(
            modelPath = path,
            threads = settings.sessionThreads,
            inputShape = longArrayOf(1, 3, RECOGNITION_INPUT.toLong(), RECOGNITION_INPUT.toLong())
        )
        BoardRecognizer(
            detector = OnnxDetector(model, recognitionConfig),
            config = recognitionConfig,
            // 传**函数**而不是实例：解压 38MB + 建会话要几百毫秒，而绝大多数用户
            // 一次都不会触发「主模型给不出棋盘框」。首次真正需要时才付这笔钱。
            locator = BoardLocator(
                modelProvider = ::boardLocatorModel,
                config = recognitionConfig
            )
        )
    }

    /**
     * 兜底定位模型（DeepXiangQi detect-ultra 导出的 ONNX）。
     *
     * 只用来在**主模型给不出合格棋盘框**时补一个框；棋子分类仍然全用主模型 ——
     * 它会把浅色皮肤上的「兵」认成「马」且置信 0.94，详见 [BoardLocator] 的类注释。
     *
     * 许可提醒：上游仓库的 license 字段为空（等于保留全部权利），自己用没问题，
     * 对外分发前必须换成有明确许可的权重。
     */
    private fun boardLocatorModel(): OnnxModel = OnnxModel(
        modelPath = assets.extractTo(LOCATOR_ASSET, "models/$LOCATOR_ASSET").absolutePath,
        threads = settings.sessionThreads,
        inputShape = longArrayOf(1, 3, LOCATOR_INPUT.toLong(), LOCATOR_INPUT.toLong())
    )

    private val engineLocator: EngineLocator by lazy {
        // 这里传的是「引擎文件名与原生库名」的默认配置，线程/哈希由每次 create 时的配置决定
        DefaultEngineLocator(app, assets, EngineConfig())
    }

    val pipeline: AnalysisPipeline by lazy {
        AnalysisPipeline(
            recognizer = recognizer,
            locator = engineLocator,
            config = { settings.engineConfig() },
            onEngineDegraded = { engineDegradedReason = it }
        )
    }

    /**
     * 引擎被迫以无权重模式运行时记下的原因；null 表示正常。
     *
     * 为什么需要它：权重文件在、体积也对，但网络结构跟引擎对不上时，引擎会拒绝加载
     * 并自杀。降级后功能照常，界面上「NNUE 权重 ▌ 50.7MB」还是绿的 —— 不说一句，
     * 用户会以为棋力问题出在识别或设置上。见 [com.xiangqi.v2.engine.WeightIncompatibleException]。
     */
    @Volatile
    var engineDegradedReason: String? = null
        private set

    /** 换了权重后清掉降级标记，让下次分析重新判定一次。 */
    fun clearEngineDegraded() {
        engineDegradedReason = null
    }

    /** 采集源。无障碍服务实例随时可能被用户关掉，所以每次抓帧都重新取。 */
    fun frameSource(): FrameSource =
        AccessibilityFrameSource({ CaptureAccessibilityService.provider() })

    /**
     * 运行必需的资产缺哪些，空表示齐了。
     *
     * 这些文件因体积与许可不入库（见 NOTICE.md），克隆后不跑
     * `python tools/fetch_assets.py` 就没有。而缺了的表现**不是报错**——
     * 识别每帧抛异常被上层吞掉，界面上只是「没反应」，
     * 排查成本全落在用户身上。所以启动时先自检一次，把缺的摆到明面上。
     *
     * **NNUE 权重不在这里**：它缺失不影响功能，只影响棋力，而且可分发包里本来就没有
     * （见 [WeightsStore]）。把它算成「致命缺失」会让分发包每次启动都弹红框。
     * 它有自己的提示位置 —— 主界面「引擎」分区那一行。
     */
    fun missingAssets(): List<String> {
        val engine = EngineConfig()
        val missing = mutableListOf<String>()
        for (name in listOf(settings.modelAsset, engine.assetEngine)) {
            if (!assets.has(name)) missing += name
        }
        // 引擎二进制以 .so 形式随包安装（W^X 限制），不在 assets 里
        val nativeLib = File(app.applicationInfo.nativeLibraryDir, "libpikafish.so")
        if (!nativeLib.exists()) missing += "libpikafish.so"
        return missing
    }

    private companion object {
        const val RECOGNITION_INPUT = 640
        /** 见 [RecognitionConfig.locatorInputSize]：320 会让框偏高而被尺寸判据拒掉 */
        const val LOCATOR_INPUT = 640
        const val LOCATOR_ASSET = "board_locator_v1.onnx"
    }
}
