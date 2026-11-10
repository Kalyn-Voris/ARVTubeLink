package com.exarry.arvtubelink.mapping

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs

/** 1€ 滤波：低通 + 速度自适应截止频率 */
class Smoother(
    @Volatile var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    @Volatile var beta: Float = DEFAULT_BETA,
    @Volatile var dCutoff: Float = DEFAULT_D_CUTOFF
) {

    @Volatile
    var oneEuroFilterEnabled = true
        private set

    fun setOneEuroFilterEnabled(enabled: Boolean) {
        if (oneEuroFilterEnabled == enabled) return
        oneEuroFilterEnabled = enabled
        if (enabled) reset()
    }

    fun smooth(paramName: String, value: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, nextMeasuredTe())
    }

    fun smooth(paramName: String, value: Float, deltaTimeSec: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, deltaTimeSec.coerceAtLeast(MIN_TE))
    }

    fun smoothAll(params: Map<String, Float>): Map<String, Float> {
        if (params.isEmpty() || !oneEuroFilterEnabled) return params
        val te = nextMeasuredTe()
        return params.mapValues { (name, value) ->
            if (name in FILTER_EXEMPT_PARAMS) value else filterOne(name, value, te)
        }
    }

    fun reset() {
        filters.clear()
        lastCallSec = Float.NaN
    }

    private class OneEuroFilter {
        val x = LowPassFilter()
        val dx = LowPassFilter()
    }

    private class LowPassFilter {
        private var hatX = Float.NaN
        private var lastRaw = Float.NaN

        fun filter(x: Float, alpha: Float): Float {
            val result = if (hatX.isNaN()) x else alpha * x + (1f - alpha) * hatX
            hatX = result
            lastRaw = x
            return result
        }

        fun hasLastRaw(): Boolean = !lastRaw.isNaN()

        fun lastRawValue(): Float = lastRaw
    }

    private val filters = ConcurrentHashMap<String, OneEuroFilter>()

    @Volatile
    private var lastCallSec = Float.NaN

    private fun filterOne(paramName: String, value: Float, te: Float): Float {
        val state = filters.getOrPut(paramName) { OneEuroFilter() }
        val freq = 1f / te
        val dValue = if (state.x.hasLastRaw()) (value - state.x.lastRawValue()) * freq else 0f
        val edValue = state.dx.filter(dValue, alpha(dCutoff, te))
        val cutoff = minCutoff + beta * abs(edValue)
        return state.x.filter(value, alpha(cutoff, te))
    }

    /** Double 计算防精度损失 */
    private fun alpha(cutoff: Float, te: Float): Float {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return (1.0 / (1.0 + tau / te)).toFloat()
    }

    private fun nextMeasuredTe(): Float {
        val nowSec = System.nanoTime() * NANOS_TO_SEC
        val last = lastCallSec
        lastCallSec = nowSec
        return if (last.isNaN()) DEFAULT_TE else (nowSec - last).coerceAtLeast(MIN_TE)
    }

    companion object {
        const val DEFAULT_MIN_CUTOFF = 1.0f
        const val DEFAULT_BETA = 0.007f
        const val DEFAULT_D_CUTOFF = 1.0f

        /** 眨眼脉冲 300ms 级，低通会削平 */
        val FILTER_EXEMPT_PARAMS = setOf(
            "EyeOpenLeft", "EyeOpenRight", "EyeSmileLeft", "EyeSmileRight"
        )

        private const val DEFAULT_TE = 1f / 30f

        private const val MIN_TE = 1e-4f

        private const val NANOS_TO_SEC = 1f / 1_000_000_000f
    }
}
