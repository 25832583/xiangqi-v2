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
import com.xiangqi.v2.pipeline.AnalysisPipeline
import com.xiangqi.v2.service.CaptureAccessibilityService
import com.xiangqi.v2.vision.BoardRecognizer
import com.xiangqi.v2.vision.OnnxDetector

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

    val settings = SettingsStore(app)

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
        BoardRecognizer(OnnxDetector(model, recognitionConfig), recognitionConfig)
    }

    private val engineLocator: EngineLocator by lazy {
        // 这里传的是「引擎文件名与原生库名」的默认配置，线程/哈希由每次 create 时的配置决定
        DefaultEngineLocator(app, assets, EngineConfig())
    }

    val pipeline: AnalysisPipeline by lazy {
        AnalysisPipeline(
            recognizer = recognizer,
            locator = engineLocator,
            config = { settings.engineConfig() }
        )
    }

    /** 采集源。无障碍服务实例随时可能被用户关掉，所以每次抓帧都重新取。 */
    fun frameSource(): FrameSource =
        AccessibilityFrameSource({ CaptureAccessibilityService.provider() })

    private companion object {
        const val RECOGNITION_INPUT = 640
    }
}
