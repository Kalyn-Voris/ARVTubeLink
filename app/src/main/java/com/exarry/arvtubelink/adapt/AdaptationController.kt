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

/** 探针编排：注入已知值、差分读回 Live2D 参数，线性反推 IN→OUT 映射 */
class AdaptationController(
    private val vtsClient: VtsWsClient,
    private val storage: AdaptationStorage,
    private val onState: (AdaptationState) -> Unit = {},
    private val onOverridesReady: (MapperOverrides, modelID: String, modelName: String) -> Unit =
        { _, _, _ -> }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobRef = AtomicReference<Job?>(null)

    /** 当前会话 modelID，重入判重用 */
    @Volatile
    private var runningModelId: String? = null

    /** 主线程调用；同 modelID 重入忽略，换模型取消旧探测重启 */
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

    /** 主线程调用：置 Idle 并发 Idle 状态收口 */
    fun cancel() {
        runningModelId = null
        jobRef.getAndSet(null)?.cancel()
        onState(AdaptationState.Idle)
    }

    /** IO 协程；单参数失败只跳过，连续读回超时才整体 Failed */
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
                delay(PARAM_SWITCH_SETTLE_MS) // 换参数前等 VTS 1s 参数回退期
            }
            finish(modelID, modelName, mouthSmileNeutrals, mouthOpenScales,
                eyeOpenScales, skipped)
        } catch (e: AdaptationAbortException) {
            InAppLog.w(TAG, "自适应失败：${e.reason}（保持默认映射）")
            onState(AdaptationState.Failed(e.reason))
        } catch (e: CancellationException) {
            throw e // 状态由 cancel() 发 Idle，吞掉会破坏取消语义
        } catch (e: Exception) {
            // IO 协程未捕获异常直达 uncaught handler 会崩溃并卡住门控，就地转 Failed
            InAppLog.e(TAG, "自适应意外异常（保持默认映射）", e)
            onState(AdaptationState.Failed(e.message ?: "未知异常"))
        }
    }

    /** 读基线 → 3 个探针值各「注入窗口→读回」→ 组装读数 */
    private suspend fun probeParameter(spec: ProbeSpec, index: Int, skippedSoFar: Int): ParamOutcome {
        report(AdaptationState.Phase.Reading, index, skippedSoFar, spec.vtsParam)
        val baselineData = readSnapshot() ?: return ParamOutcome.ReadTimeout
        val baseline = baselineData.parameters?.associateBy { it.name }
            ?: return ParamOutcome.ReadTimeout

        var relatedName: String? = null
        var lmin = 0f
        var lmax = 1f
        // outs 按探针值顺序记录关联参数读回值
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
                // 首探针 v=1：差分 top1 定关联通道，同时判死通道
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

    /** 换算覆盖层 → 落盘 → 上收产出 → Done，写盘失败不作废结果 */
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

    /** 首帧定接受性；窗口内每 60ms 重发防 VTS 1s 回退，重发返回值忽略 */
    private suspend fun injectWindow(param: String, value: Float): Boolean {
        if (!vtsClient.injectParameters(mapOf(param to value))) return false
        val deadline = System.currentTimeMillis() + INJECT_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            delay(INJECT_RESEND_INTERVAL_MS)
            vtsClient.injectParameters(mapOf(param to value))
        }
        return true
    }

    /** 读回全量参数快照，单次超时 3s，null 由外层计连续失败 */
    private suspend fun readSnapshot(): Live2DParameterListResponse.Data? =
        vtsClient.requestLive2DParameterList()

    /** 状态上收在 IO 协程线程，MainActivity 侧 runOnUiThread 镜像 */
    private fun report(phase: AdaptationState.Phase, index: Int, skipped: Int, paramName: String) {
        onState(AdaptationState.Running(phase, index, PROBE_SPECS.size, skipped, paramName))
    }

    /** 全量差分取 |diff| 最大项，过滤小于 DEAD_CHANNEL_EPSILON 的抖动 */
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

    /** 线性外推：slope=OUT(v=1)−OUT(v=0)，参考点取 v=0.5 的读数；slope 为负时符号自动翻转 */
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
            // OUT 中性点 (lmin+lmax)/2 对应的 IN = MouthSmile 中性基座
            ProbeTarget.MOUTH_SMILE_NEUTRAL -> {
                val neutral = (reads.lmin + reads.lmax) / 2f
                val vNeutral = MID_PROBE_VALUE + (neutral - reads.outAtMid) / slope
                mouthSmileNeutrals.add(vNeutral.coerceIn(0f, 1f))
            }
            // OUT 满幅 lmax 对应的 IN：半幅绑定外推 >0.5 放宽，贴满时钳回 0.5
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

    /** 单参数产出：关联参数名、量程、三个探针值的 OUT 读数 */
    private data class ProbeReads(
        val relatedName: String,
        val lmin: Float,
        val lmax: Float,
        val outAt1: Float,
        val outAtMid: Float,
        val outAt0: Float
    )

    /** ReadTimeout 由外层连续计数，达阈值整体 Failed */
    private sealed class ParamOutcome {
        data class Probed(val reads: ProbeReads) : ParamOutcome()
        object DeadChannel : ParamOutcome()
        object Unsupported : ParamOutcome()
        object ReadTimeout : ParamOutcome()
        object SegmentTimeout : ParamOutcome()
    }

    /** 只由连续读回超时触发 */
    private class AdaptationAbortException(val reason: String) : Exception(reason)

    /** 探针目标，决定读数换算公式 */
    private enum class ProbeTarget {
        MOUTH_SMILE_NEUTRAL, MOUTH_OPEN_SCALE, EYE_OPEN_SCALE
    }

    private data class ProbeSpec(val vtsParam: String, val target: ProbeTarget)

    companion object {
        private const val TAG = "Adaptation"

        /** 顺序即探测序；MouthX 为手动开关；EyeOpen 双侧共用字段取均值 */
        private val PROBE_SPECS = listOf(
            ProbeSpec("MouthSmile", ProbeTarget.MOUTH_SMILE_NEUTRAL),
            ProbeSpec("MouthOpen", ProbeTarget.MOUTH_OPEN_SCALE),
            ProbeSpec("EyeOpenLeft", ProbeTarget.EYE_OPEN_SCALE),
            ProbeSpec("EyeOpenRight", ProbeTarget.EYE_OPEN_SCALE)
        )

        /** v=1 先行定死通道与关联参数，再 0.5、0 */
        private val PROBE_VALUES = floatArrayOf(1f, 0.5f, 0f)

        /** 注入窗口 250ms，窗口内每 60ms 重发防 VTS 1s 回退 */
        private const val INJECT_WINDOW_MS = 250L
        private const val INJECT_RESEND_INTERVAL_MS = 60L
        /** 停注入后等 200ms 覆盖 VTS 读回期 */
        private const val POST_INJECT_SETTLE_MS = 200L
        /** VTS 停注入 1s 后才回退参数值，防污染下一参数基线 */
        private const val PARAM_SWITCH_SETTLE_MS = 1000L
        /** 单参数段总超时 8s，超时跳过该参数 */
        private const val PARAM_TOTAL_TIMEOUT_MS = 8000L
        /** 连续读回超时达 2 次整体 Failed */
        private const val MAX_CONSECUTIVE_READ_TIMEOUTS = 2

        /** 差分与 slope 小于 0.01 视为无响应 */
        private const val DEAD_CHANNEL_EPSILON = 0.01f
        /** outputScale 钳制下限 0.5，不再压小 */
        private const val SCALE_MIN = 0.5f
        /** outputScale 钳制上限 1，表情域满幅 */
        private const val SCALE_MAX = 1f

        /** OUT 中性/满幅外推的参考点 */
        private const val MID_PROBE_VALUE = 0.5f

        private fun avgOrNull(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.average().toFloat()
    }
}
