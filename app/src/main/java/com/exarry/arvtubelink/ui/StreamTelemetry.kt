package com.exarry.arvtubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.exarry.arvtubelink.ar.FaceRenderer
import kotlinx.coroutines.delay

internal const val TELEMETRY_WINDOW = 30

private const val TELEMETRY_SAMPLE_MS = 1000L

/** 固定容量 FloatArray + 头指针，写入 O(1)，空槽填 NaN */
internal class FpsWindow(private val capacity: Int = TELEMETRY_WINDOW) {
    private val data = FloatArray(capacity) { Float.NaN }
    private var head = 0
    private var size = 0

    fun push(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    val validCount: Int get() = size

    fun snapshot(): FloatArray {
        if (size == 0) return FloatArray(0)
        return FloatArray(size) { i ->
            val idx = (head - size + i + capacity * 2) % capacity
            data[idx]
        }
    }

    fun latest(): Float? = if (size == 0) null else data[(head - 1 + capacity) % capacity]

    fun clear() {
        data.fill(Float.NaN)
        head = 0
        size = 0
    }
}

@Composable
internal fun rememberTelemetryState(
    renderer: FaceRenderer,
    tracking: Boolean,
): TelemetryState {
    val window = remember { FpsWindow() }
    var version by remember { mutableStateOf(0L) }

    LaunchedEffect(tracking) {
        if (!tracking) return@LaunchedEffect
        window.clear()
        renderer.readAndResetSentFrames()
        version++
        while (true) {
            delay(TELEMETRY_SAMPLE_MS)
            val frames = renderer.readAndResetSentFrames()
            // 帧数 / 固定 1s 得 Hz，不记时间戳省分配
            window.push(frames.toFloat() / (TELEMETRY_SAMPLE_MS / 1000f))
            version++
        }
    }
    return remember(version, tracking) {
        TelemetryState(
            samples = window.snapshot(),
            latest = window.latest(),
            valid = window.validCount,
        )
    }
}

/** FloatArray 按内容比较 equals/hashCode */
data class TelemetryState(
    val samples: FloatArray,
    val latest: Float?,
    val valid: Int,
) {
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
