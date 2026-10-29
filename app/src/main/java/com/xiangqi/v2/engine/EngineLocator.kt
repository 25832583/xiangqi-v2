package com.xiangqi.v2.engine

import android.content.Context
import android.util.Log
import com.xiangqi.v2.config.EngineConfig
import com.xiangqi.v2.infra.AssetStore
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.infra.WeightsStore
import com.xiangqi.v2.infra.WeightsRules
import java.io.File

/** 一套引擎落地文件：可执行文件 + 可选权重。 */
data class EngineBinaries(val enginePath: String, val weightsPath: String?)

/**
 * 引擎二进制定位策略。
 *
 * 不同分发方式下引擎的落点完全不同 —— 打包进 jniLibs 时由系统解压到 nativeLibraryDir，
 * 纯 assets 分发时要自己解压到 filesDir。上层只问「有哪些候选」，不关心这些差别。
 */
interface EngineLocator {
    /** 按优先级返回候选；空列表表示一个都找不到。 */
    fun candidates(): List<EngineBinaries>
}

/**
 * 默认策略：
 *  1. 系统从 jniLibs 解压出来的 `nativeLibraryDir/<libName>`。
 *     **这一条是必须的**：Android 10 起应用不能执行自己可写目录里的文件（W^X），
 *     而 filesDir 正是可写目录，只有 nativeLibraryDir 带可执行权限。
 *  2. assets 里的引擎文件，解压到 filesDir 并补可执行位 —— 给「老系统」和
 *     「用户手动替换引擎」留的口子，能不能真跑起来取决于系统策略。
 *
 * 权重（NNUE）是**尽力而为**：解压失败或文件缺失就返回 null，让引擎降级为无权重模式运行 ——
 * 棋力会下降但功能仍可用，比整个功能起不来划算。
 */
class DefaultEngineLocator(
    private val context: Context,
    private val assets: AssetStore,
    private val config: EngineConfig,
    private val tag: String = LogTags.ENGINE
) : EngineLocator {

    override fun candidates(): List<EngineBinaries> {
        val weights = resolveWeights()
        val result = LinkedHashSet<EngineBinaries>()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        if (nativeDir != null) {
            val lib = File(nativeDir, config.nativeLibName)
            if (lib.exists()) result.add(EngineBinaries(lib.absolutePath, weights))
        }

        runCatching {
            assets.extractTo(config.assetEngine, "engine/${config.assetEngine}", executable = true)
        }.onSuccess { extracted ->
            result.add(EngineBinaries(extracted.absolutePath, weights))
        }.onFailure {
            Log.w(tag, "解压引擎 assets/${config.assetEngine} 失败", it)
        }

        return result.toList()
    }

    /**
     * 权重来源。
     *
     * 顺序是「已落地的文件 → assets」，而不是反过来：用户导入的新权重必须盖住包里那份，
     * 否则他换了权重却毫无变化。而分发包里根本没有 `pikafish.nnue` 这个 asset，
     * 第一条分支就是它唯一的路径。
     */
    private fun resolveWeights(): String? {
        val dest = File(context.filesDir, "${WeightsStore.DIR}/${config.assetWeights}")
        if (dest.length() > 0L) {
            Log.i(tag, "NNUE 权重：${dest.absolutePath}（${WeightsRules.mb(dest.length())}）")
            return dest.absolutePath
        }
        if (!assets.has(config.assetWeights)) {
            // 可分发包的正常状态：包里没有权重，用户也还没导入。不是故障，降级运行。
            Log.w(tag, "包内无 ${config.assetWeights} 且未导入权重，引擎以无权重模式运行")
            return null
        }
        return runCatching {
            assets.extractTo(config.assetWeights, "${WeightsStore.DIR}/${config.assetWeights}").absolutePath
        }.onFailure {
            Log.w(tag, "解压 NNUE 权重失败，将以无权重模式运行", it)
        }.getOrNull()
    }
}

/**
 * 依次尝试候选引擎，第一个握手成功的胜出。
 *
 * 为什么不在构造时就启动：解压 53MB 的 NNUE、起进程、握手要好几秒，
 * 只在真正需要分析时才做，避免用户一开应用就卡住。
 */
class UciEngineFactory(
    private val locator: EngineLocator,
    private val config: EngineConfig,
    private val tag: String = LogTags.ENGINE,
    /** 降到无权重模式时回调，参数是原因。UI 靠它把「权重已就绪」改成「版本不匹配」。 */
    private val onDegraded: (String) -> Unit = {}
) {

    fun create(): AnalysisEngine {
        val candidates = locator.candidates()
        if (candidates.isEmpty()) throw EngineException("未找到可用的引擎文件")

        val failures = ArrayList<String>(candidates.size)
        for (binaries in candidates) {
            try {
                return startWith(binaries.enginePath, binaries.weightsPath)
            } catch (e: WeightIncompatibleException) {
                // 权重认不出来不该让整个分析功能趴下。同一份引擎丢开权重再起一次：
                // 棋力会掉到「能走棋」的水平，但比「点一下什么都没发生」有用得多。
                Log.w(tag, "权重不可用，改以无权重模式启动：${e.message}")
                onDegraded(e.message ?: "权重与引擎版本不匹配")
                try {
                    return startWith(binaries.enginePath, null)
                } catch (e2: Exception) {
                    failures.add("${binaries.enginePath}: ${e2.message}")
                }
            } catch (e: Exception) {
                failures.add("${binaries.enginePath}: ${e.message}")
            }
        }
        throw EngineException("所有候选引擎都无法启动：${failures.joinToString("；")}")
    }

    private fun startWith(enginePath: String, weightsPath: String?): AnalysisEngine {
        val engine = UciEngine(config, enginePath, weightsPath, tag)
        try {
            engine.start()
            return engine
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw e
        }
    }
}
