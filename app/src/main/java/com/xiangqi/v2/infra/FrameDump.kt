package com.xiangqi.v2.infra

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别失败的分类。文件名的英文部分取 [fileTag]，日志/面板用 [label]。 */
enum class FailureReason(val fileTag: String, val label: String) {
    NO_BOARD("no_board", "找不到棋盘"),
    TOO_FEW_PIECES("few_pieces", "棋子太少"),
    POOR_GEOMETRY("geometry", "格线对不上"),
    ILLEGAL_BOARD("illegal", "盘面不合法")
}

/**
 * 识别失败时把**这一帧原图**存进相册 —— 用来解决「用户描述和现场对不上」的问题。
 *
 * ## 为什么必须有
 * 同一个现象（「这张棋盘识别不了」）在这条链路上有六种完全不同的成因：模型没检出棋盘、
 * 棋盘框宽高比被滤掉、棋子少于 3 颗、周期拟合残差过大、吸附失败、盘面不合法。
 * 面板上的一句话分不清是哪种，而用户拿到的截图往往是**相册里压缩过的**（872×1920），
 * 与真机抓帧（1280×2816）不是同一张图 —— 离线怎么跑都是对的，问题却复现不了。
 *
 * 拿到真机原帧，就能用 `tools/_diag_redskin_geom.py` 一模一样地复算几何链路，
 * 一次定位。所以存图存的是**原尺寸**，不缩：缩了就等于又换了一张图。
 *
 * ## 为什么不加权限
 * 走 MediaStore 写 `Pictures/XiangqiDiag/`，Android 10+ 不需要任何存储权限。
 * 更老的系统（本项目 minSdk 26）拿不到免权限的公共路径，直接放弃存档 —— 存进
 * 应用私有目录反而让用户找不到，等于没存。
 */
class FrameDump(
    context: Context,
    private val policy: DumpPolicy = DumpPolicy(),
    private val folder: String = "XiangqiDiag",
    private val tag: String = LogTags.RECOGNIZER
) {

    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 相册里能否被用户直接看到。老系统上为 false，调用方据此不要提示「已存相册」。 */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 存一帧。**立即返回**，真正写盘在 IO 线程。
     *
     * @return 是否已经受理（受 [DumpPolicy] 节流）。调用方只应在返回 true 时
     *   提示用户「诊断图已存相册」—— 提示了却没存，比不说更糟。
     */
    fun submit(frame: Bitmap, reason: FailureReason): Boolean {
        if (!available) return false
        if (frame.width <= 0 || frame.height <= 0) return false
        if (!policy.tryTake(System.currentTimeMillis())) return false

        val copy = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy == null) {
            Log.w(tag, "失败帧存档：位图复制失败")
            return false
        }
        val name = fileName(reason)
        scope.launch {
            try {
                val uri = write(copy, name)
                Log.i(
                    tag,
                    "失败帧已存相册（第 ${policy.savedCount} 张，${reason.label}）：$uri " +
                        "尺寸 ${copy.width}x${copy.height}"
                )
            } catch (t: Throwable) {
                Log.w(tag, "失败帧存档失败", t)
            } finally {
                copy.recycle()
            }
        }
        return true
    }

    private fun fileName(reason: FailureReason): String {
        // 只保留 ASCII：中文文件名在某些文件管理器/微信传输里会变乱码，
        // 用户回头就找不到是哪张
        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        return "xq_${stamp}_${reason.fileTag}.jpg"
    }

    private fun write(bitmap: Bitmap, name: String): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + folder
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 拒绝插入")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                // 90 而不是 100：诊断看布局和字迹足够，体积小一半，用户发图也快
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: error("拿不到输出流")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
