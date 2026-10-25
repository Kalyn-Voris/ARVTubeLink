package com.exarry.arvtubelink.mapping

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 映射覆盖层：参数自适应探测结果与设置页「手动映射范围」滑块的共用存储。
 *
 * 全字段可空：null = 该项无覆盖，映射读取点走硬编码默认——[BlendShapeMapper.overrides]
 * 整体为 null 时映射链路与无覆盖版**逐位一致**（零覆盖回归锚点）。字段语义与默认值：
 *  - [mouthSmileNeutralBase]：MouthSmile 输出中性基座（默认 0.5；自适应探测 VTS 端
 *    中性锚点偏移，或手动「中性点」滑块；只移基座不动半幅，满笑/满嘟不对称由钳制兜底）；
 *  - [mouthOpenDeviceMax]：MouthOpen 归一化分母（默认 0.30；手动「说话灵敏度」滑块，
 *    值越小越灵敏）；[mouthOpenOutputScale]：MouthOpen 输出量程上限（默认 0.5；
 *    自适应探测模型 IN 满幅点）；
 *  - [eyeOpenOutputScale]：EyeOpenLeft/Right 共用输出量程上限（默认 0.5；自适应）；
 *  - [browDeviceMax]：眉毛三通道共用归一化分母（默认 1.0；手动「眉毛灵敏度」倍率
 *    经此换算）。**眉毛输出基座/半幅不可覆盖**：恒为 0.5 中性 /
 *    0.5 半幅——双极通道的「中性」是模型静息姿态，探测只能反推 Live2D 声明量程中点，
 *    不等价时会把静息值永久压离 0.5，模型差异改由 VTS 端「重设中性锚点」承担；
 *  - [mouthXInvert]：MouthX 方向翻转（默认 false）。无 UI 入口，左右翻转由全局
 *    「脸部镜像」承担（见 [BlendShapeMapper.faceMirrorEnabled]）；字段与合成公式里的
 *    取反逻辑保留，作为 MouthX 极性反转的底层兜底（当前无写入点，恒为 null）。
 *    取反语义相对**当前基准极性**（`Mouth_Left − Mouth_Right`）。
 *
 * 不可变数据类：@Volatile 引用整体替换（GL 线程每帧读一次引用、UI/适应协程写入，
 * 与 CalibrationData 同款线程模型，无锁）；数值越界由 BlendShapeMapper 读取点
 * 防御性钳制兜底（持久化层不保证合法值）。
 */
data class MapperOverrides(
    val mouthSmileNeutralBase: Float? = null,
    val mouthOpenDeviceMax: Float? = null,
    val mouthOpenOutputScale: Float? = null,
    val eyeOpenOutputScale: Float? = null,
    val browDeviceMax: Float? = null,
    val mouthXInvert: Boolean? = null
)

/**
 * 映射层：华为 AR Engine 数据 -> VTube Studio 参数名。
 * 表情系数映射见 [Companion.arToVtsMapping]；头部姿态映射见 [mapHeadPose]。
 *
 * ## 处理链（校准 + 归一化 + 灵敏度）
 *
 *   原始(Raw) -> 归一化(Normalize) -> 强度整形(Shape) -> 通道增益(Gain) -> 全局灵敏度(Sens) -> 钳制(Clamp)
 *
 *  - 表情（强度域运算，反转在整形之后）：
 *      norm   = (Raw - neutral) / deviceMax
 *               neutral = 校准中性点的 AR 原始域值（正向 = base、反转 = 1 - base，未校准取 0）；
 *               deviceMax = 该通道「中性→满幅」的原始摆幅（常数，分母不含 neutral——
 *               deviceMax=1 的通道因此退化为线性映射，推导见下）；
 *      shaped = shapeExpression(norm, cfg)：死区 + 可选饱和的分段线性，缺省恒等；
 *      mapped = invert ? (1 - shaped × gain × sens) × outputScale
 *                      : shaped × gain × sens × outputScale，钳制 [0, outputScale]。
 *    outputScale = 输出量程上限，缺省 1.0（不压缩）；EyeOpen 系与 MouthOpen 为 0.5，
 *    数值来源：VTS 实测（睁眼 IN 0.50 正常开、0.65 已过驱，张嘴 IN 0.51 即满幅）；
 *    EyeOpen 通道钳制上界放宽至 1.0（VTS 标准 EyeOpen 域 0~1，InputParameterListResponse
 *    实测；0.65 = sens=1 时 0.5 基线 + 0.15 叠加的实际峰值，sens>1 不截断，
 *    见 map 内 clampMax 注释与下方「合成与叠加」）。
 *    全局灵敏度 sens 乘在最终输出，设置页「表情灵敏度」滑块继续缩放全部表情通道；
 *    channelGain 只吸收设备摆幅差异（本机 Blink≈0.5、Squint≤0.1），与全局滑块纯乘性、不覆盖。
 *    缺省配置下的退化形式（deviceMax=1、无死区/饱和、gain=1、outputScale=1）：
 *      正向：clamp01((Raw - base) × sens)；
 *      反转：1 - (Raw - (1-base)) × sens，即 neutral = 1-base。
 *  - 头姿（角度/位置）：mapped = (Raw - BaseValue) * Sensitivity，钳制到实测安全范围；
 *    角度/位置是连续控制域，不套表情的归一化/死区/曲线（连续控制量手感劣化）。
 *  - 合成与叠加（miku 模型实测驱动）：
 *      MouthSmile（合成通道）= 正段（左右 Smile 均值）− 负段（Pucker 嘟嘴，单一源；
 *        AR Engine 里 Mouth_Frown 是嘴角下垂不是嘟嘴，负段源键取 Pucker），
 *        内部 combined 取 [-1, 1]，最终输出 0.5 + combined × 0.5 落到 [0, 1]
 *        （VTS 端语义：0.5 中性、>0.5 微笑、<0.5 嘟嘴）；
 *        各输入先去校准中性、共用 deviceMax=0.85（SMILE_DEVICE_MAX /
 *        PUCKER_DEVICE_MAX）归一化，再走 shapeExpression 按正负半段分别整形
 *        （deadZone=0.15 见 [channelConfigs]），见 [putSynthesizedParams]；
 *      MouthX（合成通道）= Mouth_Left − Mouth_Right，双极输出 [-1, 1]（中性 0，
 *        嘴部左右移动；VTS 标准 MouthX 域 -1~1，InputParameterListResponse 实测）；
 *        极性已对调，见
 *        [putSynthesizedParams]；
 *      BrowLeftY/BrowRightY/Brows（合成通道）= 0.5 + combined × 0.5，输出 [0, 1]
 *        （VTS 标准三参数域均 0~1，InputParameterListResponse 实测）。
 *        双极语义：0.5 中性、>0.5 上扬、<0.5 皱眉（akari 探测：Brows 注入
 *        0/1 → BrowForm -0.75/+0.95，正负两段皆有响应，皱眉经本通道表达）；左侧
 *        combined = (InnerUp + OuterUpLeft)/2 − DownLeft，右侧对称，Brows = 左右
 *        combined 均值（不用独立三分量权重）。VTS 标准同时提供三个输入、
 *        模型参数自行选绑：miku 的 Brow Height Left/Right 实测绑 Brows，
 *        三通道同时输出提高通用性。5 个 Brow 细分 1:1 输出保留
 *        （校准快照依赖），见 [putSynthesizedParams]；
 *      EyeOpen 瞪眼叠加：输出 = (1 − blink强度 × gain × sens) × 0.5
 *        + Wide整形 × gain × sens × 0.15，钳制 [0, 1]——Wide 闭眼帧恒 0，
 *        不干扰眨眼，见 [eyeWideBoostTerm]；
 *      EyeY 瞪眼补偿：EyeLeftY/EyeRightY 减去 Wide整形 × 0.35（瞪眼时眼球下移、
 *        让瞪眼更明显），
 *        见 [putEyeDirection]。
 *      合成/叠加不进 1:1 映射表；1:1 条目一律保留（其他模型可用 + 校准快照依赖，
 *      不支持的参数由发送端白名单在 App 侧过滤、不出网）。
 *  - 脸部镜像：[faceMirrorEnabled] 开启时，[map] / [mapHeadPose] 的
 *    **最终输出 Map** 走一次统一后处理 [applyFaceMirror]——左右通道身份互换 + 水平分量
 *    取反，不侵入任何内部公式（归一化/整形/增益/合成/钳制全部原样）。前置摄像头是镜像
 *    视角，用户看到的自己与 VTS 模型朝向相反，本开关把左右整体对调。作用点在
 *    [latestExpressionRaw] / [latestHeadRaw] 快照**之后**，校准基准始终保持未镜像域。
 *
 * VTS 参数安全边界（InputParameterListResponse 实测）：
 * 角度 X/Y [-30,30]、Z（歪头）[-90,90]；位置 X/Y [-15,15]、Z（远近）[-10,10]；表情 [0,1]
 * （配置 outputScale 的通道按 [0, outputScale] 钳制，见 [channelConfigs]）。
 *
 * ## 线程模型
 *  - [map]/[mapHeadPose] 在 GL 渲染线程逐帧调用；灵敏度/校准基准由 UI 线程写入。
 *  - 所有跨线程共享状态均为 @Volatile 标量或不可变对象（整体替换），无锁。
 *  - 被钳制的参数按参数名节流输出告警（[Companion.CLAMP_WARN_INTERVAL_MS] 内同一参数只记一次）。
 */
class BlendShapeMapper(
    /** 初始校准基准值；默认 [CalibrationData]（中性默认构造 = 恒等映射）。 */
    calibration: CalibrationData = CalibrationData(),
    /** 头姿角度灵敏度（FaceAngleX/Y/Z），使用时钳制到 [SENSITIVITY_MIN]..[SENSITIVITY_MAX]。 */
    @Volatile var angleSensitivity: Float = SENSITIVITY_DEFAULT,
    /** 头部位置灵敏度（FacePositionX/Y/Z）。 */
    @Volatile var positionSensitivity: Float = SENSITIVITY_DEFAULT,
    /** 表情灵敏度（EyeOpen*、Mouth* 等全部表情项）。 */
    @Volatile var expressionSensitivity: Float = SENSITIVITY_DEFAULT
) {

    /**
     * 当前校准基准值。不可变实例整体替换：GL 线程每帧读一次引用，
     * UI 线程经 [updateCalibration]/[captureCurrentAsBase] 写入，无需加锁。
     */
    @Volatile
    var calibrationData: CalibrationData = calibration
        private set

    /** 更新校准基准值（UI 线程调用；持久化由调用方经 CalibrationStorage.save 完成）。 */
    fun updateCalibration(data: CalibrationData) {
        calibrationData = data
    }

    /**
     * 映射覆盖层（[MapperOverrides]，null = 无覆盖走硬编码默认）。不可变实例整体替换：
     * 适应协程 / UI 线程经 [updateOverrides] 写入，GL 线程每帧读一次引用
     * （与 [calibrationData] 同款线程模型，无锁）。
     */
    @Volatile
    var overrides: MapperOverrides? = null
        private set

    /** 更新覆盖层（适应协程 / UI 线程调用；传 null 清除全部覆盖 = 回退硬编码默认）。 */
    fun updateOverrides(value: MapperOverrides?) {
        overrides = value
    }

    /**
     * 脸部镜像开关（默认关闭）。开启后 [map] / [mapHeadPose] 的最终输出统一做左右镜像：
     * 左右成对通道互换身份、水平方向量取反，见 [applyFaceMirror]。
     *
     * 与 [overrides] **刻意分离**：本开关是全局显示语义，不是映射覆盖——不能被
     * `updateOverrides` 的整体替换（自适应缓存命中 / 探测完成 / 手动值）冲掉，
     * 也不受设置页「探测值生效中 → 手动映射区禁用」的门控。线程模型与灵敏度同款：
     * UI 线程写、GL 线程每帧读（@Volatile 标量，无锁）。
     *
     * 值来源：app_settings 的 `face_mirror`（默认 false）；MainActivity 启动同步 + 设置页写透。
     */
    @Volatile
    var faceMirrorEnabled: Boolean = false

    // ---------- 最新一帧原始数据快照（供「校准」捕获当前帧） ----------

    /**
     * 最新一帧表情的 VTS 输出域原始值（已反转、未校准未缩放）。
     * 仅在追踪到人脸的帧更新（GL 线程写，UI 线程读）；缺失输入项不进快照。
     */
    @Volatile
    private var latestExpressionRaw: Map<String, Float> = emptyMap()

    /** 最新一帧头姿原始值（FaceAngle* + FacePosition*，同上域）。 */
    @Volatile
    private var latestHeadRaw: Map<String, Float> = emptyMap()

    /**
     * 取最新一帧原始数据快照（表情 + 头姿合并），供设置面板的「校准」按钮调用：
     * `mapper.captureCurrentAsBase(mapper.latestRawSnapshot() ?: return)`。
     *
     * @return null 表示尚未收到任何追踪帧（未开始追踪 / 从未成功检测到人脸）。
     */
    fun latestRawSnapshot(): Map<String, Float>? {
        val snapshot: Map<String, Float> = latestExpressionRaw + latestHeadRaw
        return if (snapshot.isEmpty()) null else snapshot
    }

    /** 清空快照（停止追踪时调用，防止「校准」捕获到早已过期的陈旧帧）。 */
    fun clearRawSnapshot() {
        latestExpressionRaw = emptyMap()
        latestHeadRaw = emptyMap()
    }

    /**
     * 将给定原始帧数据整体设为校准基准值（零点），立即生效。
     *
     * 分区规则：键属于 [CalibrationData.ANGLE_KEYS] -> baseAngles；
     * 属于 [CalibrationData.POSITION_KEYS] -> basePositions；其余 -> baseExpressions。
     * 整体替换旧基准（非合并）：某类键在快照中缺失时该类回退中性默认，并输出告警日志。
     *
     * @param rawData 通常传 [latestRawSnapshot] 的返回值（当前帧 VTS 域原始值）。
     * @return 更新后的校准数据（调用方可用 CalibrationStorage.save 持久化）。
     */
    fun captureCurrentAsBase(rawData: Map<String, Float>): CalibrationData {
        val angles = rawData.filterKeys { it in CalibrationData.ANGLE_KEYS }
        val positions = rawData.filterKeys { it in CalibrationData.POSITION_KEYS }
        val expressions = rawData.filterKeys {
            it !in CalibrationData.ANGLE_KEYS && it !in CalibrationData.POSITION_KEYS
        }
        if (angles.isEmpty()) {
            Log.w(TAG, "校准捕获：快照缺头姿角度项（本帧未读到头姿？），角度基准回退中性 0")
        }
        if (positions.isEmpty()) {
            Log.w(TAG, "校准捕获：快照缺头姿位置项，位置基准回退中性 0")
        }
        if (expressions.isEmpty()) {
            Log.w(TAG, "校准捕获：快照缺表情项，表情基准回退中性 0/1")
        }

        val newCalibration = CalibrationData(
            baseAngles = angles,
            basePositions = positions,
            baseExpressions = expressions
        )
        calibrationData = newCalibration
        // 用户主动触发的低频事件（点一次「校准」一条），不纳入逐帧调试开关。
        Log.i(
            TAG,
            "校准捕获完成：角度 ${angles.size} 项 $angles，位置 ${positions.size} 项，表情 ${expressions.size} 项"
        )
        return newCalibration
    }

    /**
     * 通道配置查表 + 覆盖合并（GL 线程每帧调用）：overrides 为 null 时直接返回
     * channelConfigs 原条目（零分配、与无覆盖版逐位一致）；仅合并受覆盖的通道字段
     * （EyeOpen 系 outputScale、MouthOpen deviceMax/outputScale），其余通道原样。
     * 数值防御性钳制：覆盖值来自持久化 JSON / 手动滑块，损坏输入不得产生超界输出。
     */
    private fun cfgFor(vtsKey: String): ExpressionConfig {
        val base = channelConfigs[vtsKey] ?: DEFAULT_EXPRESSION_CONFIG
        val o = overrides ?: return base
        return when (vtsKey) {
            "EyeOpenLeft", "EyeOpenRight" ->
                if (o.eyeOpenOutputScale == null) base
                else base.copy(
                    outputScale = o.eyeOpenOutputScale.coerceIn(EXPRESSION_MIN, EXPRESSION_MAX)
                )
            "MouthOpen" ->
                if (o.mouthOpenDeviceMax == null && o.mouthOpenOutputScale == null) base
                else base.copy(
                    deviceMax = o.mouthOpenDeviceMax
                        ?.coerceIn(OVERRIDE_DEVICE_MAX_MIN, OVERRIDE_DEVICE_MAX_MAX)
                        ?: base.deviceMax,
                    outputScale = o.mouthOpenOutputScale
                        ?.coerceIn(EXPRESSION_MIN, EXPRESSION_MAX)
                        ?: base.outputScale
                )
            else -> base
        }
    }

    /**
     * 将一帧 AR 表情系数映射为 VtsParams（归一化 + 强度整形 + 通道增益 + 全局灵敏度
     * + 量程压缩 + [0, outputScale] 钳制；处理链见类 KDoc「处理链」章节）。
     * 缺失的输入按中性值补充：反转通道（EyeOpen 系）补量程上限 outputScale（睁眼中性），其余补 0f；
     * 补充值不经过校准/归一化公式（校准只作用于真实追踪到的系数）。
     * 主循环后追加合成通道（[putSynthesizedParams]）与眼球方向（[putEyeDirection]）。
     */
    fun map(arCoefficients: Map<String, Float>): VtsParams {
        val faceFound = arCoefficients.isNotEmpty()
        val cal = calibrationData // volatile 读一次，保证帧内基准一致
        val sens = expressionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val expressionRaw = LinkedHashMap<String, Float>(arToVtsMapping.size)
        val result = LinkedHashMap<String, Float>(arToVtsMapping.size + 2)
        for ((arKey, vtsKey) in arToVtsMapping) {
            val cfg = cfgFor(vtsKey)
            val raw = arCoefficients[arKey]
            // 归一化零点 = 校准中性点的 AR 原始域值：
            // 正向通道 baseExpressions 即中性；反转通道存的是 vtsRaw(=1-Raw) 域，换算回 1-base
            val neutral = if (cfg.invert) {
                EXPRESSION_MAX - (cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_MAX)
            } else {
                cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_NEUTRAL
            }
            // 强度域整形（死区/饱和在反转前运算：静息噪声归零后反转，睁眼端恒满幅）
            val intensity = raw?.let { shapeExpression((it - neutral) / cfg.deviceMax, cfg) }
            if (raw != null) {
                // 校准快照维持旧语义（vtsRaw 反转域），校准存储格式不变
                expressionRaw[vtsKey] = if (cfg.invert) EXPRESSION_MAX - raw else raw
            }
            // 瞪眼叠加项：仅 EyeOpen 通道非 0（Wide 信号并入开眼输出），其余通道恒 0
            val wideBoost = eyeWideBoostTerm(vtsKey, cfg, arCoefficients, cal, sens)
            // 钳制上界逐通道：有瞪眼叠加的通道（现仅 EyeOpen 系）放宽到表情域满幅 1.0——
            // VTS 标准 EyeOpen 域 0~1（InputParameterListResponse 实测），否则上界
            // outputScale+wideBoostGain=0.65 会把 sens>1 的瞪眼截断；其余通道维持 outputScale
            val clampMax = if (cfg.wideBoostGain > 0f) EXPRESSION_MAX else cfg.outputScale
            result[vtsKey] = when (intensity) {
                null -> if (cfg.invert) {
                    // 丢 blink 帧：睁眼中性 + 叠加；sens 拉满时叠加项可超界，同样要钳
                    clampWithLog(cfg.outputScale + wideBoost, EXPRESSION_MIN, clampMax, vtsKey)
                } else {
                    EXPRESSION_MIN
                }
                else -> {
                    val mapped = (if (cfg.invert) {
                        // 反转通道先按开度算完再整体乘 outputScale（scale 不能塞进减法里，
                        // 否则半闭眼就触底 0），压缩后闭眼端仍精确归零
                        (EXPRESSION_MAX - intensity * cfg.gain * sens) * cfg.outputScale
                    } else {
                        intensity * cfg.gain * sens * cfg.outputScale
                    }) + wideBoost
                    clampWithLog(mapped, EXPRESSION_MIN, clampMax, vtsKey)
                }
            }
        }
        latestExpressionRaw = expressionRaw
        putSynthesizedParams(arCoefficients, cal, sens, result)
        putEyeDirection(arCoefficients, cal, result)
        // 脸部镜像后处理：必须在 latestExpressionRaw 赋值之后——校准快照恒为未镜像域，
        // 拨开关不会让已存校准基准静默失效（见 applyFaceMirror KDoc）
        applyFaceMirror(result)
        return VtsParams(faceFound = faceFound, parameters = result)
    }

    // ===================== 脸部镜像（全局输出后处理） =====================

    /**
     * 脸部镜像后处理：原地改写本帧输出 Map（[map] 的表情/合成/眼球，[mapHeadPose] 的头姿），
     * 开关关闭时直接返回——零分配、与无镜像版逐位一致。
     *
     * ## 为什么是「交换 + 取反」而不是单纯交换
     * 镜像 = 绕竖直轴的反射。通道分两类：
     *  - **成对通道**表示左右两侧的**身份**（EyeOpenLeft/Right 等），反射后身份互换 → 交换；
     *  - **水平分量**表示「朝向哪边」（MouthX、FaceAngleX…），反射后方向取负 → 取反；
     *  - **眼球 X 两者兼有**（左眼/右眼的身份 + 水平分量）→ 交换且取反。
     * 反例：若 EyeLeftX/EyeRightX 只交换不取反，用户双眼看向自己右侧时原值 (+1, +1)
     * 镜像后仍是 (+1, +1)，模型会看向与镜像画面相反的一侧。
     *
     * ## 交换表（9 对，见 [MIRROR_SWAP_PAIRS]）
     * EyeOpen / EyeSmile / EyeWide / MouthSmile / MouthSad / MouthDimple / MouthStretch 的
     * Left↔Right，加 EyeLeftY↔EyeRightY、EyeLeftX↔EyeRightX。
     * **眉毛整体豁免**：眉毛是竖直轴量（0.5 中性双极），反射水平轴
     * 本不该动它，交换后静息值出现上下错位——BrowLeftY/BrowRightY、
     * BrowOuterUpLeft/Right、BrowDownLeft/Right 全部纯直传，不进本表的任何一张。
     * **其余对称量不动**：BrowInnerUp、Brows（左右均值）、MouthSmile（左右 Smile 均值 −
     * Pucker）、MouthOpen、MouthFunnel、MouthPucker、CheekPuff、FaceAngleY、
     * FacePositionY、FacePositionZ。
     *
     * ## 取反表（水平分量，见 [MIRROR_NEGATE_KEYS]）
     * MouthX、EyeLeftX、EyeRightX、FaceAngleX（左右转头）、FaceAngleZ（左右歪头）、
     * FacePositionX（水平位移）。这些键的值域都关于 0 对称（[-1,1] / [-30,30] /
     * [-90,90] / [-15,15]），取反不可能越界，因此**不做二次 [clampWithLog]**——
     * 也不会污染按参数名节流的钳制告警表。
     *
     * ## 调用位置约束
     * 必须在 [map] 里 `latestExpressionRaw` 赋值**之后**、[mapHeadPose] 里
     * `latestHeadRaw` 赋值**之后**：校准快照恒存未镜像域，拨开关不会让已存校准基准失效。
     *
     * ## 线程模型
     * GL 线程逐帧调用，原地改写本帧新建的 LinkedHashMap（不开新 Map）；开关由 UI 线程写
     * [faceMirrorEnabled]，切换当帧即生效。两处 Map 各只命中自己那部分键，缺键跳过。
     */
    private fun applyFaceMirror(out: LinkedHashMap<String, Float>) {
        if (!faceMirrorEnabled) return
        // 1) 左右成对通道交换：先读两侧再回写，避免同一次交换互相覆盖
        for ((a, b) in MIRROR_SWAP_PAIRS) {
            val va = out[a]
            val vb = out[b]
            if (va == null || vb == null) continue
            out[a] = vb
            out[b] = va
        }
        // 2) 水平分量取反：放在交换之后，于是 EyeLeftX' = -EyeRightX、EyeRightX' = -EyeLeftX
        for (key in MIRROR_NEGATE_KEYS) {
            val v = out[key] ?: continue
            out[key] = -v
        }
    }

    // ===================== 表情强度整形（归一化域分段线性） =====================

    /**
     * 死区 + 可选饱和的分段线性（边界连续，无跳变）：
     *   norm <= deadZone          -> 0
     *   deadZone < norm < satEnd  -> (norm - deadZone) / (satEnd - deadZone)（重拉伸保满幅）
     *   norm >= satEnd            -> 1（satEnd = saturation > 0 时取 saturation，否则 1.0）
     * deadZone=0 且 saturation=0 时恒等返回原值——此时管线退化为线性映射。
     * 负 norm（原始值低于中性，静息噪声）同样归零，即 [0,1] 钳制下限。
     */
    private fun shapeExpression(norm: Float, cfg: ExpressionConfig): Float {
        val satEnd = if (cfg.saturation > 0f) cfg.saturation else EXPRESSION_MAX
        if (norm <= cfg.deadZone) return EXPRESSION_MIN
        if (norm >= satEnd) return EXPRESSION_MAX
        return (norm - cfg.deadZone) / (satEnd - cfg.deadZone)
    }

    // ===================== 合成通道与瞪眼叠加 =====================

    /**
     * 合成通道：多个 AR 源系数按公式合成单个 VTS 参数，在 [map] 主循环后追加输出，
     * 不进 [Companion.arToVtsMapping] 的 1:1 路径。现有成员：
     *  - MouthSmile：miku 模型只认单数名（左右分开的 SmileLeft/Right 不在可注入列表）。
     *    正段 = 左右 Smile 均值，负段 = Pucker 单一源（嘟嘴）——AR Engine 的「嘟嘴」语义
     *    是 Mouth_Pucker（嘴唇收拢），Mouth_Frown 是嘴角下垂、不是嘟嘴：负段读
     *    Frown 恒 0、嘟嘴出不来（Pucker 实测峰 0.82、Frown 恒 0）。
     *    各自去校准中性、归一化后相减，内部 combined 取 [-1, 1]（正 = 微笑、负 = 嘟嘴）；
     *    Smile 与 Pucker 同时激活时相减抵消。
     *    最终输出换算到 VTS 端语义域 [0, 1]：0.5f + combined × 0.5——
     *    0.5 = 无表情（中性）、> 0.5 = 微笑、< 0.5 = 嘟嘴（越接近 0 越嘟）。
     *    数值来源：VTS 默认映射；直接输出 [-1, 1] 时静息 0 被 VTS 当极端嘟嘴
     *    （-0.65 恒嘟嘴）。
     *    VTS 端配合：模型图形 MouthSmile 的 IN 若保持 0.3~0.8，负半段会被钳在静息
     *    0.3 永远出不了嘟嘴——需在 VTS 把 IN 改为 0~1 全量程并重设 OUT 中性锚点（App 侧无需改动）。
     *  - MouthX：Mouth_Left − Mouth_Right（嘴巴左右移动），输出 [-1, 1]（中性 0；
     *    VTS 标准 MouthX 域 -1~1，InputParameterListResponse 实测，combined 直出）。
     *    无 1:1 中性键——两个 AR 源对应同一个合成输出名，进 1:1 表会在主循环互相覆盖
     *    并与合成值冲突，中性回退 0。
     *  - BrowLeftY / BrowRightY / Brows：0.5 + combined × 0.5，输出 [0, 1]（双极语义：
     *    0.5 中性、>0.5 上扬、<0.5 皱眉）。左侧 combined = (InnerUp + OuterUpLeft)/2 −
     *    DownLeft，右侧对称（OuterUpRight/DownRight）；Brows = 左右 combined 均值
     *    （不用独立三分量权重——综合通道与单侧公式共用同一套
     *    权重，语义自洽且零重复计算）。miku 绑 Brows、akari 对 BrowLeftY/RightY 无响应；
     *    akari 注入 Brows 0/1 → BrowForm -0.75/+0.95，正负两段皆有响应，皱眉可表达。
     *    已知取舍：0.5 中性在 akari 上（OUT 线性假设下 BrowForm 零点位于 v≈0.44）
     *    约有 +0.098 常驻抬眉，v1 不加偏移参数，可见时走 VTS 端「重设中性锚点」
     *    （与 MouthSmile 同流程，零代码）。
     *    VTS 标准同时提供 BrowLeftY/BrowRightY/Brows 三个输入、模型参数自行选择绑定
     *    哪个（白名单自动过滤不支持的），三通道同时输出提高通用性。
     *    5 个 Brow 细分 1:1 输出被白名单过滤（akari 上眉毛全死）、条目保留不删
     *    （校准快照依赖 + 其他模型）；中性取 Brow 细分 1:1 名的校准快照。
     *
     * 中性取校准快照中各输入的 1:1 VTS 名（MouthX 除外、回退 0，见上），
     * 缺省回退 [Companion.EXPRESSION_BASE_NEUTRAL]；输入缺失跳过、不进均值分母
     * （单侧缺失不把均值减半）。
     * 整形走 [shapeExpression]，死区/饱和按正负半段分别生效、半段钳 1
     * （MouthSmile deadZone=0.15；MouthX / Brow*Y v1 恒等，调参锚点在 [channelConfigs]）。
     */
    private fun putSynthesizedParams(
        arCoefficients: Map<String, Float>,
        cal: CalibrationData,
        sens: Float,
        out: LinkedHashMap<String, Float>
    ) {
        val cfg = cfgFor(VTS_MOUTH_SMILE)

        // 半段均值：各输入去中性、归一化后取平均；分母取命中数（单侧缺失不减半），
        // 全部缺失返回 0（= 中性）
        fun halfNorm(specs: List<Pair<String, String>>, deviceMax: Float): Float {
            var sum = 0f
            var count = 0
            for ((arKey, vtsKey) in specs) {
                val raw = arCoefficients[arKey] ?: continue
                val neutral = cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_NEUTRAL
                sum += (raw - neutral) / deviceMax
                count++
            }
            return if (count == 0) 0f else sum / count
        }

        val combined = halfNorm(MOUTH_SMILE_POSITIVE, SMILE_DEVICE_MAX) -
                halfNorm(MOUTH_SMILE_NEGATIVE, PUCKER_DEVICE_MAX)
        // shapeExpression 只认非负强度域：正负段取绝对值分别整形再回贴符号
        val shaped = if (combined >= 0f) shapeExpression(combined, cfg)
        else -shapeExpression(-combined, cfg)
        // 输出 = 中性基座 + shaped × gain × sens × 半幅，钳 [0,1]：中性基座可被覆盖
        // （MouthSmile 中性点，默认 0.5），半幅固定——基座偏移后满笑/满嘟不对称
        // （上端到不了 1.0 / 下端钳 0），v1 只移基座不动半幅（语义见 MapperOverrides KDoc）
        val smileNeutralBase = (overrides?.mouthSmileNeutralBase ?: SYNTH_NEUTRAL_BASE)
            .coerceIn(EXPRESSION_MIN, EXPRESSION_MAX)
        out[VTS_MOUTH_SMILE] = clampWithLog(
            smileNeutralBase + shaped * cfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_MOUTH_SMILE
        )

        // ---------- MouthX：嘴巴左右移动 ----------
        // MouthX = Mouth_Left − Mouth_Right（"左减右"），未开镜像时方向为正。
        // 镜像开关对 MouthX 额外取反（见 applyFaceMirror），于是 OFF = 正常方向、
        // ON = 镜像方向。
        // 数值：deviceMax=1.0 恒等（探针 11 项未含 Mouth_Left/Right，无实测峰值，待补数据后调）；
        //       combined 本身取 [-1,1]，直出钳 [-1, 1]——VTS 标准 MouthX 域 -1~1；
        //       中性回退 0（无 1:1 中性键，见 AR_MOUTH_ 源键注释）。
        val mouthXCfg = channelConfigs[VTS_MOUTH_X] ?: DEFAULT_EXPRESSION_CONFIG
        // 方向可被覆盖翻转（默认 +1 = 「左减右」）。无 UI 入口，左右翻转由全局
        // 「脸部镜像」承担：本分支保留为底层兜底，overrides.mouthXInvert 恒为 null
        // → mouthXSign 恒 +1。取反语义相对**当前基准**（Left − Right）。
        val mouthXSign = if (overrides?.mouthXInvert == true) -1f else 1f
        val mouthXCombined = mouthXSign * ((arCoefficients[AR_MOUTH_LEFT] ?: 0f) -
                (arCoefficients[AR_MOUTH_RIGHT] ?: 0f)) / MOUTH_X_DEVICE_MAX
        val shapedMouthX = if (mouthXCombined >= 0f) shapeExpression(mouthXCombined, mouthXCfg)
        else -shapeExpression(-mouthXCombined, mouthXCfg)
        out[VTS_MOUTH_X] = clampWithLog(
            shapedMouthX * mouthXCfg.gain * sens,
            -MOUTH_X_OUTPUT_MAX, MOUTH_X_OUTPUT_MAX, VTS_MOUTH_X
        )

        // ---------- BrowLeftY / BrowRightY / Brows：眉毛（0.5 中性双极） ----------
        // VTS 标准三个输入并存、模型自行选绑（白名单自动过滤不支持的），三通道同时输出
        // 提高通用性：miku 的 Brow Height Left/Right 绑定 Brows，
        // akari 也只有 Brows 有响应（BrowLeftY/RightY 注入无变化）。
        // 5 个 Brow 细分 1:1 输出被 VTS 白名单过滤（akari 眉毛全死）；1:1 条目保留
        // （校准快照依赖）。
        // 公式：
        // combined = 上抬均值 − 下压（左侧 (InnerUp + OuterUpLeft)/2 − DownLeft，右侧对称；
        // Brows = 左右 combined 均值，与单侧公式共用同一套权重）；输出 = 0.5 + shaped × 0.5。
        // 0.5 中性、>0.5 上扬、<0.5 皱眉（akari：Brows 注入 0/1 → BrowForm
        // -0.75/+0.95，正负两段皆有响应）；shapeExpression 只认非负强度域，正负段取
        // 绝对值分别整形再回贴符号（与 MouthSmile 同模式）。已知取舍：0.5 中性在 akari
        // 上约有 +0.098 常驻抬眉（OUT 线性假设下 BrowForm 零点在 v≈0.44），v1 不加偏移
        // 参数，可见时走 VTS 端「重设中性锚点」（与 MouthSmile 同流程）。
        // deviceMax=1.0 为恒等取值——仅 BrowOuterUpLeft 有实测峰 ~0.46，其余 6 键
        //       （含 BrowDown 双键）无数据，待补实测后调整；左右方向语义（前置摄像头镜像）
        //       随眼球方向一并确认。
        // 归一化分母可被覆盖（手动「眉毛灵敏度」倍率经此换算；三通道共用同一分母；
        // 防御性钳制防损坏输入产生超界增益）
        val browDeviceMax = (overrides?.browDeviceMax ?: BROW_DEVICE_MAX)
            .coerceIn(OVERRIDE_DEVICE_MAX_MIN, OVERRIDE_DEVICE_MAX_MAX)
        val browLeftCombined = halfNorm(BROW_UP_LEFT, browDeviceMax) -
                halfNorm(BROW_DOWN_LEFT, browDeviceMax)
        val browRightCombined = halfNorm(BROW_UP_RIGHT, browDeviceMax) -
                halfNorm(BROW_DOWN_RIGHT, browDeviceMax)
        val browLeftCfg = channelConfigs[VTS_BROW_LEFT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browRightCfg = channelConfigs[VTS_BROW_RIGHT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browsCfg = channelConfigs[VTS_BROWS] ?: DEFAULT_EXPRESSION_CONFIG
        // 正负分段整形（半段钳 1）；Brows 在归一化域取均值后再整形：v1 空配置恒等时
        // 与输出域平均逐位等价，将来调 deadZone/饱和时整形在增益前才是正确管线位置；
        // 三通道 cfg 相互独立
        val shapedBrowLeft = if (browLeftCombined >= 0f) shapeExpression(browLeftCombined, browLeftCfg)
        else -shapeExpression(-browLeftCombined, browLeftCfg)
        val shapedBrowRight = if (browRightCombined >= 0f) shapeExpression(browRightCombined, browRightCfg)
        else -shapeExpression(-browRightCombined, browRightCfg)
        val browsCombined = (browLeftCombined + browRightCombined) / 2f
        val shapedBrows = if (browsCombined >= 0f) shapeExpression(browsCombined, browsCfg)
        else -shapeExpression(-browsCombined, browsCfg)
        // 输出 = 0.5 中性基座 + shaped × gain × sens × 0.5 半幅，钳 [0,1]。
        // **基座/半幅不可覆盖**：双极通道的中性 = 模型静息姿态，
        // 自适应探测只能反推 Live2D 声明量程 [min,max] 的中点，两者不等价时算出的基座会
        // 永久偏离 0.5（三个眉通道静息值长期低位、皱眉半段被 [0,1] 钳死）。
        // 恒用 SYNTH_NEUTRAL_BASE / SYNTH_HALF_RANGE；模型间差异改由
        // browDeviceMax（手动「眉毛灵敏度」）或
        // VTS 端「重设中性锚点」承担（与 MouthSmile 同流程，零代码）。
        // 增益/灵敏度只乘偏移段，基座不动，sens>1 越界由钳制兜底，与 MouthSmile 同构。
        out[VTS_BROW_LEFT_Y] = clampWithLog(
            SYNTH_NEUTRAL_BASE + shapedBrowLeft * browLeftCfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_BROW_LEFT_Y
        )
        out[VTS_BROW_RIGHT_Y] = clampWithLog(
            SYNTH_NEUTRAL_BASE + shapedBrowRight * browRightCfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_BROW_RIGHT_Y
        )
        out[VTS_BROWS] = clampWithLog(
            SYNTH_NEUTRAL_BASE + shapedBrows * browsCfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_BROWS
        )
    }

    /**
     * EyeOpen 的瞪眼叠加项：Wide 信号（Animoji_Eye_Wide 系）归一化整形后乘叠加增益，
     * 把开眼输出从正常段（0~0.5）扩到瞪眼段（0~0.65）。Wide 闭眼帧恒 0，
     * 叠加不干扰眨眼；死区 0.10 挡静息噪声。
     * 非 EyeOpen 通道、或 Wide 源缺失/低于死区时返回 0——叠加项不参与。
     * Wide 中性取校准快照（EyeWide 系 1:1 名）；归一化分母与死区独立于 EyeWide
     * 1:1 输出通道的配置（channelConfigs 不为其加条目，1:1 输出零行为变化）。
     */
    private fun eyeWideBoostTerm(
        vtsKey: String,
        cfg: ExpressionConfig,
        arCoefficients: Map<String, Float>,
        cal: CalibrationData,
        sens: Float
    ): Float {
        if (cfg.wideBoostGain <= 0f) return 0f
        val (arKey, neutralKey) = EYE_WIDE_SOURCE[vtsKey] ?: return 0f
        val wideRaw = arCoefficients[arKey] ?: return 0f
        val neutral = cal.baseExpressions[neutralKey] ?: EXPRESSION_BASE_NEUTRAL
        val shapedWide = shapeExpression((wideRaw - neutral) / WIDE_BOOST_DEVICE_MAX, WIDE_BOOST_SHAPE)
        return shapedWide * cfg.gain * sens * cfg.wideBoostGain
    }

    // ===================== 眼球方向（瞪眼 EyeY 补偿） =====================

    /**
     * 眼球注视方向（-1..1）。VTS 默认参数为 EyeLeftX/EyeLeftY/EyeRightX/EyeRightY，
     * 不存在 EyesX/EyesY——注入不存在的参数会被 VTS 逐次返回 APIError。
     * 不参与灵敏度缩放；校准数据仅用于瞪眼补偿的 Wide 中性。
     * 左右方向语义未经核对（前置摄像头可能左右镜像）。实际取值为
     * `EyeLeftX = Animoji_Left_Eyeball_Right − Animoji_Left_Eyeball_Left`（右眼对称），
     * 若方向相反，需对调这两个源键（不存在 Look_Left/Right 参数——VTS 端只有
     * EyeLeftX/EyeLeftY/EyeRightX/EyeRightY）。
     *
     * 瞪眼 EyeY 补偿：瞪眼时眼球下移、让瞪眼更明显——
     * 同侧 Wide 信号经 [shapeExpression] 整形（复用 [Companion.WIDE_BOOST_SHAPE]，
     * deadZone=0.10 挡静息噪声；分母 [Companion.WIDE_BOOST_DEVICE_MAX]）后从
     * EyeLeftY/EyeRightY 中减去 shapedWide × [Companion.EYE_WIDE_EYE_Y_COMPENSATION]
     * （0.35，见常量 KDoc），钳制后范围仍 [-1, 1]。
     * 只作用 Y 轴、X 轴不动；Wide 源缺失时补偿为 0（回退原值）。
     */
    private fun putEyeDirection(
        ar: Map<String, Float>,
        cal: CalibrationData,
        out: LinkedHashMap<String, Float>
    ) {
        fun v(key: String) = ar[key] ?: 0f
        // Wide 整形值：EYE_WIDE_SOURCE 的键是 EyeOpen 通道名，左右眼球与同侧 EyeOpen 一一对应
        fun wideShaped(eyeOpenKey: String): Float {
            val (arKey, neutralKey) = EYE_WIDE_SOURCE[eyeOpenKey] ?: return 0f
            val wideRaw = ar[arKey] ?: return 0f
            val neutral = cal.baseExpressions[neutralKey] ?: EXPRESSION_BASE_NEUTRAL
            return shapeExpression((wideRaw - neutral) / WIDE_BOOST_DEVICE_MAX, WIDE_BOOST_SHAPE)
        }
        out["EyeLeftX"] = (v("Animoji_Left_Eyeball_Right") - v("Animoji_Left_Eyeball_Left"))
            .coerceIn(-1f, 1f)
        out["EyeLeftY"] = (v("Animoji_Left_Eyeball_Up") - v("Animoji_Left_Eyeball_Down") -
                wideShaped("EyeOpenLeft") * EYE_WIDE_EYE_Y_COMPENSATION)
            .coerceIn(-1f, 1f)
        out["EyeRightX"] = (v("Animoji_Right_Eyeball_Right") - v("Animoji_Right_Eyeball_Left"))
            .coerceIn(-1f, 1f)
        out["EyeRightY"] = (v("Animoji_Right_Eyeball_Up") - v("Animoji_Right_Eyeball_Down") -
                wideShaped("EyeOpenRight") * EYE_WIDE_EYE_Y_COMPENSATION)
            .coerceIn(-1f, 1f)
    }

    // ===================== 头部姿态映射（ARFace Pose -> VTS） =====================

    /**
     * 头姿映射：ARFace 姿态 -> VTS FaceAngleX/Y/Z + FacePositionX/Y/Z
     * （含校准偏移 + 灵敏度缩放 + 安全范围钳制）。
     *
     * ## 坐标系
     * - AR Engine（官方约定）：X+ 向左、Y+ 向上、Z+ 向外（指向前置摄像头前方的用户方向）。
     *   平移单位为米；旋转为世界系四元数。
     * - VTube Studio（官方 Model Settings 输入参数表）：
     *   FaceAngleX = 脸左右转（yaw）、FaceAngleY = 脸上下点（pitch）、FaceAngleZ = 脸左右歪（roll）；
     *   FacePositionX/Y/Z = 脸的水平 / 垂直 / 距摄像头远近位置。
     *   官方 wiki 未标注任何参数的正方向与量程，因此下方所有正负号均为按右手法则的
     *   推导值 + VTS 社区惯例（iFacialMocap 桥接）假设，逐项标注见下方注释。
     *
     * ## 轴对应关系（按物理意义，与俗称 roll/pitch/yaw 不是一一对应）
     * - 绕 Y（竖直轴）旋转 = 左右转头 -> FaceAngleX
     * - 绕 X（左右水平轴）旋转 = 上下点头 -> FaceAngleY
     * - 绕 Z（前后轴/视线轴）旋转 = 左右歪头 -> FaceAngleZ
     *
     * @param translation ARPose.getTranslation 的输出 [x, y, z]，单位米。
     * @param quaternion  ARPose.getRotationQuaternion 的输出 [x, y, z, w]。
     * @return 6 个 VTS 头姿参数（角度单位为度；超出安全范围时钳制并节流告警）。
     */
    fun mapHeadPose(translation: FloatArray, quaternion: FloatArray): Map<String, Float> {
        val eulerRad = quaternionToEuler(quaternion[0], quaternion[1], quaternion[2], quaternion[3])
        val rotXDeg = Math.toDegrees(eulerRad[0].toDouble()).toFloat() // 绕 X（左右水平轴）
        val rotYDeg = Math.toDegrees(eulerRad[1].toDouble()).toFloat() // 绕 Y（竖直轴）
        val rotZDeg = Math.toDegrees(eulerRad[2].toDouble()).toFloat() // 绕 Z（前后轴）

        // 换算后的 VTS 域原始值（未校准）：同时缓存进快照，供「校准」捕获当前帧头姿
        val angleRaw = linkedMapOf(
            // 绕 +Y 正旋转（右手法则，拇指沿 Y+ 向上）使脸的朝向(+Z)转向 +X(左) ＝ 向左看；
            // 假设 VTS FaceAngleX+ = 向左转。注：符号未经真机确认，若方向相反需在此处改为 -rotYDeg
            "FaceAngleX" to rotYDeg * ANGLE_SCALE,
            // 绕 +X 正旋转（拇指沿 X+ 向左）使视线转向 -Y(下) ＝ 低头；
            // 假设 VTS FaceAngleY+ = 抬头。注：符号未经真机确认，若方向相反需在此处改为 +rotXDeg
            "FaceAngleY" to -rotXDeg * ANGLE_SCALE,
            // 绕 +Z 正旋转（拇指沿 Z+ 朝摄像头）使头顶(+Y)偏向 -X；前置摄像头是镜像视角，
            // VTS 又是「照镜子」语义，此符号最不可靠。注：符号未经真机确认，若方向相反需在此处改为 +rotZDeg
            "FaceAngleZ" to -rotZDeg * ANGLE_SCALE
        )
        val positionRaw = linkedMapOf(
            // 平移是世界系原点（会话启动时的相机位姿）起的绝对量，含初始距离/偏移；
            // 校准基准（basePositions）即实现「基线归零」。注：符号与各轴 SCALE 未经真机确认
            "FacePositionX" to translation[0] * POSITION_SCALE_XY,
            "FacePositionY" to translation[1] * POSITION_SCALE_XY,
            // Z 取负：AR 的 Z+ 指向用户（远离摄像头），靠近时 translation[2] 变小，
            // VTS 期望「靠近 = 正」——方向翻转在换算系数符号里完成
            "FacePositionZ" to translation[2] * POSITION_SCALE_Z
        )
        latestHeadRaw = angleRaw + positionRaw

        val cal = calibrationData
        val angleSens = angleSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val positionSens = positionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

        val result = LinkedHashMap<String, Float>(6)
        for ((key, raw) in angleRaw) {
            // 头姿角度：mapped = (Raw - Base) * 灵敏度；中性基准 0 且灵敏度 1 时直传。
            // 量程逐轴（InputParameterListResponse 实测）：
            // X/Y（转头/点头）[-30,30]、Z（歪头）[-90,90]
            val base = cal.baseAngles[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FaceAngleZ") ANGLE_Z_MIN to ANGLE_Z_MAX
            else ANGLE_XY_MIN to ANGLE_XY_MAX
            result[key] = clampWithLog((raw - base) * angleSens, min, max, key)
        }
        for ((key, raw) in positionRaw) {
            // 头部位置：mapped = (Raw - Base) * 灵敏度；校准后初始距离/偏移归零。
            // 量程逐轴（InputParameterListResponse 实测）：
            // X/Y [-15,15]、Z（远近）[-10,10]
            val base = cal.basePositions[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FacePositionZ") POSITION_Z_MIN to POSITION_Z_MAX
            else POSITION_XY_MIN to POSITION_XY_MAX
            result[key] = clampWithLog((raw - base) * positionSens, min, max, key)
        }
        // 脸部镜像后处理：latestHeadRaw 已在上面赋值，校准快照恒为未镜像域（同 map 内约束）
        applyFaceMirror(result)
        return result
    }

    // ===================== 钳制 + 节流告警 =====================

    /** 每参数钳制告警节流表：key -> 上次告警的 elapsedRealtime（多线程仅 GL 线程写，安全兜底用并发表）。 */
    private val lastClampWarnAt = ConcurrentHashMap<String, Long>()

    /**
     * 钳制到 [min, max]；若发生截断，按参数名节流输出告警（30~60Hz 下防止刷屏）。
     */
    private fun clampWithLog(value: Float, min: Float, max: Float, key: String): Float {
        val clamped = value.coerceIn(min, max)
        if (clamped != value) {
            val now = SystemClock.elapsedRealtime()
            val last = lastClampWarnAt[key] ?: 0L
            if (now - last >= CLAMP_WARN_INTERVAL_MS) {
                lastClampWarnAt[key] = now
                Log.w(TAG, "参数 $key 超出安全范围已截断：$value -> $clamped（允许 [$min, $max]）")
            }
        }
        return clamped
    }

    /**
     * 四元数 -> 欧拉角（Tait-Bryan 角，ZYX 分解，标准换算公式，参考 Wikipedia
     * "Conversion between quaternions and Euler angles"）。
     *
     * @param x y z w 四元数分量，ARPose.getRotationQuaternion 的 (x, y, z, w) 顺序。
     * @return [roll, pitch, yaw]（弧度）：roll = 绕 X 轴旋转角、pitch = 绕 Y 轴旋转角、yaw = 绕 Z 轴旋转角。
     *         这是「绕世界系哪个轴」的命名（航空惯例），与头部运动的俗称不同——
     *         绕 Y = 转头、绕 X = 点头、绕 Z = 歪头，对应关系见 [mapHeadPose]。
     *         pitch 的 asin 入参已钳制到 [-1, 1]（万向锁附近数值安全）。
     */
    fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y)) // 绕 X
        val pitch = asin((2f * (w * y - z * x)).coerceIn(-1f, 1f))       // 绕 Y
        val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z)) // 绕 Z
        return floatArrayOf(roll, pitch, yaw)
    }

    companion object {

        private const val TAG = "BlendShapeMapper"

        /** AR Engine（Animoji_*）-> VTS 标准输入参数 映射表。 */
        val arToVtsMapping: Map<String, String> = linkedMapOf(
            // 眼睛（Blink 语义相反：1=闭眼，映射为 EyeOpen 需反转，反转配置见 [channelConfigs]）
            "Animoji_Eye_Blink_Left" to "EyeOpenLeft",
            "Animoji_Eye_Blink_Right" to "EyeOpenRight",
            "Animoji_Eye_Squint_Left" to "EyeSmileLeft",
            "Animoji_Eye_Squint_Right" to "EyeSmileRight",
            "Animoji_Eye_Wide_Left" to "EyeWideLeft",
            "Animoji_Eye_Wide_Right" to "EyeWideRight",
            // 眉毛
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Left" to "BrowOuterUpLeft",
            "Animoji_Brow_Outer_Up_Right" to "BrowOuterUpRight",
            "Animoji_Brow_Down_Left" to "BrowDownLeft",
            "Animoji_Brow_Down_Right" to "BrowDownRight",
            // 嘴
            "Animoji_Jaw_Open" to "MouthOpen",
            "Animoji_Mouth_Funnel" to "MouthFunnel",
            "Animoji_Mouth_Pucker" to "MouthPucker",
            "Animoji_Mouth_Smile_Left" to "MouthSmileLeft",
            "Animoji_Mouth_Smile_Right" to "MouthSmileRight",
            "Animoji_Mouth_Frown_Left" to "MouthSadLeft",
            "Animoji_Mouth_Frown_Right" to "MouthSadRight",
            "Animoji_Mouth_Dimple_Left" to "MouthDimpleLeft",
            "Animoji_Mouth_Dimple_Right" to "MouthDimpleRight",
            "Animoji_Mouth_Stretch_Left" to "MouthStretchLeft",
            "Animoji_Mouth_Stretch_Right" to "MouthStretchRight",
            // 脸颊
            "Animoji_Cheek_Puff" to "CheekPuff"

        )
        // 以下 AR 系数暂未映射，待核对所用 VTS 模型的输入参数绑定后补充：
        //  Mouth_Roll_Lower/Upper, Mouth_Shrug_Lower/Upper, Mouth_Upper_Up, Mouth_Lower_Down,
        //  Mouth_Left/Right(下颌左右), Jaw_Forward, Cheek_Squint_L/R, Tongue_*,
        //  Frown_Nose_Mouth_Up, HealthParameter 项
        //  miku 模型实测（可注入列表）不含：MouthPucker、EyeSmileLeft/Right、
        //  EyeWideLeft/Right、Brow 细分 5 名、MouthSmileLeft/Right 等左右分开名、MouthFunnel——
        //  1:1 条目一律保留（其他模型可用 + 校准快照依赖，App 端白名单过滤后不出网），
        //  miku 上由合成通道 MouthSmile 与 EyeOpen 瞪眼叠加补齐；
        //  Mouth_Shrug_Lower/Upper、Mouth_Roll_Lower/Upper 维持不映射。

        /**
         * 单个表情通道的重映射配置。缺省值即「无整形」：deviceMax=1、gain=1、无死区/饱和、
         * 不反转、outputScale=1——此时管线退化为线性映射（推导见类 KDoc「处理链」）。
         * deviceMax 语义：以校准中性为零点、满幅表情时的原始系数摆幅（非绝对峰值）；
         * 归一化分母只取该常数、不随中性点变化。
         * outputScale 语义：输出量程上限，钳制上界随之收紧为 [0, outputScale]；
         * 反转通道按开度整体压缩（(1 - shaped×gain×sens) × outputScale），保持输出线性比例。
         * data class 仅为 cfgFor 的覆盖合并提供 copy()。
         */
        private data class ExpressionConfig(
            val deviceMax: Float = 1.0f,
            val gain: Float = 1.0f,
            val deadZone: Float = 0f,
            val saturation: Float = 0f,
            val invert: Boolean = false,
            val outputScale: Float = 1.0f,
            /**
             * EyeOpen 专属瞪眼叠加增益：Wide 信号归一化整形后乘此值加进开眼输出
             * （0.15 = 0.5 正常开眼基线之上再扩 0.15，正常瞪眼到达 0.65）。默认 0 = 无叠加，
             * 其余通道零行为变化；目标模型若绑定了独立的 EyeWideLeft/Right 参数，
             * 应置 0 防止瞪眼双计（独立输出与叠加同时生效）。
             * 钳制余量：有叠加的通道上界为表情域满幅 1.0（见 map 内
             * clampMax 注释），Wide 极强 + sens>1 时输出可超 0.65。
             */
            val wideBoostGain: Float = 0f,
        )

        /** 全通道共用缺省配置（无整形）：未列入 [channelConfigs] 的通道即用它。 */
        private val DEFAULT_EXPRESSION_CONFIG = ExpressionConfig()

        /**
         * 每通道重映射配置表（按 VTS 输出名索引）。只列有实测证据的通道，其余通道
         * 走缺省（无整形）：
         *  - EyeOpen 系：本机 Blink 摆幅约 0.5，归一化后 1.00x 即满闭；死区 0.10 抑制
         *    静息 Blink 噪声（反转前的强度域运算）；
         *    outputScale=0.5：VTS 端 IN 0.50 -> OUT 0.97 才是正常开眼、
         *    IN 0.65 -> OUT 1.20 已是过驱瞪眼、IN 0.00 -> OUT 0.00 全闭——
         *    输出量程压缩为校准睁眼 = 0.5、满闭 = 0，前半量程不会被 VTS 当过驱消耗；
         *  - EyeSmile 系：本机 Squint 满幅峰值约 0.22，deviceMax 取该峰值；静息噪声（睁眼帧）
         *    p99 = 0.034、max = 0.077，死区 0.10（归一化域 = raw 0.022）在其上方且
         *    不吃轻眯信号（raw 0.06~0.08 仍可透过）；VTS 端 Smile 期望 0~1 满幅，不压缩；
         *  - MouthOpen（源系数 Animoji_Jaw_Open）：deviceMax=0.30 为 Jaw_Open 实测峰值
         *    （缺省 1.0 时正常说话 raw 0.05~0.15 只输出 0.025~0.075，VTS 几乎不动）；
         *    outputScale=0.5——VTS 端 IN 0.51 -> OUT 1.00 即满幅，App 满幅输出对齐 0.5；
         *  - EyeOpen 系 wideBoostGain=0.15：瞪眼叠加（见 [eyeWideBoostTerm]），Wide 实测峰
         *    WL 0.973 / WR 1.000、闭眼帧 Wide 恒 0；正常瞪眼到达 0.65（0.5 基线 + 0.15
         *    叠加），钳制上界放宽至表情域满幅 1.0（VTS 标准 EyeOpen 域 0~1，sens>1 不截断，
         *    见 map 内 clampMax 注释）；
         *  - MouthSmile：合成通道（见 [putSynthesizedParams]）；deadZone=0.15（归一化域
         *    = raw 0.128）压静息 Pucker 噪声——静息 Pucker 0.082，零死区时
         *    0.082/0.85=0.096 穿透死区输出 0.452 轻微嘟嘴；代价是 raw < 0.128 的
         *    轻嘟嘴/轻微笑低段被吃（有意嘟嘴峰 0.82，重拉伸保满幅、主体动态无损）；
         *  - MouthX / BrowLeftY / BrowRightY / Brows：合成通道（见 [putSynthesizedParams]），v1 恒等
         *    （归一化分母是常量 MOUTH_X_DEVICE_MAX / BROW_DEVICE_MAX，探针无实测数据；
         *    Brows 输入为左右 combined 均值，共用 BROW_DEVICE_MAX），
         *    deadZone/饱和锚点留在此处（静息眉毛若微抖，参照 WIDE_BOOST_SHAPE
         *    先例给三通道加 deadZone=0.10）；
         *  - 目前取值 0.5 / 0.22 / 0.30 / 0.15 / 0.85 为单机实测推断，
         *    满幅校准落地后由校准数据覆盖。
         */
        private val channelConfigs: Map<String, ExpressionConfig> = linkedMapOf(
            // wideBoostGain=0.15：瞪眼叠加，正常瞪眼到达 0.65（VTS 端 IN 0~0.65 已配），
            // 钳制上界放宽至 1.0（VTS 标准 EyeOpen 域 0~1，见 map 内 clampMax）
            "EyeOpenLeft" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            "EyeOpenRight" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            // deviceMax=0.22：Squint 实测峰值（0.1 会 2.2 倍过冲）
            "EyeSmileLeft" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            "EyeSmileRight" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            // VTS 实测：IN 0.51 -> OUT 1.00 满幅，输出量程压缩到 [0, 0.5]
            // deviceMax=0.30：raw_probe.txt 实测 Jaw_Open 峰值，漏配 1.0 时说话不动
            "MouthOpen" to ExpressionConfig(deviceMax = 0.30f, outputScale = 0.5f),
            // MouthSmile：合成通道（putSynthesizedParams），正段左右 Smile 均值 − 负段 Pucker 嘟嘴；
            // deadZone=0.15 压静息 Pucker 噪声（依据与代价见 channelConfigs KDoc）
            "MouthSmile" to ExpressionConfig(deadZone = 0.15f),
            // MouthX / BrowLeftY / BrowRightY / Brows：合成通道（putSynthesizedParams，公式见
            // 函数内注释），v1 恒等（归一化分母在常量里），deadZone/饱和锚点留在此处
            "MouthX" to ExpressionConfig(),
            "BrowLeftY" to ExpressionConfig(),
            "BrowRightY" to ExpressionConfig(),
            // Brows：左右合并输出（miku 的 Brow Height 实测绑定它），
            // 左右 combined 均值（0.5 中性双极，见 putSynthesizedParams）走本 cfg 整形；
            // 归一化分母共用 BROW_DEVICE_MAX（左右已除过）
            "Brows" to ExpressionConfig(),
        )

        // ---------- 合成通道与瞪眼叠加的常量（putSynthesizedParams / eyeWideBoostTerm 用） ----------

        /** 合成输出参数名（miku 模型实测支持单数名 MouthSmile；左右分开名不在可注入列表）。 */
        private const val VTS_MOUTH_SMILE = "MouthSmile"

        /** MouthSmile 正段输入：AR Smile 源键 -> VTS 1:1 名（后者兼作校准中性键）。 */
        private val MOUTH_SMILE_POSITIVE = listOf(
            "Animoji_Mouth_Smile_Left" to "MouthSmileLeft",
            "Animoji_Mouth_Smile_Right" to "MouthSmileRight"
        )

        /** MouthSmile 负段输入（嘟嘴）：AR Pucker 源键 -> VTS 1:1 名（后者兼作校准中性键）。 */
        private val MOUTH_SMILE_NEGATIVE = listOf(
            "Animoji_Mouth_Pucker" to "MouthPucker"
        )

        /**
         * 合成输入的归一化分母（实测峰）：
         * Smile 左 0.893 / 右 0.780（均值 0.84）取 0.85；Pucker 峰 0.824 同取 0.85（左右对称）。
         * SmileL 实测峰 0.893 略超 deviceMax，超出部分由半段钳 1 兜底。
         * 证据等级与 Squint 0.22 / Jaw 0.30 相同，满幅校准落地后由校准数据覆盖。
         */
        private const val SMILE_DEVICE_MAX = 0.85f
        private const val PUCKER_DEVICE_MAX = 0.85f

        // ---------- MouthX / 眉毛合成通道常量（putSynthesizedParams 用） ----------

        /**
         * 合成输出参数名（VTS 标准输入，InputParameterListResponse 实测）：
         * MouthX [-1,1] 双极中性 0；BrowLeftY/BrowRightY/Brows [0,1]（单极，非 -1~1）。
         */
        private const val VTS_MOUTH_X = "MouthX"
        private const val VTS_BROW_LEFT_Y = "BrowLeftY"
        private const val VTS_BROW_RIGHT_Y = "BrowRightY"

        /** Brows：VTS 标准的左右合并输入（miku 的 Brow Height 实测绑定它）。 */
        private const val VTS_BROWS = "Brows"

        /**
         * MouthX 源键：嘴巴向右 / 向左。不进 arToVtsMapping——两个 AR 源对应同一个
         * 合成输出名，进 1:1 表会在主循环里互相覆盖、并按 1:1 公式发出与合成值冲突的
         * MouthX；校准中性因此无 1:1 键可取、回退 0（静息若有漂移，实测后再议）。
         */
        private const val AR_MOUTH_RIGHT = "Animoji_Mouth_Right"
        private const val AR_MOUTH_LEFT = "Animoji_Mouth_Left"

        /**
         * 合成输入的归一化分母（恒等 1.0）：Mouth_Left/Right 在探针 11 项之外、
         * 无实测峰值；眉毛仅 BrowOuterUpLeft 有实测峰 ~0.46（logcat），
         * 其余 6 键（含 BrowDown 双键）无数据。
         * 待补测后调整（EyeSmile 取 0.1 会致 2.2 倍过冲）。
         */
        private const val MOUTH_X_DEVICE_MAX = 1.0f
        private const val BROW_DEVICE_MAX = 1.0f

        // ---------- 覆盖层（MapperOverrides 读取点）共用常量 ----------

        /** 合成双极通道的中性基座（0.5 中性语义）。MouthSmile 可被覆盖层移动，
         *  眉毛三通道恒用本值、不可覆盖。 */
        private const val SYNTH_NEUTRAL_BASE = 0.5f
        /** 合成双极通道的半幅（基座 ± 半幅 = 输出满量程 [0,1]）。眉毛恒用本值。 */
        private const val SYNTH_HALF_RANGE = 0.5f

        /**
         * deviceMax 类覆盖值（mouthOpenDeviceMax / browDeviceMax）的防御性钳制范围：
         * 来自持久化 JSON / 手动滑块，损坏输入不得产生超界增益——下界防除以近零分母
         * 把静息噪声放大成满幅，上界防灵敏度完全失效。
         */
        private const val OVERRIDE_DEVICE_MAX_MIN = 0.05f
        private const val OVERRIDE_DEVICE_MAX_MAX = 2f

        /** MouthX 输出边界：VTS 标准域 -1~1（InputParameterListResponse 实测）。
         *  BrowLeftY/BrowRightY 输出域为 [0,1] = 表情域 EXPRESSION_MIN/MAX。 */
        private const val MOUTH_X_OUTPUT_MAX = 1.0f

        /** 眉毛合成输入：AR 源键 -> VTS 1:1 名（后者兼作校准中性键；1:1 条目已存在）。 */
        private val BROW_UP_LEFT = listOf(
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Left" to "BrowOuterUpLeft"
        )
        private val BROW_UP_RIGHT = listOf(
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Right" to "BrowOuterUpRight"
        )

        /** BrowDown 下压段输入（双极方案纳入合成公式）。 */
        private val BROW_DOWN_LEFT = listOf(
            "Animoji_Brow_Down_Left" to "BrowDownLeft"
        )
        private val BROW_DOWN_RIGHT = listOf(
            "Animoji_Brow_Down_Right" to "BrowDownRight"
        )

        /**
         * EyeOpen -> (同侧 Wide 的 AR 源键, Wide 的 VTS 1:1 名 = 校准中性键)。
         * Wide 作 EyeOpen 的内部叠加信号（[eyeWideBoostTerm]）与 EyeY 瞪眼补偿信号
         * （[putEyeDirection]，按同侧 EyeOpen 通道名查表），不改变
         * EyeWideLeft/Right 的 1:1 输出路径。
         */
        private val EYE_WIDE_SOURCE = mapOf(
            "EyeOpenLeft" to ("Animoji_Eye_Wide_Left" to "EyeWideLeft"),
            "EyeOpenRight" to ("Animoji_Eye_Wide_Right" to "EyeWideRight")
        )

        /**
         * Wide 归一化分母：实测峰 WL 0.973 / WR 1.000（raw_probe.txt）≈ 满幅，
         * 取 1.0 恒等；死区 0.10 只作用于叠加项，EyeWide 1:1 输出通道不加条目（零行为变化）。
         * 静息不瞪眼时 EyeOpen 应稳定在 0.5——WR 存在 0.25~0.51 低频摆动，
         * 若穿透死区造成可见抖动，把 [WIDE_BOOST_SHAPE] 的 deadZone 提到 0.55。
         */
        private const val WIDE_BOOST_DEVICE_MAX = 1.0f
        private val WIDE_BOOST_SHAPE = ExpressionConfig(deadZone = 0.10f)

        /**
         * 瞪眼 EyeY 补偿系数：EyeLeftY/EyeRightY 减去 Wide整形值 × 本系数，
         * 瞪眼时眼球下移、放大瞪眼观感。只作用 Y 轴，Wide 源缺失时补偿为 0。
         * 后续可微调。
         * 见 [putEyeDirection]。
         */
        private const val EYE_WIDE_EYE_Y_COMPENSATION = 0.35f

        // ---------- 脸部镜像表（applyFaceMirror 用） ----------

        /**
         * 镜像时左右**身份互换**的成对通道（9 对，VTS 参数名 A ↔ B）。见 [applyFaceMirror]
         * 的「为什么是交换 + 取反」。表内键全部由 [map] 全量写出（1:1 主循环 + 合成通道 +
         * 眼球通道），因此镜像不会造成某一侧缺键；缺键时该对跳过（防御）。
         *
         * **眉毛整体豁免**：BrowLeftY / BrowRightY、
         * BrowOuterUpLeft/Right、BrowDownLeft/Right 全部不参与镜像——眉毛是**竖直轴**量
         * （0.5 中性双极），反射水平轴本就不该动它；交换后静息值出现上下错位，
         * 整体不进交换表，实现「眉毛完全绕开镜像」的纯直传。
         *
         * 其余刻意不含的对称量：BrowInnerUp（左右共用一个系数）、Brows（左右 combined 均值）、
         * MouthSmile（左右 Smile 均值 − Pucker）、MouthOpen / MouthFunnel / MouthPucker /
         * CheekPuff（无左右之分）。
         */
        private val MIRROR_SWAP_PAIRS = listOf(
            // 眼：开合 / 眯眼（EyeSmile）/ 瞪眼（EyeWide）
            "EyeOpenLeft" to "EyeOpenRight",
            "EyeSmileLeft" to "EyeSmileRight",
            "EyeWideLeft" to "EyeWideRight",
            // 嘴：1:1 左右分开名（眉毛不参与镜像，见上方豁免说明）
            "MouthSmileLeft" to "MouthSmileRight",
            "MouthSadLeft" to "MouthSadRight",
            "MouthDimpleLeft" to "MouthDimpleRight",
            "MouthStretchLeft" to "MouthStretchRight",
            // 眼球：Y 只交换（竖直轴不受反射影响），X 交换后再取反（见 MIRROR_NEGATE_KEYS）
            "EyeLeftY" to "EyeRightY",
            "EyeLeftX" to "EyeRightX"
        )

        /**
         * 镜像时**水平分量取反**的通道。值域均关于 0 对称（MouthX/EyeX [-1,1]、
         * FaceAngleX [-30,30]、FaceAngleZ [-90,90]、FacePositionX [-15,15]），
         * 取反不越界，不做二次钳制。
         *
         * 眼球 X 的语义是「该眼视线在脸坐标系里的水平分量」（左眼 = Eyeball_Right −
         * Eyeball_Left），正负表示朝向哪边而非鼻侧/颞侧——所以镜像时必须取反，
         * 只交换会让模型看向与镜像画面相反的方向。
         */
        private val MIRROR_NEGATE_KEYS = listOf(
            // 表情：嘴巴左右移动（合成通道）
            "MouthX",
            // 眼球：交换后的水平分量取反（执行顺序保证 = -对侧原值）
            "EyeLeftX",
            "EyeRightX",
            // 头姿：左右转头（yaw）/ 左右歪头（roll）/ 水平位移
            "FaceAngleX",
            "FaceAngleZ",
            "FacePositionX"
        )

        /**
         * 头部平移（米）-> VTS FacePosition 的换算系数（per-axis）。
         * 人脸距前置摄像头约 0.3~0.5 m，X/Y 乘 100 后约 30~50，超出部分由钳制截断；
         * 校准后以校准时刻的位置为零点，有效行程回到 ±[POSITION_XY_MAX] 内。
         * Z 取负：AR 的 Z+ 指向用户（远离摄像头），靠近时 translation[2] 变小，
         * 而 VTS 期望「靠近 = 正」——方向翻转在换算系数符号里完成。
         * Z 符号与校准基线域相反时基线失效：需重新校准一次，否则 Z 恒钳在边界。
         * 若模型实际位移幅度不符可在此微调（VTS 官方 wiki 未给出 FacePosition 的量纲）。
         */
        private const val POSITION_SCALE_XY = 100f
        private const val POSITION_SCALE_Z = -100f

        /**
         * 头部角度（度）的缩放系数：1f = 度数直传（再经校准偏移、灵敏度与安全范围钳制）。
         * VTS 标准量程（InputParameterListResponse 实测）：FaceAngleX/Y ±30、
         * FaceAngleZ ±90（官方 wiki：FaceAngleX=face right/left rotation、
         * FaceAngleY=face up/down rotation、FaceAngleZ=face lean rotation）。
         * 若转头满幅时模型只动一点点，则调大；过量截断则调小。
         */
        private const val ANGLE_SCALE = 1f

        // ---------- VTS 参数安全边界（InputParameterListResponse 实测） ----------
        /** FaceAngleX/Y（转头/点头）量程。 */
        const val ANGLE_XY_MIN = -30f
        const val ANGLE_XY_MAX = 30f
        /** FaceAngleZ（歪头）量程——VTS 标准比 X/Y 宽。 */
        const val ANGLE_Z_MIN = -90f
        const val ANGLE_Z_MAX = 90f
        /** FacePositionX/Y 量程。 */
        const val POSITION_XY_MIN = -15f
        const val POSITION_XY_MAX = 15f
        /** FacePositionZ（远近）量程。 */
        const val POSITION_Z_MIN = -10f
        const val POSITION_Z_MAX = 10f
        const val EXPRESSION_MIN = 0f
        const val EXPRESSION_MAX = 1f

        // ---------- 灵敏度范围（UI 滑块同区间） ----------
        const val SENSITIVITY_MIN = 0.1f
        const val SENSITIVITY_MAX = 3.0f
        const val SENSITIVITY_DEFAULT = 1.0f

        // ---------- 表情中性基准（按参数类别回退，见类文档） ----------
        /** 反转通道未校准时的中性 base（vtsRaw 域存 1.0，换算到 AR 原始域中性 = 0）。 */
        const val EXPRESSION_BASE_MAX = 1f
        /** 正向表情未校准时的中性基准：(Raw - 0) * 1.0 = Raw，恒等。 */
        const val EXPRESSION_BASE_NEUTRAL = 0f

        /** 同一参数钳制告警的最小间隔（毫秒），防止 30Hz 逐帧刷屏。 */
        private const val CLAMP_WARN_INTERVAL_MS = 5000L
    }
}