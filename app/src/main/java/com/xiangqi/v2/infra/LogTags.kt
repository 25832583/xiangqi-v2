package com.xiangqi.v2.infra

/**
 * 日志标签集中定义。
 *
 * 这些字符串是**排查现场问题的接口**：重构前后刻意保持一致，这样同一句
 * `adb logcat -s XiangqiPanel` 在老版本和新版本上都能用，方便逐条比对行为差异。
 */
object LogTags {
    /** 悬浮服务与编排：识别结果、质量门槛、分析状态 */
    const val PANEL = "XiangqiPanel"

    /** 采集：投屏/无障碍的帧回调、路径选择、自愈 */
    const val CAPTURE = "XiangqiCapture"

    /** 识别：找棋盘、单格分类回退 */
    const val RECOGNIZER = "XiangqiRecognizer"

    /** 引擎进程与 UCI 交互 */
    const val ENGINE = "XiangqiEngine"

    /** 参数加载 */
    const val CONFIG = "XiangqiConfig"

    /** 无障碍抓屏服务 */
    const val SCREENSHOT = "XiangqiScreenshot"
}
