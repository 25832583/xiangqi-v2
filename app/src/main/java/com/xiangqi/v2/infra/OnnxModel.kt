package com.xiangqi.v2.infra

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.ValueInfo
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ONNX 会话的薄封装：单输入、单输出、固定输入形状。
 *
 * 重构前 `BoardRecognizer` 与 `CellClassifier` 各写了一份 tensor 创建 / 结果读取 /
 * 资源释放，两处的 close 时机还略有差异（漏关会缓慢泄漏 native 内存）。
 * 现在只在这里管一次。
 *
 * 换成 batch=N 的模型时，改 [inputShape] 即可；多输入/多输出的模型再扩展
 * [run] 的重载，不影响现有调用方。
 */
class OnnxModel(
    private val modelPath: String,
    private val threads: Int,
    private val inputShape: LongArray,
    private val optimizationLevel: OrtSession.SessionOptions.OptLevel =
        OrtSession.SessionOptions.OptLevel.ALL_OPT
) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var session: OrtSession? = null
    private var inputName: String = ""
    private var outputName: String = ""
    private var outputShape: IntArray = IntArray(0)

    val loaded: Boolean get() = session != null

    /**
     * 输出张量形状（load 之后、或至少跑过一次推理之后可用）。
     *
     * 识别层靠它判定 YOLOv5 / YOLOv8 布局 —— **形状是唯一可靠的事实**，
     * 不看模型文件名、不看版本字符串，换权重时不需要改一行代码。
     *
     * 形状有两个来源，[load] 时读会话元信息，[run] 时用实际输出张量覆盖。
     * 后者是兜底也是权威：元信息在某些 ORT 版本上取不到（见 [readOutputShape]），
     * 而推理结果的形状永远是准的。
     */
    val lastOutputShape: IntArray get() = outputShape

    val elementCount: Long get() = inputShape.fold(1L) { acc, v -> acc * v }

    @Synchronized
    fun load() {
        if (session != null) return
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(optimizationLevel)
        }
        val created = env.createSession(modelPath, options)
        inputName = created.inputNames.iterator().next()
        outputName = created.outputNames.iterator().next()
        outputShape = readOutputShape(created, outputName)
        session = created
        Log.i(
            LogTags.RECOGNIZER,
            "模型就绪 $modelPath（${File(modelPath).length()} 字节）" +
                " 输入 $inputName:${inputShape.toList()}" +
                " 输出 $outputName:${outputShape.toList()} 线程 $threads"
        )
        if (outputShape.isEmpty()) {
            // 不致命：run() 会拿实际输出张量补上。但必须喊一声，
            // 否则「形状取不到」会一直静默，下一次改动又撞同一堵墙。
            Log.w(LogTags.RECOGNIZER, "会话未报告输出形状，等首次推理从输出张量里取")
        }
    }

    /**
     * 跑一次推理。
     * @param chw 已按 NCHW 排好序、已归一化的浮点数组，长度必须等于输入元素总数
     */
    @Synchronized
    fun run(chw: FloatArray): FloatArray {
        val active = session ?: error("ONNX 模型尚未加载：$modelPath")
        val expected = elementCount
        require(chw.size.toLong() == expected) {
            "输入长度不符：期望 $expected，实际 ${chw.size}"
        }
        val buffer = ByteBuffer.allocateDirect(chw.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(chw)
        buffer.rewind()

        val tensor = OnnxTensor.createTensor(env, buffer, inputShape)
        try {
            val result = active.run(mapOf(inputName to tensor))
            try {
                val out = result.get(0) as OnnxTensor
                // 以**实际输出张量**为准刷新形状：会话元信息可能压根没给
                // （动态轴、或包装层没解开），张量形状永远准。
                outputShape = out.info.shape.map { it.toInt() }.toIntArray()
                val floats = out.floatBuffer
                val data = FloatArray(floats.remaining())
                floats.get(data)
                return data
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * 从会话元信息里取输出形状。
     *
     * **这里曾经是一个只在真机上才暴露的 bug**：ORT 1.16 起 `getOutputInfo()` 返回
     * `Map<String, NodeInfo>`，而 [NodeInfo] 只是名字 + [ValueInfo] 的一层包装，
     * 真正的类型信息在它的 `info` 属性上（`TensorInfo` / `SequenceInfo` / `MapInfo`）。
     * 老代码直接写 `info is TensorInfo` —— **永远为 false**，编译通过、单测也碰不到
     * （JVM 侧没有 native 库），运行期静默拿到空形状，于是每一次推理都报
     * 「模型输出形状未知，load() 未成功？」，而会话其实建得好好的。
     *
     * 所以这里先拆包装，再按 [ValueInfo] 判断；两种形态都接受，不赌某个 ORT 版本的行为。
     */
    private fun readOutputShape(session: OrtSession, name: String): IntArray {
        val info = runCatching { session.outputInfo }.getOrNull() ?: return IntArray(0)
        // 显式声明为 Any?：NodeInfo 与 TensorInfo 之间没有继承关系，
        // 直接在 NodeInfo 类型上写 `is TensorInfo` 编译器会认为分支不可能成立。
        val node: Any? = info[name]
        val value: ValueInfo? = when (node) {
            is NodeInfo -> node.info
            is ValueInfo -> node
            else -> null
        }
        val tensor = value as? TensorInfo ?: return IntArray(0)
        return tensor.shape.map { it.toInt() }.toIntArray()
    }
}
