package com.exarry.arvtubelink.mapping

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs

/** 1€ 滤波：低通 + 速度自适应截止频率，cutoff = minCutoff + beta * abs(dx_hat) */
class Smoother(
    /** 速度为零时的截止频率（Hz） */
    @Volatile var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    /** 速度到截止频率的增益系数 */
    @Volatile var beta: Float = DEFAULT_BETA,
    /** 速度估计低通的截止频率（Hz） */
    @Volatile var dCutoff: Float = DEFAULT_D_CUTOFF
) {

    /** 总开关，仅可经 setOneEuroFilterEnabled 切换 */
    @Volatile
    var oneEuroFilterEnabled = true
        private set

    /** 关闭时原值直传；重新开启清空滤波历史 */
    fun setOneEuroFilterEnabled(enabled: Boolean) {
        if (oneEuroFilterEnabled == enabled) return
        oneEuroFilterEnabled = enabled
        if (enabled) reset()
    }

    /** 帧间隔按上次调用时刻自动测量，首帧直传 */
    fun smooth(paramName: String, value: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, nextMeasuredTe())
    }

    /** 显式传入相邻两帧时间间隔（秒） */
    fun smooth(paramName: String, value: Float, deltaTimeSec: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, deltaTimeSec.coerceAtLeast(MIN_TE))
    }

    /** 整帧滤波，返回新 Map；FILTER_EXEMPT_PARAMS 内的通道原值直传 */
    fun smoothAll(params: Map<String, Float>): Map<String, Float> {
        if (params.isEmpty() || !oneEuroFilterEnabled) return params
        val te = nextMeasuredTe()
        return params.mapValues { (name, value) ->
            if (name in FILTER_EXEMPT_PARAMS) value else filterOne(name, value, te)
        }
    }

    /** 清空滤波历史与帧间隔 */
    fun reset() {
        filters.clear()
        lastCallSec = Float.NaN
    }

    /** 每参数滤波状态：信号低通 + 速度低通 */
    private class OneEuroFilter {
        val x = LowPassFilter()
        val dx = LowPassFilter()
    }

    /** 低通滤波：hatX 为输出，lastRaw 为上一帧原始值，首帧直传 */
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

    /** 上次无显式 deltaTime 调用的时刻（秒），NaN 表示尚无历史 */
    @Volatile
    private var lastCallSec = Float.NaN

    private fun filterOne(paramName: String, value: Float, te: Float): Float {
        val state = filters.getOrPut(paramName) { OneEuroFilter() }
        // 首帧无上一帧原始值，速度按 0 起步
        val freq = 1f / te
        val dValue = if (state.x.hasLastRaw()) (value - state.x.lastRawValue()) * freq else 0f
        // 速度先经 dCutoff 低通，再抬高截止频率
        val edValue = state.dx.filter(dValue, alpha(dCutoff, te))
        val cutoff = minCutoff + beta * abs(edValue)
        return state.x.filter(value, alpha(cutoff, te))
    }

    /** tau = 1/(2*PI*cutoff)，alpha = 1/(1 + tau/te)，Double 计算防精度损失 */
    private fun alpha(cutoff: Float, te: Float): Float {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return (1.0 / (1.0 + tau / te)).toFloat()
    }

    /** 首帧按 30Hz 起步 */
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

        /** 眼部通道跳过滤波直传：300ms 级眨眼脉冲会被低通削平 */
        val FILTER_EXEMPT_PARAMS = setOf(
            "EyeOpenLeft", "EyeOpenRight", "EyeSmileLeft", "EyeSmileRight"
        )

        /** 首帧/无历史时的默认帧间隔，按 30Hz */
        private const val DEFAULT_TE = 1f / 30f

        /** te 下限，防除零 */
        private const val MIN_TE = 1e-4f

        private const val NANOS_TO_SEC = 1f / 1_000_000_000f
    }
}
