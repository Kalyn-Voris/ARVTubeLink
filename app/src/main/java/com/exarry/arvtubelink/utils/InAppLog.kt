package com.exarry.arvtubelink.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** 进程内日志 500 条环形缓冲 + 全进程日志级别闸门 */
object InAppLog {

    private const val MAX_LINES = 500

    private val lines = ConcurrentLinkedQueue<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    @Volatile
    private var debugEnabled = false

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

    /** 不受 setDebugEnabled 影响 */
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

    /** 版本号每条 +1，供 UI 增量刷新 */
    private fun append(level: String, tag: String, msg: String) {
        lines.add("${timeFmt.format(Date())} $level/$tag: $msg")
        while (lines.size > MAX_LINES) lines.poll()
        _version.value++
    }
}
