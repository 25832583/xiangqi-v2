package com.xiangqi.v2.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.xiangqi.v2.capture.AccessibilityScreenshotProvider
import com.xiangqi.v2.infra.LogTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通过无障碍服务抓屏。
 *
 * 同时它是 [AccessibilityScreenshotProvider] 的实现 —— 采集层只依赖那个接口，
 * 不直接依赖这个 Service 类，所以抓屏方式可以整体替换而不影响其它层。
 *
 * 除了抓屏它什么都不做（`onAccessibilityEvent` 空实现）：本应用不需要读界面控件，
 * 只要能拍屏。事件监听越少，系统给的开销和用户看到的「这个应用在盯你」的顾虑都越少。
 */
class CaptureAccessibilityService : AccessibilityService(), AccessibilityScreenshotProvider {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /** 当前是否已连上（主界面用它显示「已开启/未开启」）。 */
        val connected: Boolean get() = instance != null

        /** 不依赖静态实例的探测：服务没连上时 [instance] 为 null。 */
        fun provider(): AccessibilityScreenshotProvider? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(LogTags.SCREENSHOT, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        Log.i(LogTags.SCREENSHOT, "无障碍服务已断开")
        super.onDestroy()
    }

    override fun takeScreenshot(timeoutMs: Long): Bitmap? {
        // takeScreenshot(int, Executor, Callback) 是 API 30 才有的
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        result = screenshot.toSoftwareBitmap()
                        if (result == null) {
                            Log.w(LogTags.SCREENSHOT, "截图转换失败（HardwareBuffer 为空）")
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(LogTags.SCREENSHOT, "截图失败 code=$errorCode${hint(errorCode)}")
                        latch.countDown()
                    }
                }
            )
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(LogTags.SCREENSHOT, "截图超时 ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            Log.w(LogTags.SCREENSHOT, "截图异常", t)
        }
        return result
    }

    /** 错误码翻译成人话 —— 原始码对用户没有意义，对排查却极其关键。 */
    private fun hint(errorCode: Int): String = when (errorCode) {
        1 -> "（截图被系统节流，调用太频繁）"
        2 -> "（系统繁忙）"
        3 -> "（窗口内容不允许截图，可能有防截屏保护）"
        else -> ""
    }

    /**
     * HardwareBuffer → 软件 Bitmap。
     *
     * 必须 `copy(ARGB_8888)`：takeScreenshot 返回的是硬件位图，直接喂给 `getPixels`
     * 或 ONNX 的类型转换都会失败（硬件位图只在 GPU 上）。copy 之后还要 `close()`，
     * 否则每次抓帧泄漏一个 GraphicBuffer，几分钟就把显存耗尽。
     */
    private fun ScreenshotResult.toSoftwareBitmap(): Bitmap? = try {
        val hardware = hardwareBuffer
        val wrapped = Bitmap.wrapHardwareBuffer(hardware, colorSpace)
        val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        hardware.close()
        software
    } catch (t: Throwable) {
        Log.w(LogTags.SCREENSHOT, "HardwareBuffer 转换异常", t)
        null
    }
}
