package com.exarry.arvtubelink.mapping

import android.os.SystemClock
import com.exarry.arvtubelink.utils.InAppLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2

/** 映射覆盖层：全字段可空，null = 该项无覆盖走硬编码默认 */
data class MapperOverrides(
    val mouthSmileNeutralBase: Float? = null,
    val mouthOpenDeviceMax: Float? = null,
    val mouthOpenOutputScale: Float? = null,
    val eyeOpenOutputScale: Float? = null,
    val browDeviceMax: Float? = null,
    val mouthXInvert: Boolean? = null
)

/** 映射层：AR Engine 表情与头姿 -> VTS 参数名，输出钳制到 VTS 安全量程 */
class BlendShapeMapper(
    calibration: CalibrationData = CalibrationData(),
    /** 头姿角度灵敏度，读取点钳制到 SENSITIVITY_MIN..MAX */
    @Volatile var angleSensitivity: Float = SENSITIVITY_DEFAULT,
    @Volatile var positionSensitivity: Float = SENSITIVITY_DEFAULT,
    @Volatile var expressionSensitivity: Float = SENSITIVITY_DEFAULT
) {

    /** 校准基准值，@Volatile 整体替换，GL 每帧读一次引用 */
    @Volatile
    var calibrationData: CalibrationData = calibration
        private set

    fun updateCalibration(data: CalibrationData) {
        calibrationData = data
    }

    /** 覆盖层，null = 无覆盖走硬编码默认；整体替换无锁 */
    @Volatile
    var overrides: MapperOverrides? = null
        private set

    fun updateOverrides(value: MapperOverrides?) {
        overrides = value
    }

    /** 脸部镜像开关：map / mapHeadPose 最终输出做左右成对互换 + 水平分量取反 */
    @Volatile
    var faceMirrorEnabled: Boolean = false

    /** 最新一帧表情原始值，VTS 输出域（已反转未校准），GL 写 UI 读 */
    @Volatile
    private var latestExpressionRaw: Map<String, Float> = emptyMap()

    /** 最新一帧头姿原始值，域同上 */
    @Volatile
    private var latestHeadRaw: Map<String, Float> = emptyMap()

    /** 合并表情与头姿快照，无追踪帧时返回 null */
    fun latestRawSnapshot(): Map<String, Float>? {
        val snapshot: Map<String, Float> = latestExpressionRaw + latestHeadRaw
        return if (snapshot.isEmpty()) null else snapshot
    }

    fun clearRawSnapshot() {
        latestExpressionRaw = emptyMap()
        latestHeadRaw = emptyMap()
    }

    /** 按 ANGLE_KEYS / POSITION_KEYS 分区整体替换校准基准，缺类回退中性 */
    fun captureCurrentAsBase(rawData: Map<String, Float>): CalibrationData {
        val angles = rawData.filterKeys { it in CalibrationData.ANGLE_KEYS }
        val positions = rawData.filterKeys { it in CalibrationData.POSITION_KEYS }
        val expressions = rawData.filterKeys {
            it !in CalibrationData.ANGLE_KEYS && it !in CalibrationData.POSITION_KEYS
        }
        if (angles.isEmpty()) {
            InAppLog.w(TAG, "校准捕获：快照缺头姿角度项（本帧未读到头姿？），角度基准回退中性 0")
        }
        if (positions.isEmpty()) {
            InAppLog.w(TAG, "校准捕获：快照缺头姿位置项，位置基准回退中性 0")
        }
        if (expressions.isEmpty()) {
            InAppLog.w(TAG, "校准捕获：快照缺表情项，表情基准回退中性 0/1")
        }

        val newCalibration = CalibrationData(
            baseAngles = angles,
            basePositions = positions,
            baseExpressions = expressions
        )
        calibrationData = newCalibration
        InAppLog.e(
            TAG,
            "校准捕获完成：角度 ${angles.size} 项 $angles，位置 ${positions.size} 项，表情 ${expressions.size} 项"
        )
        return newCalibration
    }

    /** 查表合并 overrides 覆盖字段，覆盖值防御性钳制 */
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

    /** 一帧 AR 表情系数 -> VtsParams，缺失输入按通道补中性值 */
    fun map(arCoefficients: Map<String, Float>): VtsParams {
        val faceFound = arCoefficients.isNotEmpty()
        val cal = calibrationData // volatile 读一次，帧内基准一致
        val sens = expressionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val expressionRaw = LinkedHashMap<String, Float>(arToVtsMapping.size)
        val result = LinkedHashMap<String, Float>(arToVtsMapping.size + 2)
        for ((arKey, vtsKey) in arToVtsMapping) {
            val cfg = cfgFor(vtsKey)
            val raw = arCoefficients[arKey]
            // 归一化零点取校准中性点；反转通道存 vtsRaw 域，换算回 1-base
            val neutral = if (cfg.invert) {
                EXPRESSION_MAX - (cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_MAX)
            } else {
                cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_NEUTRAL
            }
            // 整形在反转前做，静息噪声归零后反转，睁眼端仍满幅
            val intensity = raw?.let { shapeExpression((it - neutral) / cfg.deviceMax, cfg) }
            if (raw != null) {
                // 快照维持 vtsRaw 反转域
                expressionRaw[vtsKey] = if (cfg.invert) EXPRESSION_MAX - raw else raw
            }
            val wideBoost = eyeWideBoostTerm(vtsKey, cfg, arCoefficients, cal, sens)
            // 有瞪眼叠加的通道上界放宽到表情满幅 1.0（VTS 标准 EyeOpen 域 0~1），其余为 outputScale
            val clampMax = if (cfg.wideBoostGain > 0f) EXPRESSION_MAX else cfg.outputScale
            result[vtsKey] = when (intensity) {
                null -> if (cfg.invert) {
                    // 无 blink 输入帧按睁眼中性 + 叠加补值
                    clampWithLog(cfg.outputScale + wideBoost, EXPRESSION_MIN, clampMax, vtsKey)
                } else {
                    EXPRESSION_MIN
                }
                else -> {
                    val mapped = (if (cfg.invert) {
                        // 反转通道先算开度再整体乘 outputScale，否则半闭眼即触底 0
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
        // 镜像在快照赋值之后，校准快照恒为未镜像域
        applyFaceMirror(result)
        return VtsParams(faceFound = faceFound, parameters = result)
    }

    /** 原地左右镜像：成对通道交换后水平分量取反，眉毛为竖直轴量不进表 */
    private fun applyFaceMirror(out: LinkedHashMap<String, Float>) {
        if (!faceMirrorEnabled) return
        // 先读两侧再回写，避免同一次交换互相覆盖
        for ((a, b) in MIRROR_SWAP_PAIRS) {
            val va = out[a]
            val vb = out[b]
            if (va == null || vb == null) continue
            out[a] = vb
            out[b] = va
        }
        // 取反在交换之后，EyeLeftX 得 -EyeRightX
        for (key in MIRROR_NEGATE_KEYS) {
            val v = out[key] ?: continue
            out[key] = -v
        }
    }

    /** 死区 + 饱和分段线性，重拉伸保满幅；无死区饱和时恒等返回 */
    private fun shapeExpression(norm: Float, cfg: ExpressionConfig): Float {
        val satEnd = if (cfg.saturation > 0f) cfg.saturation else EXPRESSION_MAX
        if (norm <= cfg.deadZone) return EXPRESSION_MIN
        if (norm >= satEnd) return EXPRESSION_MAX
        return (norm - cfg.deadZone) / (satEnd - cfg.deadZone)
    }

    /** 合成通道：MouthSmile 与眉毛三通道为 0.5 中性双极，MouthX 直出 [-1,1] */
    private fun putSynthesizedParams(
        arCoefficients: Map<String, Float>,
        cal: CalibrationData,
        sens: Float,
        out: LinkedHashMap<String, Float>
    ) {
        val cfg = cfgFor(VTS_MOUTH_SMILE)

        // 各输入去中性后取均值，分母取命中数（单侧缺失不减半）
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
        // 中性基座可被覆盖，半幅固定，越界由 [0,1] 钳制兜底
        val smileNeutralBase = (overrides?.mouthSmileNeutralBase ?: SYNTH_NEUTRAL_BASE)
            .coerceIn(EXPRESSION_MIN, EXPRESSION_MAX)
        out[VTS_MOUTH_SMILE] = clampWithLog(
            smileNeutralBase + shaped * cfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_MOUTH_SMILE
        )

        // MouthX = Mouth_Left − Mouth_Right，deviceMax 1.0 为占位（探针未含两键）
        val mouthXCfg = channelConfigs[VTS_MOUTH_X] ?: DEFAULT_EXPRESSION_CONFIG
        // mouthXInvert 无 UI 写入点恒 null，方向翻转由脸部镜像承担
        val mouthXSign = if (overrides?.mouthXInvert == true) -1f else 1f
        val mouthXCombined = mouthXSign * ((arCoefficients[AR_MOUTH_LEFT] ?: 0f) -
                (arCoefficients[AR_MOUTH_RIGHT] ?: 0f)) / MOUTH_X_DEVICE_MAX
        val shapedMouthX = if (mouthXCombined >= 0f) shapeExpression(mouthXCombined, mouthXCfg)
        else -shapeExpression(-mouthXCombined, mouthXCfg)
        out[VTS_MOUTH_X] = clampWithLog(
            shapedMouthX * mouthXCfg.gain * sens,
            -MOUTH_X_OUTPUT_MAX, MOUTH_X_OUTPUT_MAX, VTS_MOUTH_X
        )

        // combined = 上抬均值 − 下压；deviceMax 1.0 为占位（仅 BrowOuterUpLeft 峰值 0.46）
        val browDeviceMax = (overrides?.browDeviceMax ?: BROW_DEVICE_MAX)
            .coerceIn(OVERRIDE_DEVICE_MAX_MIN, OVERRIDE_DEVICE_MAX_MAX)
        val browLeftCombined = halfNorm(BROW_UP_LEFT, browDeviceMax) -
                halfNorm(BROW_DOWN_LEFT, browDeviceMax)
        val browRightCombined = halfNorm(BROW_UP_RIGHT, browDeviceMax) -
                halfNorm(BROW_DOWN_RIGHT, browDeviceMax)
        val browLeftCfg = channelConfigs[VTS_BROW_LEFT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browRightCfg = channelConfigs[VTS_BROW_RIGHT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browsCfg = channelConfigs[VTS_BROWS] ?: DEFAULT_EXPRESSION_CONFIG
        // Brows 在归一化域先取左右均值再整形
        val shapedBrowLeft = if (browLeftCombined >= 0f) shapeExpression(browLeftCombined, browLeftCfg)
        else -shapeExpression(-browLeftCombined, browLeftCfg)
        val shapedBrowRight = if (browRightCombined >= 0f) shapeExpression(browRightCombined, browRightCfg)
        else -shapeExpression(-browRightCombined, browRightCfg)
        val browsCombined = (browLeftCombined + browRightCombined) / 2f
        val shapedBrows = if (browsCombined >= 0f) shapeExpression(browsCombined, browsCfg)
        else -shapeExpression(-browsCombined, browsCfg)
        // 基座/半幅恒用 SYNTH_NEUTRAL_BASE / SYNTH_HALF_RANGE，眉毛三通道不可覆盖
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

    /** Wide 归一化整形后乘 wideBoostGain 叠加到 EyeOpen，闭眼帧 Wide 恒 0 */
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

    /** 眼球方向 EyeLeftX/Y、EyeRightX/Y；EyeY 减 Wide 整形 × 0.35 作瞪眼补偿 */
    private fun putEyeDirection(
        ar: Map<String, Float>,
        cal: CalibrationData,
        out: LinkedHashMap<String, Float>
    ) {
        fun v(key: String) = ar[key] ?: 0f
        // 按同侧 EyeOpen 名查 Wide 源
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

    /** ARFace 姿态 -> FaceAngleX/Y/Z + FacePositionX/Y/Z，旋转绕 Y/X/Z 依次对应 */
    fun mapHeadPose(translation: FloatArray, quaternion: FloatArray): Map<String, Float> {
        val eulerRad = quaternionToEuler(quaternion[0], quaternion[1], quaternion[2], quaternion[3])
        val rotXDeg = Math.toDegrees(eulerRad[0].toDouble()).toFloat()
        val rotYDeg = Math.toDegrees(eulerRad[1].toDouble()).toFloat()
        val rotZDeg = Math.toDegrees(eulerRad[2].toDouble()).toFloat()

        // 换算后未校准的 VTS 域原始值，同时缓存进快照
        val angleRaw = linkedMapOf(
            // 绕 +Y 正旋转使脸朝向转向 +X，VTS FaceAngleX+ 假设为向左转，符号未在设备上验证
            "FaceAngleX" to rotYDeg * ANGLE_SCALE,
            // 绕 +X 正旋转使视线转向 -Y，VTS FaceAngleY+ 假设为抬头，符号未在设备上验证
            "FaceAngleY" to -rotXDeg * ANGLE_SCALE,
            // 前置摄像头是镜像视角且 VTS 为照镜语义，此符号最不可靠
            "FaceAngleZ" to -rotZDeg * ANGLE_SCALE
        )
        val positionRaw = linkedMapOf(
            // 平移为会话启动相机位姿起的绝对量，校准基准负责基线归零
            "FacePositionX" to translation[0] * POSITION_SCALE_XY,
            "FacePositionY" to translation[1] * POSITION_SCALE_XY,
            // Z 取负：AR 的 Z+ 指向用户，VTS 期望靠近为正
            "FacePositionZ" to translation[2] * POSITION_SCALE_Z
        )
        latestHeadRaw = angleRaw + positionRaw

        val cal = calibrationData
        val angleSens = angleSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val positionSens = positionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

        val result = LinkedHashMap<String, Float>(6)
        for ((key, raw) in angleRaw) {
            // 量程 X/Y [-30,30]、Z [-90,90]（InputParameterListResponse 报告值）
            val base = cal.baseAngles[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FaceAngleZ") ANGLE_Z_MIN to ANGLE_Z_MAX
            else ANGLE_XY_MIN to ANGLE_XY_MAX
            result[key] = clampWithLog((raw - base) * angleSens, min, max, key)
        }
        for ((key, raw) in positionRaw) {
            // 量程 X/Y [-15,15]、Z（远近）[-10,10]（InputParameterListResponse 报告值）
            val base = cal.basePositions[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FacePositionZ") POSITION_Z_MIN to POSITION_Z_MAX
            else POSITION_XY_MIN to POSITION_XY_MAX
            result[key] = clampWithLog((raw - base) * positionSens, min, max, key)
        }
        applyFaceMirror(result)
        return result
    }

    /** 每参数上次告警的 elapsedRealtime，仅 GL 线程写，并发表兜底 */
    private val lastClampWarnAt = ConcurrentHashMap<String, Long>()

    /** 钳制到 [min, max]，截断时按参数名节流告警 */
    private fun clampWithLog(value: Float, min: Float, max: Float, key: String): Float {
        val clamped = value.coerceIn(min, max)
        if (clamped != value) {
            val now = SystemClock.elapsedRealtime()
            val last = lastClampWarnAt[key] ?: 0L
            if (now - last >= CLAMP_WARN_INTERVAL_MS) {
                lastClampWarnAt[key] = now
                InAppLog.w(TAG, "参数 $key 超出安全范围已截断：$value -> $clamped（允许 [$min, $max]）")
            }
        }
        return clamped
    }

    /** 四元数 -> [roll, pitch, yaw] 弧度，ZYX 分解；pitch 的 asin 入参钳到 [-1,1] */
    fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y))
        val pitch = asin((2f * (w * y - z * x)).coerceIn(-1f, 1f))
        val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
        return floatArrayOf(roll, pitch, yaw)
    }

    companion object {

        private const val TAG = "BlendShapeMapper"

        /** AR Engine（Animoji_*）-> VTS 标准参数名 */
        val arToVtsMapping: Map<String, String> = linkedMapOf(
            // Blink 1 = 闭眼，EyeOpen 反转由 channelConfigs 的 invert 承担
            "Animoji_Eye_Blink_Left" to "EyeOpenLeft",
            "Animoji_Eye_Blink_Right" to "EyeOpenRight",
            "Animoji_Eye_Squint_Left" to "EyeSmileLeft",
            "Animoji_Eye_Squint_Right" to "EyeSmileRight",
            "Animoji_Eye_Wide_Left" to "EyeWideLeft",
            "Animoji_Eye_Wide_Right" to "EyeWideRight",
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Left" to "BrowOuterUpLeft",
            "Animoji_Brow_Outer_Up_Right" to "BrowOuterUpRight",
            "Animoji_Brow_Down_Left" to "BrowDownLeft",
            "Animoji_Brow_Down_Right" to "BrowDownRight",
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
            "Animoji_Cheek_Puff" to "CheekPuff"

        )

        /** 单通道重映射配置，缺省即恒等；deviceMax 是中性到满幅的原始摆幅，非绝对峰值 */
        private data class ExpressionConfig(
            val deviceMax: Float = 1.0f,
            val gain: Float = 1.0f,
            val deadZone: Float = 0f,
            val saturation: Float = 0f,
            val invert: Boolean = false,
            val outputScale: Float = 1.0f,
            /** EyeOpen 专属瞪眼叠加增益，默认 0 = 无叠加 */
            val wideBoostGain: Float = 0f,
        )

        private val DEFAULT_EXPRESSION_CONFIG = ExpressionConfig()

        /** 每通道重映射配置，只列有实测数据的通道，其余走缺省恒等 */
        private val channelConfigs: Map<String, ExpressionConfig> = linkedMapOf(
            // wideBoostGain 0.15：正常瞪眼达 0.65，钳制上界放宽到 1.0
            "EyeOpenLeft" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            "EyeOpenRight" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            // deviceMax 0.22 = Squint 峰值
            "EyeSmileLeft" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            "EyeSmileRight" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            // deviceMax 0.30 = Jaw_Open 峰值，outputScale 0.5 对齐 VTS 满幅 IN 0.51
            "MouthOpen" to ExpressionConfig(deviceMax = 0.30f, outputScale = 0.5f),
            // deadZone 0.15 压静息 Pucker 噪声（raw 0.082）
            "MouthSmile" to ExpressionConfig(deadZone = 0.15f),
            // v1 恒等，归一化分母在常量里
            "MouthX" to ExpressionConfig(),
            "BrowLeftY" to ExpressionConfig(),
            "BrowRightY" to ExpressionConfig(),
            // 左右 combined 均值共用 BROW_DEVICE_MAX
            "Brows" to ExpressionConfig(),
        )

        private const val VTS_MOUTH_SMILE = "MouthSmile"

        /** 正段输入：AR 源键 -> VTS 1:1 名，后者兼作校准中性键 */
        private val MOUTH_SMILE_POSITIVE = listOf(
            "Animoji_Mouth_Smile_Left" to "MouthSmileLeft",
            "Animoji_Mouth_Smile_Right" to "MouthSmileRight"
        )

        /** 负段输入：Pucker 才是嘟嘴，Frown 是嘴角下垂 */
        private val MOUTH_SMILE_NEGATIVE = listOf(
            "Animoji_Mouth_Pucker" to "MouthPucker"
        )

        /** Smile 峰值 0.893/0.780（均值 0.84）、Pucker 0.824，均取 0.85 */
        private const val SMILE_DEVICE_MAX = 0.85f
        private const val PUCKER_DEVICE_MAX = 0.85f

        /** MouthX 域 -1~1，BrowLeftY/BrowRightY/Brows 域 0~1（InputParameterListResponse 报告值） */
        private const val VTS_MOUTH_X = "MouthX"
        private const val VTS_BROW_LEFT_Y = "BrowLeftY"
        private const val VTS_BROW_RIGHT_Y = "BrowRightY"

        private const val VTS_BROWS = "Brows"

        /** 不进 arToVtsMapping：两个源对应同一合成输出名，进 1:1 表会互相覆盖 */
        private const val AR_MOUTH_RIGHT = "Animoji_Mouth_Right"
        private const val AR_MOUTH_LEFT = "Animoji_Mouth_Left"

        /** 无峰值数据取恒等 1.0；眉毛仅 BrowOuterUpLeft 峰值 0.46 */
        private const val MOUTH_X_DEVICE_MAX = 1.0f
        private const val BROW_DEVICE_MAX = 1.0f

        /** 合成双极通道中性基座，眉毛三通道恒用本值不可覆盖 */
        private const val SYNTH_NEUTRAL_BASE = 0.5f
        /** 合成双极通道半幅，基座 ± 半幅 = [0,1] 满量程 */
        private const val SYNTH_HALF_RANGE = 0.5f

        /** deviceMax 覆盖值钳制范围，下界防近零分母把静息噪声放大成满幅 */
        private const val OVERRIDE_DEVICE_MAX_MIN = 0.05f
        private const val OVERRIDE_DEVICE_MAX_MAX = 2f

        /** MouthX 输出边界，VTS 标准域 -1~1（InputParameterListResponse 报告值） */
        private const val MOUTH_X_OUTPUT_MAX = 1.0f

        /** 眉毛上抬输入，VTS 名兼作校准中性键 */
        private val BROW_UP_LEFT = listOf(
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Left" to "BrowOuterUpLeft"
        )
        private val BROW_UP_RIGHT = listOf(
            "Animoji_Brow_Inner_Up" to "BrowInnerUp",
            "Animoji_Brow_Outer_Up_Right" to "BrowOuterUpRight"
        )

        private val BROW_DOWN_LEFT = listOf(
            "Animoji_Brow_Down_Left" to "BrowDownLeft"
        )
        private val BROW_DOWN_RIGHT = listOf(
            "Animoji_Brow_Down_Right" to "BrowDownRight"
        )

        /** EyeOpen -> (同侧 Wide 的 AR 源键, Wide 的 VTS 名作中性键) */
        private val EYE_WIDE_SOURCE = mapOf(
            "EyeOpenLeft" to ("Animoji_Eye_Wide_Left" to "EyeWideLeft"),
            "EyeOpenRight" to ("Animoji_Eye_Wide_Right" to "EyeWideRight")
        )

        /** Wide 峰值 0.973/1.000 取 1.0；死区 0.10 只作用于叠加项 */
        private const val WIDE_BOOST_DEVICE_MAX = 1.0f
        private val WIDE_BOOST_SHAPE = ExpressionConfig(deadZone = 0.10f)

        /** 瞪眼 EyeY 补偿系数，只作用 Y 轴 */
        private const val EYE_WIDE_EYE_Y_COMPENSATION = 0.35f

        /** 镜像时左右身份互换的 9 对通道，眉毛为竖直轴整体豁免 */
        private val MIRROR_SWAP_PAIRS = listOf(
            "EyeOpenLeft" to "EyeOpenRight",
            "EyeSmileLeft" to "EyeSmileRight",
            "EyeWideLeft" to "EyeWideRight",
            "MouthSmileLeft" to "MouthSmileRight",
            "MouthSadLeft" to "MouthSadRight",
            "MouthDimpleLeft" to "MouthDimpleRight",
            "MouthStretchLeft" to "MouthStretchRight",
            // 眼球 Y 只交换，X 交换后再取反
            "EyeLeftY" to "EyeRightY",
            "EyeLeftX" to "EyeRightX"
        )

        /** 镜像取反的水平分量，值域关于 0 对称故不二次钳制 */
        private val MIRROR_NEGATE_KEYS = listOf(
            "MouthX",
            "EyeLeftX",
            "EyeRightX",
            "FaceAngleX",
            "FaceAngleZ",
            "FacePositionX"
        )

        /** 平移米 -> FacePosition：X/Y 乘 100、Z 取负（VTS 靠近为正） */
        private const val POSITION_SCALE_XY = 100f
        private const val POSITION_SCALE_Z = -100f

        /** 角度缩放 1f = 度数直传 */
        private const val ANGLE_SCALE = 1f

        /** VTS 安全量程（InputParameterListResponse 报告值） */
        const val ANGLE_XY_MIN = -30f
        const val ANGLE_XY_MAX = 30f
        const val ANGLE_Z_MIN = -90f
        const val ANGLE_Z_MAX = 90f
        const val POSITION_XY_MIN = -15f
        const val POSITION_XY_MAX = 15f
        const val POSITION_Z_MIN = -10f
        const val POSITION_Z_MAX = 10f
        const val EXPRESSION_MIN = 0f
        const val EXPRESSION_MAX = 1f

        const val SENSITIVITY_MIN = 0.1f
        const val SENSITIVITY_MAX = 3.0f
        const val SENSITIVITY_DEFAULT = 1.0f

        /** 反转通道未校准中性：vtsRaw 域存 1.0，换算到 AR 域中性 = 0 */
        const val EXPRESSION_BASE_MAX = 1f
        const val EXPRESSION_BASE_NEUTRAL = 0f

        /** 同一参数钳制告警最小间隔，防 30Hz 逐帧刷屏 */
        private const val CLAMP_WARN_INTERVAL_MS = 5000L
    }
}
