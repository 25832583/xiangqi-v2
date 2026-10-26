package com.xiangqi.v2.infra

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * assets 资源落盘缓存。
 *
 * ONNX Runtime 与外部引擎进程都只认文件路径，读不了 assets，所以模型 / NNUE 权重 /
 * 引擎二进制必须解压到 filesDir。这里把「解压一次、之后复用」的逻辑收拢到一处，
 * 并做并发去重 —— 重构前这段逻辑每个调用点自己写一遍，多线程首启会重复解压大文件。
 */
class AssetStore(private val context: Context) {

    private val extracted = ConcurrentHashMap<String, File>()

    /**
     * 把 assets 里的 [assetName] 解压到 [dest]；已存在且非空则直接复用。
     *
     * @param executable 是否补上可执行位（引擎二进制需要）
     */
    fun extract(assetName: String, dest: File, executable: Boolean = false): File {
        extractInternal(assetName, dest)
        if (executable) runCatching { dest.setExecutable(true, false) }
        return dest
    }

    /** 解压到 filesDir 下的相对路径，省得每个调用点自己拼 File。 */
    fun extractTo(assetName: String, relativePath: String, executable: Boolean = false): File =
        extract(assetName, File(context.filesDir, relativePath), executable)

    /** 只查询已解压缓存，不触发解压。 */
    fun cached(assetName: String): File? = extracted[assetName]

    /** assets 里是否存在该文件。不落盘、不解压。 */
    fun has(assetName: String): Boolean =
        runCatching { context.assets.open(assetName).close() }.isSuccess

    private fun extractInternal(assetName: String, dest: File) {
        extracted[assetName]?.takeIf { it.exists() && it.length() > 0L }?.let { return }
        synchronized(this) {
            extracted[assetName]?.takeIf { it.exists() && it.length() > 0L }?.let { return }
            if (!dest.exists() || dest.length() == 0L) {
                dest.parentFile?.mkdirs()
                // 先写临时文件再改名，避免解压中途被杀留下半个文件被当成「已解压」
                val temp = File(dest.parentFile, "${dest.name}.tmp")
                val input = try {
                    context.assets.open(assetName)
                } catch (e: FileNotFoundException) {
                    // 缺模型是「克隆后忘了备料」的典型现象，不是运行时故障。
                    // 原始 FileNotFoundException 只带一句文件名，看不出该干什么，
                    // 而且它会被上层的 catch(Exception) 吞成「分析失败：null」。
                    throw MissingAssetException(assetName, e)
                }
                input.use { i ->
                    FileOutputStream(temp).use { output -> i.copyTo(output) }
                }
                if (dest.exists()) dest.delete()
                if (!temp.renameTo(dest)) {
                    temp.copyTo(dest, overwrite = true)
                    temp.delete()
                }
            }
            extracted[assetName] = dest
        }
    }
}

/**
 * assets 里缺文件。
 *
 * 单独一种异常而不是沿用 [FileNotFoundException]：这条要一路冒到界面上，
 * 文案里必须带「怎么补」，否则用户只会看到「分析失败」。
 */
class MissingAssetException(val assetName: String, cause: Throwable?) :
    IllegalStateException(
        "缺少 $assetName —— 在工程根目录运行 python tools/fetch_assets.py 补齐",
        cause
    )
