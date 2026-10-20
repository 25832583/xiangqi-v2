package com.xiangqi.v2.infra

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同时写 logcat 和文件的诊断日志。
 *
 * ## 为什么需要这条旁路
 * vivo / iQOO 的「开发者选项 → 日志记录级别」设成「关闭」时，通过 adb 安装的应用
 * 调用的 `Log.*` 会被系统**静默丢弃** —— logcat 里一条都没有。而 ART 的 GC 日志
 * 照常输出，于是很容易误判成「应用没在跑」或「代码没走到」。这个机型上已经踩过两次，
 * 每次都要先怀疑一遍代码。
 *
 * 写文件不受那个开关影响。取法（debuggable 包）：
 *
 *     adb shell run-as com.xiangqi.v2 cat files/diag.log
 *
 * 只在需要留证的路径上调用，正常帧不写，避免每帧一次 IO。
 */
object DiagLog {

    private var file: File? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 超过这个长度就清空重来：诊断日志只需要最近几千行。 */
    private const val MAX_BYTES = 256 * 1024L

    fun init(dir: File) {
        val target = File(dir, "diag.log")
        if (target.length() > MAX_BYTES) runCatching { target.delete() }
        file = target
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append(tag, message)
    }

    @Synchronized
    private fun append(tag: String, message: String) {
        val target = file ?: return
        runCatching { target.appendText("${stamp.format(Date())} $tag: $message\n") }
    }
}
