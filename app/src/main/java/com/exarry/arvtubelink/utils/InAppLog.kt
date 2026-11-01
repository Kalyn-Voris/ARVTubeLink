package com.exarry.arvtubelink.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * App 内嵌日志环形缓冲（无 USB 时在手机上看日志）+ 全进程日志级别的唯一闸门。
 *
 * 用法：任意线程调用 [d]/[i]/[w]/[e]，与 android.util.Log 同签名；
 *      同时写 Logcat（有 USB 时行为不变）与内存队列（[ui.LogScreen] 的 LogContent 展示）。
 *
 * 级别策略（默认只留错误）：
 *  - [e] 无条件输出——错误必须在任何开关状态下可见；同时是"必需要被看见"的低频事件
 *    （如用户主动触发的校准成功）借用的 always-on 通道，本条不一定语义上是错误；
 *  - [w]/[i]/[d] 受进程级总开关 [setDebugEnabled] 控制，默认关闭。关闭时**提前返回**，
 *    既不写 Logcat 也不进内存面板（不是"写了再过滤"，内存里不会留痕）。
 *  - 开关真源是设置页「详细日志」（SharedPreferences: enable_debug_logs），由
 *    MainActivity 在 onCreate 最早期同步进来，之后设置页每次切换同步一次。
 *
 * 热路径（GL 线程逐帧日志）：先读 [isDebugEnabled]（一次 volatile 读）再决定要不要拼
 * 日志字符串，避免开关关闭时仍为丢弃的日志做 toString / String.format 分配。
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

    /**
     * 详细日志总开关（进程级，默认 false = 只放行 ERROR）。
     * 由 MainActivity 依据 SharedPreferences（Key: enable_debug_logs）写入；
     * 本类不读 SharedPreferences（保持工具类无 Android Context 依赖）。
     */
    @Volatile
    private var debugEnabled = false

    /** 当前是否放行非 ERROR 级别。热路径可用它先短路，避免为已丢弃的日志拼字符串。 */
    val isDebugEnabled: Boolean get() = debugEnabled

    /**
     * 设置详细日志开关。false 时拦截所有低于 ERROR 级别的输出
     * （WARN/INFO/DEBUG 一律不出 Logcat、不进内存面板）。
     */
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

    /** 错误级：无条件输出，不受 [setDebugEnabled] 影响。 */
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
