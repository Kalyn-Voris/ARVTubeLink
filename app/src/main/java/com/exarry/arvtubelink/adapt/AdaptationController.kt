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

/** 参数自适应状态机（MainActivity 以 mutableStateOf 镜像展示，AppRoot 只读消费）。 */
sealed class AdaptationState {
    data object Idle : AdaptationState()

    /**
     * 探测中。[phase] 细分档位；[index]/[total] 为参数进度（1 起）；[skipped] 为
     * 已跳过参数数（死通道/白名单外/超时）；[paramName] 为当前探测的 VTS 输入参数名。
     */
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

/**
 * 参数自适应探针编排器（adapt 包）：向 VTS 注入已知探针值、读回全量
 * Live2D 参数差分，线性反推每个模型的 IN→OUT 映射特性，产出 [MapperOverrides]。
 * 算法与 tools/probe_mapping_range.py 同源（基线差分 + top1 关联 + 线性外推）。
 *
 * ## 探针序列
 * 4 参数 × 3 值（顺序 1 → 0.5 → 0；v=1 先行——死通道判定需要最大幅值差分，
 * v=0 在中性贴 0 的模型上无差分会误判）。探针目标只覆盖探针可测的 3 个字段：
 * 中性点偏移（MouthSmile）与输出量程利用（MouthOpen / EyeOpen 系）；
 * deviceMax 类（AR 域摆幅）与方向开关（MouthX）属手动项，探针测不到，不产出。
 *
 * ## 眉毛三通道不参与探测
 * BrowLeftY / BrowRightY / Brows 已整体移出探针序列，映射层恒用默认 0.5 中性 /
 * 0.5 半幅。原因：眉毛是 0.5 中性双极通道，其「中性」语义是模型的**静息姿态**，
 * 本探针只能反推 Live2D **声明量程 [min,max] 的中点**，两者不等价。量程不对称、
 * 静息值偏离中点、单极绑定的模型都属此类。这类模型算出的中性基座会永久偏离 0.5，
 * 三个眉通道静息值长期低于 0.5，皱眉半段被 [0,1] 钳死。眉毛的个体差异改由
 * 手动「眉毛灵敏度」（browDeviceMax）或 VTS 端「重设中性锚点」承担，零代码可调。
 * 单极/双极的正确探测需要 defaultValue 域加合理性闸门，本类未实现。
 *
 * ## 采样节奏（探针注入的主动节奏，与 GL 帧循环无关）
 *   注入窗口 250ms（每 60ms 重发约 4 次，防 VTS「1s 不重发即回退」）
 *   → 停发后立即读回 Live2DParameterList（值仍在 VTS 保持期）
 *   → 等 200ms → 下一探针值；换参数前等 1000ms（VTS 参数回退期）。
 * 正常推流此时已被 setStreamingPaused(true) 门控拦截（MainActivity 在 Running
 * 状态回调里执行），在途锁空闲，探针帧经 injectParameters 串行无竞争。
 *
 * ## 判定规则
 *  - 死通道快跳：首探针 v=1 与基线的全量差分最大项（top1）变化量小于
 *    [DEAD_CHANNEL_EPSILON]（0.01，相对基线非探针间差）→ 跳过该参数剩余值；
 *    injectParameters 被拒（VTS 白名单外）同样跳过。关联参数不硬编码：
 *    v=1 首探针的 top1 即关联通道，后续两个探针值沿用（不搬脚本的 top1
 *    出现次数统计——3 点场景首探针满幅值最可靠，快跳后可能只剩单点）。
 *  - 超时：单参数段整体 8s（withTimeoutOrNull，超时跳过该参数继续）；
 *    单次读回超时 3s（requestLive2DParameterList 默认值），连续 2 次读回
 *    超时整体 Failed——读回卡死只废一个参数，不废全场。
 *  - 换算只用线性假设 + 钳制判定（OUT 贴 Live2D min/max 视为满幅覆盖），
 *    无 sigmoid；slope 近零的读回视为死数据丢弃。
 *
 * ## 失败兜底
 * Failed 时用默认映射（overrides 不动）并给出提示，不阻塞推流。门控复位由
 * MainActivity 的状态回调执行，本类只发状态。
 *
 * ## 线程与生命周期
 *  - 自持 IO 协程作用域（仿 VtsWsClient.scope），不绑生命周期：取消条件是
 *    断连/停追踪/销毁而非 lifecycle。cancel() 在主线程调（stopTracking/onDestroy）；
 *    start() 经 onModelInfo 触发链从主线程调，AtomicReference 仅作跨线程防御。
 *  - 状态经 [onState] 上收（IO 协程线程），MainActivity 侧 runOnUiThread 镜像；
 *    overrides 产出经 [onOverridesReady]（写盘成功后回调，应用与门控复位均在
 *    MainActivity 侧完成）。
 *  - 重入：Running 中同 modelID 忽略；不同 modelID 取消旧探测重启。
 *    取消路径的挂起点（delay/读回 await）抛 CancellationException 正常传播，
 *    状态由 cancel() 发 Idle 收口（try 体系不吞取消）。
 *
 * @param vtsClient         探针注入 + 读回通道（公开 API，复用注入白名单：
 *                          不在 supportedParams 的探针 injectParameters 返回 false）。
 * @param storage           覆盖层持久化（Done 前落盘，写盘失败不作废结果）。
 * @param onState           状态上收回调（IO 协程线程触发）。
 * @param onOverridesReady  探测产出回调（参数：overrides / modelID / modelName，
 *                          写盘成功后触发；应用 mapper 与持久化成败的 UI 反馈在
 *                          MainActivity 侧）。
 */
class AdaptationController(
    private val vtsClient: VtsWsClient,
    private val storage: AdaptationStorage,
    private val onState: (AdaptationState) -> Unit = {},
    private val onOverridesReady: (MapperOverrides, modelID: String, modelName: String) -> Unit =
        { _, _, _ -> }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobRef = AtomicReference<Job?>(null)

    /** 当前探测会话的 modelID（重入判重用；与 jobRef 同步维护）。 */
    @Volatile
    private var runningModelId: String? = null

    /**
     * 启动探测（主线程调用；门控 setStreamingPaused(true) 由 MainActivity 在
     * 首个 Running 状态回调里执行，此处不管门控）。Running 中重入：同 modelID
     * 忽略（重连认证后的重复 onModelInfo），不同 modelID 取消旧探测重启（换模型）。
     */
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

    /** 取消探测并复位（主线程调用：stopTracking / onDestroy / Running 中换模型重启）。 */
    fun cancel() {
        runningModelId = null
        jobRef.getAndSet(null)?.cancel()
        onState(AdaptationState.Idle)
    }

    // ---------- 探针主流程 ----------

    /** 探测主流程（IO 协程）。任何单参数失败只跳过，连续读回超时才整体失败。 */
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
                delay(PARAM_SWITCH_SETTLE_MS) // VTS 参数回退期（含最后一个参数：恢复推流前清干净）
            }
            finish(modelID, modelName, mouthSmileNeutrals, mouthOpenScales,
                eyeOpenScales, skipped)
        } catch (e: AdaptationAbortException) {
            InAppLog.w(TAG, "自适应失败：${e.reason}（保持默认映射）")
            onState(AdaptationState.Failed(e.reason))
        } catch (e: CancellationException) {
            throw e // 协程取消：状态由 cancel() 发 Idle 收口，吞掉会破坏取消语义
        } catch (e: Exception) {
            // 意外异常兜底。IO 协程未捕获异常直达 uncaught handler，应用崩溃，门控卡在
            // Running。必须就地转 Failed：失败用默认映射，不阻塞推流。
            InAppLog.e(TAG, "自适应意外异常（保持默认映射）", e)
            onState(AdaptationState.Failed(e.message ?: "未知异常"))
        }
    }

    /** 单参数探测：读基线 → 3 探针值（1/0.5/0）各「注入窗口→读回」→ 组装读数。 */
    private suspend fun probeParameter(spec: ProbeSpec, index: Int, skippedSoFar: Int): ParamOutcome {
        report(AdaptationState.Phase.Reading, index, skippedSoFar, spec.vtsParam)
        val baselineData = readSnapshot() ?: return ParamOutcome.ReadTimeout
        val baseline = baselineData.parameters?.associateBy { it.name }
            ?: return ParamOutcome.ReadTimeout

        var relatedName: String? = null
        var lmin = 0f
        var lmax = 1f
        // outs 按探针值顺序记录关联 Live2D 参数的读回值
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
                // 首探针 v=1：全量差分取 top1 定关联通道，同时判死通道（快跳判据）
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

    /** 完成收尾：换算覆盖层 → 落盘 → 上收产出 → Done（写盘失败不作废结果）。 */
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
            // mouthOpenDeviceMax / browDeviceMax / mouthXInvert 为手动项，探针不产出（见类 KDoc）；
            // 眉毛三通道整体不探测，browNeutralBase / browHalfRange 字段已随之下线
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

    // ---------- 探针原子操作 ----------

    /**
     * 探针注入窗口：首帧决定接受性（false = 未认证/白名单外/入队失败），窗口内每
     * 60ms 重发（约 4 次，防 VTS「1s 不重发即回退」）——重发是 injectParameters 的
     * 主动节奏、与 GL 帧循环无关；重发返回值忽略（首帧已入队，250ms 内不回退，
     * 偶发在途锁竞争丢弃一帧重发无碍）。
     */
    private suspend fun injectWindow(param: String, value: Float): Boolean {
        if (!vtsClient.injectParameters(mapOf(param to value))) return false
        val deadline = System.currentTimeMillis() + INJECT_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            delay(INJECT_RESEND_INTERVAL_MS)
            vtsClient.injectParameters(mapOf(param to value))
        }
        return true
    }

    /** 读回全量 Live2D 参数快照（单次超时 3s，null = 超时，由外层计连续失败）。 */
    private suspend fun readSnapshot(): Live2DParameterListResponse.Data? =
        vtsClient.requestLive2DParameterList()

    /** 状态上收（IO 协程线程；MainActivity 侧 runOnUiThread 镜像）。 */
    private fun report(phase: AdaptationState.Phase, index: Int, skipped: Int, paramName: String) {
        onState(AdaptationState.Running(phase, index, PROBE_SPECS.size, skipped, paramName))
    }

    // ---------- 差分与换算 ----------

    /** 全量差分取 |diff| 最大项（过滤小于 [DEAD_CHANNEL_EPSILON] 的抖动）。 */
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

    /**
     * 单参数读数换算（线性假设，参考点外推）。slope = OUT(v=1) − OUT(v=0)，参考点取
     * v=0.5 的读数（离两端最远，最抗 VTS 平滑拖尾）。slope 为负（OUT 对 IN 反向绑定）
     * 时公式仍成立——外推只做除法，符号随 slope 自动翻转，无需分支。
     *
     * 眉毛三通道已不在本函数处理范围内（见类 KDoc）。
     */
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
            // OUT 中性点 (lmin+lmax)/2 对应的 IN（参考点 v=0.5）＝ MouthSmile 中性基座
            ProbeTarget.MOUTH_SMILE_NEUTRAL -> {
                val neutral = (reads.lmin + reads.lmax) / 2f
                val vNeutral = MID_PROBE_VALUE + (neutral - reads.outAtMid) / slope
                mouthSmileNeutrals.add(vNeutral.coerceIn(0f, 1f))
            }
            // OUT 满幅 (lmax) 对应的 IN（参考点 v=0.5）＝ 该通道输出量程上限：
            // 模型半幅绑定（IN 0.5 未贴满）时外推 >0.5 → 放宽；贴满时 ≤0.5 → 钳回 0.5 保持
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

    /** 探测产出（单参数级）：关联 Live2D 参数名、其量程、三个探针值的 OUT 读数。 */
    private data class ProbeReads(
        val relatedName: String,
        val lmin: Float,
        val lmax: Float,
        val outAt1: Float,
        val outAtMid: Float,
        val outAt0: Float
    )

    /** 单参数探测结果（ReadTimeout 由外层连续计数，达阈值整体 Failed）。 */
    private sealed class ParamOutcome {
        data class Probed(val reads: ProbeReads) : ParamOutcome()
        object DeadChannel : ParamOutcome()
        object Unsupported : ParamOutcome()
        object ReadTimeout : ParamOutcome()
        object SegmentTimeout : ParamOutcome()
    }

    /** 中止探测（整体 Failed 路径）：只由「连续读回超时」触发，单参数失败一律跳过。 */
    private class AdaptationAbortException(val reason: String) : Exception(reason)

    // ---------- 探针序列定义 ----------

    /** 探针目标（决定读数换算公式；探针可测的覆盖字段见类 KDoc）。 */
    private enum class ProbeTarget {
        MOUTH_SMILE_NEUTRAL, MOUTH_OPEN_SCALE, EYE_OPEN_SCALE
    }

    private data class ProbeSpec(val vtsParam: String, val target: ProbeTarget)

    companion object {
        private const val TAG = "Adaptation"

        /**
         * 探针序列（4 参数，顺序即探测序）：MouthX 排除——方向翻转是手动开关不自动探；
         * EyeOpen 双侧共用一个覆盖字段（结果取均值）。**眉毛三通道整体不探测**：双极通道的中性 = 模型静息姿态，本探针只能反推声明量程中点，不等价时
         * 会写出错误的 0.5 中性基座，详见类 KDoc「眉毛三通道不参与探测」。
         */
        private val PROBE_SPECS = listOf(
            ProbeSpec("MouthSmile", ProbeTarget.MOUTH_SMILE_NEUTRAL),
            ProbeSpec("MouthOpen", ProbeTarget.MOUTH_OPEN_SCALE),
            ProbeSpec("EyeOpenLeft", ProbeTarget.EYE_OPEN_SCALE),
            ProbeSpec("EyeOpenRight", ProbeTarget.EYE_OPEN_SCALE)
        )

        /** 探针值序列：v=1 先行（死通道判定 + 关联参数确定），再 0.5、0。 */
        private val PROBE_VALUES = floatArrayOf(1f, 0.5f, 0f)

        /** 探针注入窗口（ms）：窗口内每 60ms 重发，防 VTS「1s 不重发即回退」。 */
        private const val INJECT_WINDOW_MS = 250L
        /** 探针重发间隔（ms；250ms 窗口约 4 次重发）。 */
        private const val INJECT_RESEND_INTERVAL_MS = 60L
        /** 停注入后的保底等待（ms），覆盖 VTS 读回期，之后进下一探针值。 */
        private const val POST_INJECT_SETTLE_MS = 200L
        /** 换参数前的等待（ms）：VTS 停止注入 1s 后才回退参数值，防污染下一参数基线。 */
        private const val PARAM_SWITCH_SETTLE_MS = 1000L
        /** 单参数段总超时（ms）：防读回卡死拖垮全场，超时跳过该参数。 */
        private const val PARAM_TOTAL_TIMEOUT_MS = 8000L
        /** 连续读回超时阈值：达到即整体 Failed（断线/读回通道坏死的兜底）。 */
        private const val MAX_CONSECUTIVE_READ_TIMEOUTS = 2

        /** 死通道判定阈值：与基线差分（及 slope）小于此值视为无响应。 */
        private const val DEAD_CHANNEL_EPSILON = 0.01f
        /** outputScale 类覆盖值的钳制下限（保持默认 0.5 不再压小）。 */
        private const val SCALE_MIN = 0.5f
        /** outputScale 类覆盖值的钳制上限（表情域满幅）。 */
        private const val SCALE_MAX = 1f

        /** 中点探针值（OUT 中性/满幅外推的参考点）。 */
        private const val MID_PROBE_VALUE = 0.5f

        private fun avgOrNull(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.average().toFloat()
    }
}
