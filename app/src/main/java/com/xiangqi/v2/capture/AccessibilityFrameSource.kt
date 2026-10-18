package com.xiangqi.v2.capture

import android.graphics.Bitmap
import android.util.Log
import com.xiangqi.v2.infra.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无障碍截图来源。
 *
 * 为什么选它而不是 MediaProjection：
 *  - 无障碍只需用户在设置里开一次，**重启应用不用重新授权**；MediaProjection 每次启动都要弹窗；
 *  - `takeScreenshot()`（API 30+）走系统合成，自身悬浮窗也会被拍进去，这一点和投屏一样；
 *  - 部分机型的投屏镜像拿到的是全黑帧，无障碍反而正常。
 *
 * 代价是 API 30 起才有、且有系统级节流（连续高频调用会返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`），
 * 所以扫描间隔默认 700ms，见 [com.xiangqi.v2.config.SettingsStore.scanIntervalMs]。
 *
 * [provider] 用 lambda 传入而不是直接传实例：无障碍服务的生命周期由系统管，
 * 用户随时可能在设置里关掉，每次抓帧都要重新取一次当前实例。
 */
class AccessibilityFrameSource(
    private val provider: () -> AccessibilityScreenshotProvider?,
    private val tag: String = LogTags.CAPTURE
) : FrameSource {

    override val name: String = "accessibility"

    private var failureCount = 0

    override val available: Boolean get() = provider() != null

    override suspend fun grab(timeoutMs: Long): Bitmap? {
        val active = provider()
        if (active == null) {
            logOccasionally("无障碍服务未开启")
            return null
        }
        // takeScreenshot 内部是阻塞等回调，必须离开主线程
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { active.takeScreenshot(timeoutMs) }
                .onFailure { Log.w(tag, "无障碍截图异常", it) }
                .getOrNull()
        }
        if (bitmap == null) {
            logOccasionally("无障碍截图未返回结果")
        } else {
            failureCount = 0
        }
        return bitmap
    }

    /** 前几次每次都报，之后每 50 次报一条 —— 不刷屏，但也不至于把问题埋掉。 */
    private fun logOccasionally(message: String) {
        failureCount++
        if (failureCount <= 3 || failureCount % 50 == 0) {
            Log.w(tag, "$message（已 $failureCount 次）")
        }
    }
}
