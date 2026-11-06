package com.exarry.arvtubelink.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** 进程内日志环形缓冲（最多 500 条）+ 全进程日志级别闸门 */
object InAppLog {

    private const val MAX_LINES = 500

    private val lines = ConcurrentLinkedQueue<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /** 进程级开关，由 MainActivity 从 enable_debug_logs 同步进来，本类不读 prefs */
    @Volatile
    private var debugEnabled = false

    /** 热路径先读它短路，避免为已丢弃日志拼字符串 */
    val isDebugEnabled: Boolean get() = debugEnabled

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun d(tag: String, msg: String) {
        if (!debugEnabled) return
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (!debugEnabled) return
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (!debugEnabled) return
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable) {
        if (!debugEnabled) return
        Log.w(tag, msg, t)
        val top = t.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
        append("W", tag, "$msg ${t.javaClass.simpleName}: ${t.message}$top")
    }

    /** 无条件输出，不受 setDebugEnabled 影响 */
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

    /** 每条 +1 供 UI 增量刷新；超 500 条丢最旧 */
    private fun append(level: String, tag: String, msg: String) {
        lines.add("${timeFmt.format(Date())} $level/$tag: $msg")
        while (lines.size > MAX_LINES) lines.poll()
        _version.value++
    }
}
