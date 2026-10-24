package com.exarry.arvtubelink.mapping

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs

/**
 * 映射层：1€ 滤波器（1€ Filter，Casiez et al. 2012，官方文档 https://gery.casiez.net/1euro/），
 * 替换旧 EMA 平滑。特性：静止时用低截止频率强平滑抑制抖动；快速运动时截止频率自适应抬高，
 * 保持低延迟不拖影。
 *
 * ## 官方公式（严格照搬，勿自行改动）
 *  - 低通滤波：hatX = alpha * x + (1 - alpha) * hatX_prev，首帧直传初始化；
 *  - 低通系数：alpha(cutoff) = 1 / (1 + tau / te)，其中 tau = 1 / (2 * PI * cutoff)，
 *    te = 相邻两帧的时间间隔（秒）；
 *  - 速度估计：dx = (x_i - x_prev) / te，先经固定截止频率 [dCutoff] 低通得到 dx_hat；
 *  - 自适应截止频率：cutoff = minCutoff + beta * abs(dx_hat)。
 *
 * 参数：[minCutoff]（默认 1.0）越小静止时越平滑；[beta]（默认 0.007）越大快速运动越跟手；
 * [dCutoff] 为速度估计的低通截止频率，官方默认 1.0。
 *
 * 开关：[setOneEuroFilterEnabled] 传 false 退回无平滑模式（原始值直传，不更新滤波状态）。
 *
 * ## 时间间隔（te）
 *  提供显式传入 deltaTimeSec 的重载；不传的重载按调用时刻自动测量帧间隔，
 *  供 FaceRenderer 逐帧调用 smoothAll 的现有链路无需改动即生效。
 *
 * ## 线程模型
 *  smooth/smoothAll 在 GL 渲染线程逐帧调用；reset/开关/参数由 UI 线程写入。
 *  与旧 EMA 实现一致：跨线程共享状态为 @Volatile 标量或 ConcurrentHashMap，无锁。
 */
class Smoother(
    /** 速度为零时的截止频率（Hz）。 */
    @Volatile var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    /** 速度 -> 截止频率的增益系数。 */
    @Volatile var beta: Float = DEFAULT_BETA,
    /** 速度估计（导数）低通的截止频率（Hz）。 */
    @Volatile var dCutoff: Float = DEFAULT_D_CUTOFF
) {

    /** 1€ 滤波总开关；private set，只能经 [setOneEuroFilterEnabled] 切换。 */
    @Volatile
    var oneEuroFilterEnabled = true
        private set

    /**
     * 1€ 滤波开关。关闭后为无平滑模式（直传）；重新开启时清空滤波历史，
     * 避免关闭期间残留的旧状态拖拽新序列。
     */
    fun setOneEuroFilterEnabled(enabled: Boolean) {
        if (oneEuroFilterEnabled == enabled) return
        oneEuroFilterEnabled = enabled
        if (enabled) reset()
    }

    /** 对单个参数做一次 1€ 滤波，帧间隔由内部按上次调用时刻自动测量。首帧直传。 */
    fun smooth(paramName: String, value: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, nextMeasuredTe())
    }

    /** 同 [smooth]，显式传入相邻两帧的时间间隔（秒）。 */
    fun smooth(paramName: String, value: Float, deltaTimeSec: Float): Float {
        if (!oneEuroFilterEnabled) return value
        return filterOne(paramName, value, deltaTimeSec.coerceAtLeast(MIN_TE))
    }

    /**
     * 对整帧参数做 1€ 滤波，返回新 Map（不修改入参）。关闭时原样直传。
     *
     * 眼部通道（[FILTER_EXEMPT_PARAMS]）豁免滤波、原值直传：300ms 级眨眼脉冲会被
     * minCutoff=1.0 / beta=0.007 的自适应低通削平（「1€ 开启眨不动」，豁免后恢复），
     * 数据来源：raw_probe.txt 诊断（SDK 侧 Blink 连续满幅、脉冲本就干净）。
     */
    fun smoothAll(params: Map<String, Float>): Map<String, Float> {
        if (params.isEmpty() || !oneEuroFilterEnabled) return params
        val te = nextMeasuredTe()
        return params.mapValues { (name, value) ->
            if (name in FILTER_EXEMPT_PARAMS) value else filterOne(name, value, te)
        }
    }

    /** 清空历史（追踪重启/重新校准/重新开启滤波时调用，避免旧值拖拽新序列）。 */
    fun reset() {
        filters.clear()
        lastCallSec = Float.NaN
    }

    // ===================== 1€ 滤波核心（官方算法） =====================

    /** 每参数滤波状态：信号低通 + 速度低通，与官方 OneEuroFilter 结构一致。 */
    private class OneEuroFilter {
        val x = LowPassFilter()
        val dx = LowPassFilter()
    }

    /**
     * 低通滤波器（官方 LowPassFilter）：hatX 为滤波输出，lastRaw 为上一帧原始值；
     * 首帧（hatX 未初始化）直传。
     */
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

    /** 上次无显式 deltaTime 调用的时刻（秒），NaN 表示尚无历史。 */
    @Volatile
    private var lastCallSec = Float.NaN

    private fun filterOne(paramName: String, value: Float, te: Float): Float {
        val state = filters.getOrPut(paramName) { OneEuroFilter() }
        // 速度估计：dx = (x_i - x_prev) / te（首帧无上一帧原始值，官方按 0 起步）
        val freq = 1f / te
        val dValue = if (state.x.hasLastRaw()) (value - state.x.lastRawValue()) * freq else 0f
        // 速度先经 dCutoff 低通，再按速度自适应抬高截止频率
        val edValue = state.dx.filter(dValue, alpha(dCutoff, te))
        val cutoff = minCutoff + beta * abs(edValue)
        return state.x.filter(value, alpha(cutoff, te))
    }

    /** 官方 alpha 公式：tau = 1/(2*PI*cutoff)，alpha = 1/(1 + tau/te)。Double 计算防精度损失。 */
    private fun alpha(cutoff: Float, te: Float): Float {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return (1.0 / (1.0 + tau / te)).toFloat()
    }

    /** 测量自上次调用以来的帧间隔（秒）；首帧按官方默认 30Hz 起步。 */
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

        /**
         * 滤波豁免通道（VTS 参数名）：这些通道跳过 1€ 滤波、原值直传。
         * 来源：raw_probe.txt 诊断——眨眼/眯眼是 300ms 级短脉冲，
         * 1€ 低通（minCutoff=1.0 / beta=0.007）会把脉冲削平导致「眨不动」；
         * SDK 原始信号本身干净（Blink 静息基准 ~0.02、无抖动），无需平滑。
         * 豁免通道不进 [filters] 表、无状态，reset 对其无操作。
         */
        val FILTER_EXEMPT_PARAMS = setOf(
            "EyeOpenLeft", "EyeOpenRight", "EyeSmileLeft", "EyeSmileRight"
        )

        /** 首帧/无历史时的默认帧间隔（官方实现默认按 30Hz 起步）。 */
        private const val DEFAULT_TE = 1f / 30f

        /** te 数值下限：防时钟回拨/同帧重复调用导致除零。 */
        private const val MIN_TE = 1e-4f

        private const val NANOS_TO_SEC = 1f / 1_000_000_000f
    }
}
