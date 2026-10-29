package com.xiangqi.v2.infra

import android.content.Context
import android.net.Uri
import com.xiangqi.v2.config.EngineConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * NNUE 权重的落地位置与导入。
 *
 * ## 为什么这个文件要单独管
 * `pikafish.nnue`（约 50.7MB）的条款是「仅限个人非商业使用」，与引擎代码的 GPL 不同源，
 * 不能随 APK 对外分发。于是它有两个入口：
 *  - 开发包：`fetch_assets.py` 下到 `assets/`，[AssetStore] 解压到
 *    `filesDir/engine/pikafish.nnue`；
 *  - 分发包：APK 里根本没有这个文件，用户从皮卡鱼官方取一份，经系统文件选择器导入，
 *    也落在同一个路径。
 *
 * 两条路汇到同一个文件，引擎侧就不必知道权重是怎么来的 —— [EngineLocator] 只看
 * 「这个路径上有没有文件」。
 *
 * ## 为什么导入要校验
 * 选错文件的代价不是「导入失败」，而是**引擎起来了、加载权重时崩**，
 * 现象是一句 `CRITICAL ERROR` 加进程退出，用户看不出跟刚才那次选择有关系。
 * 所以宁可在导入这一步就拒掉：体积下限挡误选图片/文档，文件头挡别的引擎的权重。
 */
class WeightsStore(
    private val context: Context,
    private val config: EngineConfig = EngineConfig()
) {

    /** 引擎实际加载的权重文件。 */
    val file: File get() = File(context.filesDir, "$DIR/${config.assetWeights}")

    /** 这个路径上是否已经有一份够格的权重。只量体积，内容校验在导入时做过。 */
    fun ready(): Boolean = file.length() >= WeightsRules.MIN_BYTES

    fun sizeBytes(): Long = file.length()

    /**
     * 把用户选中的文档复制成权重文件。
     *
     * 先写 `.import` 临时文件再改名：中途被杀不会留下半截文件被当成「已导入」，
     * 而半截权重正是最难查的一类故障 —— 引擎能起，加载时崩。
     *
     * @return null 表示成功；否则是能直接摆到界面上的原因
     */
    fun importFrom(uri: Uri): String? {
        val dest = file
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${config.assetWeights}.import")
        temp.delete()

        val finished = try {
            val input = context.contentResolver.openInputStream(uri) ?: return "打不开这个文件"
            input.use { copy(it, temp) }
        } catch (e: Exception) {
            temp.delete()
            return "读取失败：${e.message ?: e.javaClass.simpleName}"
        }
        if (!finished) {
            temp.delete()
            return WeightsRules.TOO_BIG
        }

        val problem = WeightsRules.check(temp.length(), headOf(temp))
        if (problem != null) {
            temp.delete()
            return problem
        }

        // 先删旧文件再改名：Java 的 renameTo 在目标已存在时**不保证**成功，
        // 而「替换权重」正是这个函数最常见的用法。删掉后改名在同目录内是原子的。
        dest.delete()
        if (!temp.renameTo(dest)) {
            // 退路：跨文件系统或某些 ROM 的实现差异会走到这里
            runCatching { temp.copyTo(dest, overwrite = true) }
            temp.delete()
        }
        return null
    }

    /** @return false 表示中途超过上限，已停止复制（调用方负责删临时文件） */
    private fun copy(input: InputStream, out: File): Boolean {
        FileOutputStream(out).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > WeightsRules.MAX_BYTES) return false
                output.write(buffer, 0, n)
            }
        }
        return true
    }

    /** 读文件头几个字节；读不满就用 0 补齐，交给 [WeightsRules.check] 判长度错误。 */
    private fun headOf(file: File): ByteArray {
        val head = ByteArray(WeightsRules.MAGIC.size)
        runCatching {
            file.inputStream().use { input ->
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
        }
        return head
    }

    companion object {
        /** 与 [EngineLocator] 解压 assets 时用的相对路径保持一致 —— 这是两条路的交点。 */
        const val DIR = "engine"

        private const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * 权重文件的判据。纯函数，JVM 单测直接钉。
 *
 * 判据只有两条，都是「宁可错拒」的方向：选错文件的后果（引擎崩）比拒一个可疑文件重得多。
 */
object WeightsRules {

    /** 已知权重约 50.7MB。8MB 是「小得不像权重」的下限，用来挡住误选图片、文档。 */
    const val MIN_BYTES = 8L * 1024 * 1024

    /** 上限只防误选视频把存储写满，不是格式约束。 */
    const val MAX_BYTES = 200L * 1024 * 1024

    const val TOO_BIG = "文件超过 200MB，不像权重"

    /**
     * 皮卡鱼的 `.nnue` 是 zstd 压缩帧，头 4 字节固定为 `28 B5 2F FD`。
     *
     * 只判格式不验内容：完整哈希要在 50MB 上跑一遍，而它的收益仅仅是「权重的版本对不对」——
     * 版本不对时引擎自己会报 `info string`，那条信息比哈希更有用，而且这里根本
     * 没法知道引擎认哪一版（见 [looksLikeWeightMismatch]）。
     */
    val MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /** @return null 表示通过；否则是给用户看的原因 */
    fun check(length: Long, head: ByteArray): String? {
        if (length < MIN_BYTES) return "文件只有 ${mb(length)}，NNUE 权重至少 8MB"
        if (length > MAX_BYTES) return TOO_BIG
        for (i in MAGIC.indices) {
            if (i >= head.size || head[i] != MAGIC[i]) {
                return "这不是 pikafish 的 NNUE 权重（文件头不是 zstd 帧）"
            }
        }
        return null
    }

    /**
     * 引擎输出里是否出现「网络文件加载失败」的痕迹。
     *
     * 皮卡鱼加载权重失败时打的是这两句，然后自杀：
     *   info string ERROR: Network evaluation parameters compatible with the engine must be available.
     *   info string ERROR: The network file ... was not loaded successfully.
     *
     * 判据取 `network` / `evalfile` 两个词（不分大小写）：够具体，不至于把 CPU 不支持、
     * 路径不存在这类别的原因错判成权重问题 —— 误判的代价是丢掉本来能用的权重去降级。
     */
    fun looksLikeWeightMismatch(lines: List<String>): Boolean = lines.any { line ->
        val lower = line.lowercase(Locale.US)
        "network" in lower || "evalfile" in lower
    }

    /**
     * 十进制口径（1MB = 1,000,000 字节），跟 README、APK 体积、皮卡鱼官网的说法一致。
     * 用 1048576 会把 50,706,378 字节显示成 48.4MB，跟用户下载时看到的 50.7MB 对不上。
     */
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1fMB", bytes / 1_000_000.0)
}
