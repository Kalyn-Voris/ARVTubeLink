package com.exarry.arvtubelink.adapt

import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.network.Live2DParameterListResponse
import com.exarry.arvtubelink.network.VtsWsClient
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** 参数自适应状态机，MainActivity 以 mutableStateOf 镜像 */
sealed class AdaptationState {
    data object Idle : AdaptationState()

    /** phase 细分档位；index/total 从 1 起；skipped 为死通道/白名单外/超时数 */
    data class Running(
        val phase: Phase,
        val index: Int,
        val total: Int,
        val skipped: Int,
        val paramName: String
    ) : AdaptationState()

    data class Done(val modelID: String) : AdaptationState()
    data class Failed(val reason: String) : AdaptationState()

    enum class Phase { Injecting, Reading, Saving }
}

/** 注入已知值、差分读回 Live2D 参数，线性反推 IN→OUT 映射 */
class AdaptationController(
    private val vtsClient: VtsWsClient,
    private val storage: AdaptationStorage,
    private val onState: (AdaptationState) -> Unit = {},
    private val onOverridesReady: (MapperOverrides, modelID: String, modelName: String) -> Unit =
        { _, _, _ -> }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobRef = AtomicReference<Job?>(null)

    @Volatile
    private var runningModelId: String? = null

    /** 主线程调用；同 modelID 重入忽略 */
    fun start(modelID: String, modelName: String, isSwitch: Boolean) {
        val prev = jobRef.get()
        if (prev?.isActive == true) {
            if (runningModelId == modelID) {
                InAppLog.i(TAG, "自适应已在进行中（同 modelID=$modelID），忽略重入")
                return
            }
            InAppLog.i(TAG, "自适应进行中检测到新模型（$modelID），取消旧探测重启")
            prev.cancel()
        }
        runningModelId = modelID
        jobRef.set(scope.launch { run(modelID, modelName, isSwitch) })
    }

    fun cancel() {
        runningModelId = null
        jobRef.getAndSet(null)?.cancel()
        onState(AdaptationState.Idle)
    }

    /** 单参数失败只跳过，连续读回超时才整体 Failed */
    private suspend fun run(modelID: String, modelName: String, isSwitch: Boolean) {
        InAppLog.i(
            TAG,
            "自适应探测开始：model=$modelName id=$modelID${if (isSwitch) "（模型已变更）" else ""}"
        )
        val mouthSmileNeutrals = mutableListOf<Float>()
        val mouthOpenScales = mutableListOf<Float>()
        val eyeOpenScales = mutableListOf<Float>()
        var skipped = 0
        var consecutiveReadTimeouts = 0
        try {
            for ((i, spec) in PROBE_SPECS.withIndex()) {
                val outcome = withTimeoutOrNull(PARAM_TOTAL_TIMEOUT_MS) {
                    probeParameter(spec, i + 1, skipped)
                } ?: ParamOutcome.SegmentTimeout
                when (outcome) {
                    is ParamOutcome.Probed -> {
                        consecutiveReadTimeouts = 0
                        applyReads(spec, outcome.reads, mouthSmileNeutrals, mouthOpenScales,
                            eyeOpenScales)
                    }
                    ParamOutcome.DeadChannel -> {
                        consecutiveReadTimeouts = 0
                        skipped++
                        InAppLog.i(TAG, "死通道：${spec.vtsParam} 首探针差分过小，跳过")
                    }
                    ParamOutcome.Unsupported -> {
                        consecutiveReadTimeouts = 0
                        skipped++
                        InAppLog.i(TAG, "白名单外：${spec.vtsParam} 注入被拒，跳过")
                    }
                    ParamOutcome.ReadTimeout -> {
                        consecutiveReadTimeouts++
                        skipped++
                        InAppLog.w(TAG, "读回超时：${spec.vtsParam}（连续 $consecutiveReadTimeouts 次）")
                        if (consecutiveReadTimeouts >= MAX_CONSECUTIVE_READ_TIMEOUTS) {
                            throw AdaptationAbortException(
                                "连续 $consecutiveReadTimeouts 次 Live2D 读回超时"
                            )
                        }
                    }
                    ParamOutcome.SegmentTimeout -> {
                        skipped++
                        InAppLog.w(TAG, "单参数段超 ${PARAM_TOTAL_TIMEOUT_MS}ms：${spec.vtsParam}，跳过")
                    }
                }
                delay(PARAM_SWITCH_SETTLE_MS)
            }
            finish(modelID, modelName, mouthSmileNeutrals, mouthOpenScales,
                eyeOpenScales, skipped)
        } catch (e: AdaptationAbortException) {
            InAppLog.w(TAG, "自适应失败：${e.reason}（保持默认映射）")
            onState(AdaptationState.Failed(e.reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InAppLog.e(TAG, "自适应意外异常（保持默认映射）", e)
            onState(AdaptationState.Failed(e.message ?: "未知异常"))
        }
    }

    private suspend fun probeParameter(spec: ProbeSpec, index: Int, skippedSoFar: Int): ParamOutcome {
        report(AdaptationState.Phase.Reading, index, skippedSoFar, spec.vtsParam)
        val baselineData = readSnapshot() ?: return ParamOutcome.ReadTimeout
        val baseline = baselineData.parameters?.associateBy { it.name }
            ?: return ParamOutcome.ReadTimeout

        // 关联参数名与量程取自首探针读回的 ParamInfo
        var relatedName: String? = null
        var lmin = 0f
        var lmax = 1f
        val outs = FloatArray(PROBE_VALUES.size)
        for ((vi, v) in PROBE_VALUES.withIndex()) {
            report(AdaptationState.Phase.Injecting, index, skippedSoFar, spec.vtsParam)
            if (!injectWindow(spec.vtsParam, v)) return ParamOutcome.Unsupported
            report(AdaptationState.Phase.Reading, index, skippedSoFar, spec.vtsParam)
            val afterData = readSnapshot() ?: return ParamOutcome.ReadTimeout
            val after = afterData.parameters?.associateBy { it.name }
                ?: return ParamOutcome.ReadTimeout
            delay(POST_INJECT_SETTLE_MS)

            if (vi == 0) {
                val top = topDiff(baseline, after) ?: return ParamOutcome.DeadChannel
                relatedName = top.first
                val param = after.getValue(top.first)
                lmin = param.min
                lmax = param.max
            }
            outs[vi] = after[relatedName]?.value ?: return ParamOutcome.DeadChannel
        }
        return ParamOutcome.Probed(
            ProbeReads(relatedName!!, lmin, lmax, outs[0], outs[1], outs[2])
        )
    }

    private suspend fun finish(
        modelID: String,
        modelName: String,
        mouthSmileNeutrals: List<Float>,
        mouthOpenScales: List<Float>,
        eyeOpenScales: List<Float>,
        skipped: Int
    ) {
        report(AdaptationState.Phase.Saving, PROBE_SPECS.size, skipped, "")
        val overrides = MapperOverrides(
            mouthSmileNeutralBase = mouthSmileNeutrals.firstOrNull(),
            mouthOpenOutputScale = mouthOpenScales.firstOrNull(),
            eyeOpenOutputScale = avgOrNull(eyeOpenScales)
            // deviceMax 与 mouthXInvert 为手动项，探针不产出
        )
        if (overrides == MapperOverrides()) {
            InAppLog.w(TAG, "自适应完成但 4 参数全部无响应（全死通道），覆盖层为空（等同默认映射）")
        }
        storage.save(modelID, modelName, overrides)
        onOverridesReady(overrides, modelID, modelName)
        InAppLog.i(
            TAG,
            "自适应完成：model=$modelName，跳过 $skipped 项，overrides=$overrides"
        )
        onState(AdaptationState.Done(modelID))
    }

    /** 首帧返回值定接受性，窗口内重发返回值忽略 */
    private suspend fun injectWindow(param: String, value: Float): Boolean {
        if (!vtsClient.injectParameters(mapOf(param to value))) return false
        val deadline = System.currentTimeMillis() + INJECT_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            delay(INJECT_RESEND_INTERVAL_MS)
            vtsClient.injectParameters(mapOf(param to value))
        }
        return true
    }

    /** 单次读回超时 3s，超时返回 null */
    private suspend fun readSnapshot(): Live2DParameterListResponse.Data? =
        vtsClient.requestLive2DParameterList()

    /** 状态回调落在 IO 协程线程 */
    private fun report(phase: AdaptationState.Phase, index: Int, skipped: Int, paramName: String) {
        onState(AdaptationState.Running(phase, index, PROBE_SPECS.size, skipped, paramName))
    }

    private fun topDiff(
        baseline: Map<String, Live2DParameterListResponse.Live2DParam>,
        after: Map<String, Live2DParameterListResponse.Live2DParam>
    ): Pair<String, Float>? {
        var bestName: String? = null
        var bestDiff = 0f
        for (p in after.values) {
            val b = baseline[p.name] ?: continue
            val d = p.value - b.value
            if (abs(d) < DEAD_CHANNEL_EPSILON) continue
            if (bestName == null || abs(d) > abs(bestDiff)) {
                bestName = p.name
                bestDiff = d
            }
        }
        return bestName?.let { it to bestDiff }
    }

    /** 线性外推：slope=OUT(1)−OUT(0)，参考点取 v=0.5 读数，slope 为负时符号自动翻转 */
    private fun applyReads(
        spec: ProbeSpec,
        reads: ProbeReads,
        mouthSmileNeutrals: MutableList<Float>,
        mouthOpenScales: MutableList<Float>,
        eyeOpenScales: MutableList<Float>
    ) {
        val slope = reads.outAt1 - reads.outAt0
        if (abs(slope) < DEAD_CHANNEL_EPSILON) {
            InAppLog.w(TAG, "${spec.vtsParam}: OUT 两端无差异（slope≈0），视为死数据丢弃")
            return
        }
        when (spec.target) {
            ProbeTarget.MOUTH_SMILE_NEUTRAL -> {
                val neutral = (reads.lmin + reads.lmax) / 2f
                val vNeutral = MID_PROBE_VALUE + (neutral - reads.outAtMid) / slope
                mouthSmileNeutrals.add(vNeutral.coerceIn(0f, 1f))
            }
            ProbeTarget.MOUTH_OPEN_SCALE -> {
                val vAtMax = MID_PROBE_VALUE + (reads.lmax - reads.outAtMid) / slope
                mouthOpenScales.add(vAtMax.coerceIn(SCALE_MIN, SCALE_MAX))
            }
            ProbeTarget.EYE_OPEN_SCALE -> {
                val vAtMax = MID_PROBE_VALUE + (reads.lmax - reads.outAtMid) / slope
                eyeOpenScales.add(vAtMax.coerceIn(SCALE_MIN, SCALE_MAX))
            }
        }
    }

    private data class ProbeReads(
        val relatedName: String,
        val lmin: Float,
        val lmax: Float,
        val outAt1: Float,
        val outAtMid: Float,
        val outAt0: Float
    )

    private sealed class ParamOutcome {
        data class Probed(val reads: ProbeReads) : ParamOutcome()
        object DeadChannel : ParamOutcome()
        object Unsupported : ParamOutcome()
        object ReadTimeout : ParamOutcome()
        object SegmentTimeout : ParamOutcome()
    }

    private class AdaptationAbortException(val reason: String) : Exception(reason)

    private enum class ProbeTarget {
        MOUTH_SMILE_NEUTRAL, MOUTH_OPEN_SCALE, EYE_OPEN_SCALE
    }

    private data class ProbeSpec(val vtsParam: String, val target: ProbeTarget)

    companion object {
        private const val TAG = "Adaptation"

        /** 顺序为探测序；MouthX 为手动开关；EyeOpen 双侧共用字段取均值 */
        private val PROBE_SPECS = listOf(
            ProbeSpec("MouthSmile", ProbeTarget.MOUTH_SMILE_NEUTRAL),
            ProbeSpec("MouthOpen", ProbeTarget.MOUTH_OPEN_SCALE),
            ProbeSpec("EyeOpenLeft", ProbeTarget.EYE_OPEN_SCALE),
            ProbeSpec("EyeOpenRight", ProbeTarget.EYE_OPEN_SCALE)
        )

        /** 3 个采样值，v=1 先行定死关联通道，再 0.5、0 */
        private val PROBE_VALUES = floatArrayOf(1f, 0.5f, 0f)

        /** 注入窗口 250ms 内每 60ms 重发：VTS 停注入 1s 后回退参数值 */
        private const val INJECT_WINDOW_MS = 250L
        private const val INJECT_RESEND_INTERVAL_MS = 60L
        /** 停注入后等 200ms 覆盖 VTS 读回期 */
        private const val POST_INJECT_SETTLE_MS = 200L
        /** 换参数前等满 1s 回退期 */
        private const val PARAM_SWITCH_SETTLE_MS = 1000L
        /** 单参数段总超时 8s，超时跳过该参数 */
        private const val PARAM_TOTAL_TIMEOUT_MS = 8000L
        /** 连续读回超时达 2 次整体 Failed */
        private const val MAX_CONSECUTIVE_READ_TIMEOUTS = 2

        /** 差分与 slope 小于 0.01 视为无响应 */
        private const val DEAD_CHANNEL_EPSILON = 0.01f
        /** outputScale 钳制 0.5~1 */
        private const val SCALE_MIN = 0.5f
        private const val SCALE_MAX = 1f

        /** 外推参考点取 v=0.5 的读数 */
        private const val MID_PROBE_VALUE = 0.5f

        private fun avgOrNull(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.average().toFloat()
    }
}
