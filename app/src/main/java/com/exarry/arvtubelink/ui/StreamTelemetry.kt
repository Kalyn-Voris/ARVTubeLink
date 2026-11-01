package com.exarry.arvtubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.exarry.arvtubelink.ar.FaceRenderer
import kotlinx.coroutines.delay

/** 采样窗口长度（点）：每秒一点 = 最近 30 秒。 */
internal const val TELEMETRY_WINDOW = 30

/** 采样间隔（ms）：必须与 [TELEMETRY_WINDOW] 配合，1s × 30 = 30s。 */
private const val TELEMETRY_SAMPLE_MS = 1000L

/**
 * 实时发送频率环形缓冲（v1.2.6 首页仪表盘/折线图数据源）。
 *
 * ## 为什么用 FloatArray 而不是 List<Float>
 *
 * 折线图每帧重绘，若用 `List` + `removeAt(0)` 会变成 O(n) 搬移 + 每帧新列表；
 * 30 点虽小，但配合 Canvas 重绘会稳定产生 GC 压力（设计案明确要求"Path 复用以防
 * GC 抖动"）。这里固定容量数组 + 头指针，写入 O(1)、快照一次分配。
 *
 * ## 为什么存 NaN 而不是 0
 *
 * 追踪刚启动时只有 1~2 个真实样本，其余槽位若填 0 会让折线从 0 猛跳上来，
 * 看起来像"掉帧"。填 NaN 后绘制端跳过无效点，折线从左侧逐步"生长"出来。
 *
 * 线程：仅在 Compose 主线程读写（[rememberTelemetry] 的 LaunchedEffect），无并发问题。
 */
internal class FpsWindow(private val capacity: Int = TELEMETRY_WINDOW) {
    private val data = FloatArray(capacity) { Float.NaN }
    private var head = 0
    private var size = 0

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** 有效样本数（非 NaN）。 */
    val validCount: Int get() = size

    /**
     * 按时间顺序导出（最旧 → 最新）。未填满时只返回已有部分，避免左侧一大段 NaN。
     *
     * 每次采样调用一次（1Hz），30 个 Float 的分配可忽略。
     */
    fun snapshot(): FloatArray {
        if (size == 0) return FloatArray(0)
        return FloatArray(size) { i ->
            val idx = (head - size + i + capacity * 2) % capacity
            data[idx]
        }
    }

    /** 最近一个有效值（用于仪表盘），无样本时返回 null。 */
    fun latest(): Float? = if (size == 0) null else data[(head - 1 + capacity) % capacity]

    fun clear() {
        data.fill(Float.NaN)
        head = 0
        size = 0
    }
}

/**
 * 遥测采样 + 状态发布的唯一入口。
 *
 * ## 为什么采样与 version 递增在同一个循环里
 *
 * 拆成两个 `LaunchedEffect` 各自 `delay(1000)` 会随时间漂移（协程调度抖动累积），
 * 出现"version 已推进但本帧还没采样"的窗口 → 图表偶尔读到上一轮数据。
 * 合并后一次循环内先采样、再递增 version，保证每次重组读到的都是刚写入的点。
 *
 * @param renderer 数据源（只读调用，不修改渲染器状态）
 * @param tracking 追踪是否进行中；false 时停止采样（窗口内容保留，供停止后回看）
 */
@Composable
internal fun rememberTelemetryState(
    renderer: FaceRenderer,
    tracking: Boolean,
): TelemetryState {
    val window = remember { FpsWindow() }
    var version by remember { mutableStateOf(0L) }

    LaunchedEffect(tracking) {
        if (!tracking) return@LaunchedEffect
        // 追踪重启：清空上一会话的曲线，并清掉计数器残留值，保证第一个点从 0 开始计
        window.clear()
        renderer.readAndResetSentFrames()
        version++ // 立刻发布一次"已清空"，让图表先回到空态而不是继续显示旧曲线
        while (true) {
            delay(TELEMETRY_SAMPLE_MS)
            val frames = renderer.readAndResetSentFrames()
            // 帧数 / 间隔秒数 = 实测 Hz。用固定 1s 而非实测 elapsed：delay 抖动通常在
            // 个位毫秒，对 30 点曲线的视觉影响可忽略，换来零时间戳分配。
            window.push(frames.toFloat() / (TELEMETRY_SAMPLE_MS / 1000f))
            version++
        }
    }
    // version 变化 → 重新取快照。以 (version, tracking) 为 key，切 Tab/重进组合也能拿到最新值。
    return remember(version, tracking) {
        TelemetryState(
            samples = window.snapshot(),
            latest = window.latest(),
            valid = window.validCount,
        )
    }
}

/** 图表一次性读取的数据快照（不可变，可安全跨重组）。 */
data class TelemetryState(
    val samples: FloatArray,
    val latest: Float?,
    val valid: Int,
) {
    // FloatArray 的 data class 需要手写 equals/hashCode（数组引用比较会让
    // remember(key) 永远判定为"变了"，反而每帧重组）。这里按内容比较。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelemetryState) return false
        return latest == other.latest && valid == other.valid &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + (latest?.hashCode() ?: 0)
        result = 31 * result + valid
        return result
    }
}
