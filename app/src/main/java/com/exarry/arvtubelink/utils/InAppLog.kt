package com.exarry.arvtubelink.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * App 内嵌日志环形缓冲（无 USB 时在手机上看日志）。
 *
 * 用法：任意线程调用 [d]/[i]/[w]/[e]，与 android.util.Log 同签名；
 *      同时写 Logcat（有 USB 时行为不变）与内存队列（[ui.LogScreen] 的 LogContent 展示）。
 *
 * 拆除方式：全局搜索 InAppLog 换回 Log，删掉本文件与 ui/LogScreen.kt，
 *           并移除 ui/AppRoot.kt 中的 TAB_LOG 分支（标签页、图标、内容分发三处）。
 *
 * 线程安全：ConcurrentLinkedQueue + volatile 版本号；GL/OkHttp/主线程均可直接调用。
 * 容量：最多 [MAX_LINES] 条，超出自动移除最旧日志。
 * UI 通知：[version] 状态流，每追加一条 +1（UI 监听它增量刷新）。
 */
object InAppLog {

    private const val MAX_LINES = 500

    private val lines = ConcurrentLinkedQueue<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append("E", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable) {
        Log.e(tag, msg, t)
        val top = t.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
        append("E", tag, "$msg ${t.javaClass.simpleName}: ${t.message}$top")
    }

    fun getAll(): List<String> = lines.toList()

    fun clear() {
        lines.clear()
        _version.value++
    }

    private fun append(level: String, tag: String, msg: String) {
        lines.add("${timeFmt.format(Date())} $level/$tag: $msg")
        while (lines.size > MAX_LINES) lines.poll()
        _version.value++
    }
}
