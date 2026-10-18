package com.xiangqi.v2.capture

import android.graphics.Bitmap

/**
 * 截图来源。
 *
 * 采集方式有好几种（无障碍 takeScreenshot、MediaProjection 投屏、未来可能的 root/Shizuku、
 * 以及调试用的「读本地图片」），但上层只关心「给我一帧」。抽成接口之后：
 *  - 加一种采集方式 = 加一个实现，编排层一行不动；
 *  - 调试时可以塞一个「读本地图片」的假实现，不必真机跑。
 */
interface FrameSource {

    /** 用于日志与诊断的实现名。 */
    val name: String

    /**
     * 抓取一帧。
     * @param timeoutMs 本次抓取的等待上限
     * @return null 表示当前拿不到画面（调用方可据此触发自愈或提示用户）
     */
    suspend fun grab(timeoutMs: Long): Bitmap?

    /** 当前是否具备提供画面的条件（通道已建立、授权未失效）。 */
    val available: Boolean

    fun close() {}
}

/**
 * 无障碍截图能力的抽象。
 *
 * 这样定义是为了让采集层不必直接依赖 `AccessibilityService` 的具体类 ——
 * 采集层只认这个接口，谁实现都行（真机上是 CaptureAccessibilityService，测试里可以是假的）。
 */
fun interface AccessibilityScreenshotProvider {
    /** 阻塞直到拿到截图或超时；失败返回 null。 */
    fun takeScreenshot(timeoutMs: Long): Bitmap?
}
