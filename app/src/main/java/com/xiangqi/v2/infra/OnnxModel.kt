package com.xiangqi.v2.infra

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
     * 输出张量形状（load 之后可用）。
     *
     * 识别层靠它判定 YOLOv5 / YOLOv8 布局 —— **形状是唯一可靠的事实**，
     * 不看模型文件名、不看版本字符串，换权重时不需要改一行代码。
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
        val info = created.outputInfo[outputName]
        outputShape = if (info is TensorInfo) info.shape.map { it.toInt() }.toIntArray() else IntArray(0)
        session = created
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
                val floats = (result.get(0) as OnnxTensor).floatBuffer
                val out = FloatArray(floats.remaining())
                floats.get(out)
                return out
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
}
