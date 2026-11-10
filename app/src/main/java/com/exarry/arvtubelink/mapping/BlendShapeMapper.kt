package com.exarry.arvtubelink.mapping

import android.os.SystemClock
import com.exarry.arvtubelink.utils.InAppLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2

data class MapperOverrides(
    val mouthSmileNeutralBase: Float? = null,
    val mouthOpenDeviceMax: Float? = null,
    val mouthOpenOutputScale: Float? = null,
    val eyeOpenOutputScale: Float? = null,
    val browDeviceMax: Float? = null,
    val mouthXInvert: Boolean? = null
)

/** AR Engine 表情与头姿 -> VTS 参数名，输出钳制到 VTS 安全量程 */
class BlendShapeMapper(
    calibration: CalibrationData = CalibrationData(),
    @Volatile var angleSensitivity: Float = SENSITIVITY_DEFAULT,
    @Volatile var positionSensitivity: Float = SENSITIVITY_DEFAULT,
    @Volatile var expressionSensitivity: Float = SENSITIVITY_DEFAULT
) {

    /** @Volatile 整体替换，GL 每帧读一次引用 */
    @Volatile
    var calibrationData: CalibrationData = calibration
        private set

    fun updateCalibration(data: CalibrationData) {
        calibrationData = data
    }

    /** null = 走硬编码默认，整体替换无锁 */
    @Volatile
    var overrides: MapperOverrides? = null
        private set

    fun updateOverrides(value: MapperOverrides?) {
        overrides = value
    }

    @Volatile
    var faceMirrorEnabled: Boolean = false

    /** 最新一帧表情原始值，vtsRaw 反转域，GL 写 UI 读 */
    @Volatile
    private var latestExpressionRaw: Map<String, Float> = emptyMap()

    @Volatile
    private var latestHeadRaw: Map<String, Float> = emptyMap()

    fun latestRawSnapshot(): Map<String, Float>? {
        val snapshot: Map<String, Float> = latestExpressionRaw + latestHeadRaw
        return if (snapshot.isEmpty()) null else snapshot
    }

    fun clearRawSnapshot() {
        latestExpressionRaw = emptyMap()
        latestHeadRaw = emptyMap()
    }

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

    fun map(arCoefficients: Map<String, Float>): VtsParams {
        val faceFound = arCoefficients.isNotEmpty()
        val cal = calibrationData // 帧内基准一致，volatile 读一次
        val sens = expressionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val expressionRaw = LinkedHashMap<String, Float>(arToVtsMapping.size)
        val result = LinkedHashMap<String, Float>(arToVtsMapping.size + 2)
        for ((arKey, vtsKey) in arToVtsMapping) {
            val cfg = cfgFor(vtsKey)
            val raw = arCoefficients[arKey]
            // 反转通道中性存 vtsRaw 域，换算回 1-base
            val neutral = if (cfg.invert) {
                EXPRESSION_MAX - (cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_MAX)
            } else {
                cal.baseExpressions[vtsKey] ?: EXPRESSION_BASE_NEUTRAL
            }
            // 先整形后反转，顺序不可反
            val intensity = raw?.let { shapeExpression((it - neutral) / cfg.deviceMax, cfg) }
            if (raw != null) {
                expressionRaw[vtsKey] = if (cfg.invert) EXPRESSION_MAX - raw else raw
            }
            val wideBoost = eyeWideBoostTerm(vtsKey, cfg, arCoefficients, cal, sens)
            // 有瞪眼叠加的通道上界放宽到 1.0（VTS EyeOpen 域 0~1）
            val clampMax = if (cfg.wideBoostGain > 0f) EXPRESSION_MAX else cfg.outputScale
            result[vtsKey] = when (intensity) {
                null -> if (cfg.invert) {
                    clampWithLog(cfg.outputScale + wideBoost, EXPRESSION_MIN, clampMax, vtsKey)
                } else {
                    EXPRESSION_MIN
                }
                else -> {
                    val mapped = (if (cfg.invert) {
                        // 反转通道先算开度再乘 outputScale，顺序不可反
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
        // 镜像在快照赋值之后，快照恒为未镜像域
        applyFaceMirror(result)
        return VtsParams(faceFound = faceFound, parameters = result)
    }

    private fun applyFaceMirror(out: LinkedHashMap<String, Float>) {
        if (!faceMirrorEnabled) return
        for ((a, b) in MIRROR_SWAP_PAIRS) {
            val va = out[a]
            val vb = out[b]
            if (va == null || vb == null) continue
            out[a] = vb
            out[b] = va
        }
        // 取反在交换之后，顺序不可反
        for (key in MIRROR_NEGATE_KEYS) {
            val v = out[key] ?: continue
            out[key] = -v
        }
    }

    /** 死区 + 饱和分段线性，重拉伸保满幅 */
    private fun shapeExpression(norm: Float, cfg: ExpressionConfig): Float {
        val satEnd = if (cfg.saturation > 0f) cfg.saturation else EXPRESSION_MAX
        if (norm <= cfg.deadZone) return EXPRESSION_MIN
        if (norm >= satEnd) return EXPRESSION_MAX
        return (norm - cfg.deadZone) / (satEnd - cfg.deadZone)
    }

    /** 合成通道 0.5 中性双极；MouthX 直出 -1~1 */
    private fun putSynthesizedParams(
        arCoefficients: Map<String, Float>,
        cal: CalibrationData,
        sens: Float,
        out: LinkedHashMap<String, Float>
    ) {
        val cfg = cfgFor(VTS_MOUTH_SMILE)

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
        val shaped = if (combined >= 0f) shapeExpression(combined, cfg)
        else -shapeExpression(-combined, cfg)
        // 基座可被覆盖，半幅固定
        val smileNeutralBase = (overrides?.mouthSmileNeutralBase ?: SYNTH_NEUTRAL_BASE)
            .coerceIn(EXPRESSION_MIN, EXPRESSION_MAX)
        out[VTS_MOUTH_SMILE] = clampWithLog(
            smileNeutralBase + shaped * cfg.gain * sens * SYNTH_HALF_RANGE,
            EXPRESSION_MIN, EXPRESSION_MAX, VTS_MOUTH_SMILE
        )

        // deviceMax 1.0 为占位，无 Mouth_Left/Right 峰值数据
        val mouthXCfg = channelConfigs[VTS_MOUTH_X] ?: DEFAULT_EXPRESSION_CONFIG
        val mouthXSign = if (overrides?.mouthXInvert == true) -1f else 1f
        val mouthXCombined = mouthXSign * ((arCoefficients[AR_MOUTH_LEFT] ?: 0f) -
                (arCoefficients[AR_MOUTH_RIGHT] ?: 0f)) / MOUTH_X_DEVICE_MAX
        val shapedMouthX = if (mouthXCombined >= 0f) shapeExpression(mouthXCombined, mouthXCfg)
        else -shapeExpression(-mouthXCombined, mouthXCfg)
        out[VTS_MOUTH_X] = clampWithLog(
            shapedMouthX * mouthXCfg.gain * sens,
            -MOUTH_X_OUTPUT_MAX, MOUTH_X_OUTPUT_MAX, VTS_MOUTH_X
        )

        // deviceMax 1.0 为占位，仅 BrowOuterUpLeft 峰值 0.46
        val browDeviceMax = (overrides?.browDeviceMax ?: BROW_DEVICE_MAX)
            .coerceIn(OVERRIDE_DEVICE_MAX_MIN, OVERRIDE_DEVICE_MAX_MAX)
        val browLeftCombined = halfNorm(BROW_UP_LEFT, browDeviceMax) -
                halfNorm(BROW_DOWN_LEFT, browDeviceMax)
        val browRightCombined = halfNorm(BROW_UP_RIGHT, browDeviceMax) -
                halfNorm(BROW_DOWN_RIGHT, browDeviceMax)
        val browLeftCfg = channelConfigs[VTS_BROW_LEFT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browRightCfg = channelConfigs[VTS_BROW_RIGHT_Y] ?: DEFAULT_EXPRESSION_CONFIG
        val browsCfg = channelConfigs[VTS_BROWS] ?: DEFAULT_EXPRESSION_CONFIG
        val shapedBrowLeft = if (browLeftCombined >= 0f) shapeExpression(browLeftCombined, browLeftCfg)
        else -shapeExpression(-browLeftCombined, browLeftCfg)
        val shapedBrowRight = if (browRightCombined >= 0f) shapeExpression(browRightCombined, browRightCfg)
        else -shapeExpression(-browRightCombined, browRightCfg)
        val browsCombined = (browLeftCombined + browRightCombined) / 2f
        val shapedBrows = if (browsCombined >= 0f) shapeExpression(browsCombined, browsCfg)
        else -shapeExpression(-browsCombined, browsCfg)
        // 眉毛三通道基座/半幅固定不可覆盖
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

    private fun putEyeDirection(
        ar: Map<String, Float>,
        cal: CalibrationData,
        out: LinkedHashMap<String, Float>
    ) {
        fun v(key: String) = ar[key] ?: 0f
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

    fun mapHeadPose(translation: FloatArray, quaternion: FloatArray): Map<String, Float> {
        val eulerRad = quaternionToEuler(quaternion[0], quaternion[1], quaternion[2], quaternion[3])
        val rotXDeg = Math.toDegrees(eulerRad[0].toDouble()).toFloat()
        val rotYDeg = Math.toDegrees(eulerRad[1].toDouble()).toFloat()
        val rotZDeg = Math.toDegrees(eulerRad[2].toDouble()).toFloat()

        // VTS 角度为照镜语义，前置摄像头视角已镜像
        val angleRaw = linkedMapOf(
            "FaceAngleX" to rotYDeg * ANGLE_SCALE,
            "FaceAngleY" to -rotXDeg * ANGLE_SCALE,
            "FaceAngleZ" to -rotZDeg * ANGLE_SCALE
        )
        val positionRaw = linkedMapOf(
            // 平移为会话启动位姿起的绝对量，基线归零靠校准
            "FacePositionX" to translation[0] * POSITION_SCALE_XY,
            "FacePositionY" to translation[1] * POSITION_SCALE_XY,
            "FacePositionZ" to translation[2] * POSITION_SCALE_Z
        )
        latestHeadRaw = angleRaw + positionRaw

        val cal = calibrationData
        val angleSens = angleSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        val positionSens = positionSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

        val result = LinkedHashMap<String, Float>(6)
        for ((key, raw) in angleRaw) {
            // 量程 X/Y -30~30、Z -90~90（VTS 报告值）
            val base = cal.baseAngles[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FaceAngleZ") ANGLE_Z_MIN to ANGLE_Z_MAX
            else ANGLE_XY_MIN to ANGLE_XY_MAX
            result[key] = clampWithLog((raw - base) * angleSens, min, max, key)
        }
        for ((key, raw) in positionRaw) {
            // 量程 X/Y -15~15、Z -10~10（VTS 报告值）
            val base = cal.basePositions[key] ?: CalibrationData.DEFAULT_POSE_BASE
            val (min, max) = if (key == "FacePositionZ") POSITION_Z_MIN to POSITION_Z_MAX
            else POSITION_XY_MIN to POSITION_XY_MAX
            result[key] = clampWithLog((raw - base) * positionSens, min, max, key)
        }
        applyFaceMirror(result)
        return result
    }

    /** 每参数上次告警时刻，仅 GL 线程写 */
    private val lastClampWarnAt = ConcurrentHashMap<String, Long>()

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

    /** 四元数 -> [roll, pitch, yaw] 弧度，ZYX 分解 */
    fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y))
        val pitch = asin((2f * (w * y - z * x)).coerceIn(-1f, 1f))
        val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
        return floatArrayOf(roll, pitch, yaw)
    }

    companion object {

        private const val TAG = "BlendShapeMapper"

        val arToVtsMapping: Map<String, String> = linkedMapOf(
            // Blink 1 = 闭眼，反转由 invert 承担
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

        /** deviceMax 为中性到满幅摆幅，非绝对峰值 */
        private data class ExpressionConfig(
            val deviceMax: Float = 1.0f,
            val gain: Float = 1.0f,
            val deadZone: Float = 0f,
            val saturation: Float = 0f,
            val invert: Boolean = false,
            val outputScale: Float = 1.0f,
            val wideBoostGain: Float = 0f,
        )

        private val DEFAULT_EXPRESSION_CONFIG = ExpressionConfig()

        private val channelConfigs: Map<String, ExpressionConfig> = linkedMapOf(
            // 正常瞪眼达 0.65，钳制上界放宽到 1.0
            "EyeOpenLeft" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            "EyeOpenRight" to ExpressionConfig(deviceMax = 0.5f, deadZone = 0.10f, invert = true, outputScale = 0.5f, wideBoostGain = 0.15f),
            // deviceMax 0.22 = Squint 峰值
            "EyeSmileLeft" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            "EyeSmileRight" to ExpressionConfig(deviceMax = 0.22f, deadZone = 0.10f),
            // deviceMax 0.30 = Jaw_Open 峰值，outputScale 0.5 对齐 VTS 满幅 0.51
            "MouthOpen" to ExpressionConfig(deviceMax = 0.30f, outputScale = 0.5f),
            // deadZone 0.15 压静息 Pucker 噪声 0.082
            "MouthSmile" to ExpressionConfig(deadZone = 0.15f),
            "MouthX" to ExpressionConfig(),
            "BrowLeftY" to ExpressionConfig(),
            "BrowRightY" to ExpressionConfig(),
            "Brows" to ExpressionConfig(),
        )

        private const val VTS_MOUTH_SMILE = "MouthSmile"

        /** VTS 名兼作校准中性键 */
        private val MOUTH_SMILE_POSITIVE = listOf(
            "Animoji_Mouth_Smile_Left" to "MouthSmileLeft",
            "Animoji_Mouth_Smile_Right" to "MouthSmileRight"
        )

        /** 负段取 Pucker（嘟嘴），Frown 是嘴角下垂 */
        private val MOUTH_SMILE_NEGATIVE = listOf(
            "Animoji_Mouth_Pucker" to "MouthPucker"
        )

        /** Smile 峰值 0.893/0.780（均值 0.84）、Pucker 0.824，均取 0.85 */
        private const val SMILE_DEVICE_MAX = 0.85f
        private const val PUCKER_DEVICE_MAX = 0.85f

        /** MouthX 域 -1~1，Brow* 域 0~1（VTS 报告值） */
        private const val VTS_MOUTH_X = "MouthX"
        private const val VTS_BROW_LEFT_Y = "BrowLeftY"
        private const val VTS_BROW_RIGHT_Y = "BrowRightY"

        private const val VTS_BROWS = "Brows"

        /** 两个源合成同一输出名，进 1:1 表会互相覆盖 */
        private const val AR_MOUTH_RIGHT = "Animoji_Mouth_Right"
        private const val AR_MOUTH_LEFT = "Animoji_Mouth_Left"

        /** 无峰值数据取 1.0；眉毛仅 BrowOuterUpLeft 峰值 0.46 */
        private const val MOUTH_X_DEVICE_MAX = 1.0f
        private const val BROW_DEVICE_MAX = 1.0f

        /** 合成双极通道中性基座，眉毛不可覆盖 */
        private const val SYNTH_NEUTRAL_BASE = 0.5f
        private const val SYNTH_HALF_RANGE = 0.5f

        private const val OVERRIDE_DEVICE_MAX_MIN = 0.05f
        private const val OVERRIDE_DEVICE_MAX_MAX = 2f

        private const val MOUTH_X_OUTPUT_MAX = 1.0f

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

        private val EYE_WIDE_SOURCE = mapOf(
            "EyeOpenLeft" to ("Animoji_Eye_Wide_Left" to "EyeWideLeft"),
            "EyeOpenRight" to ("Animoji_Eye_Wide_Right" to "EyeWideRight")
        )

        /** Wide 峰值 0.973/1.000 取 1.0 */
        private const val WIDE_BOOST_DEVICE_MAX = 1.0f
        private val WIDE_BOOST_SHAPE = ExpressionConfig(deadZone = 0.10f)

        private const val EYE_WIDE_EYE_Y_COMPENSATION = 0.35f

        /** 眉毛竖直轴不参与镜像互换 */
        private val MIRROR_SWAP_PAIRS = listOf(
            "EyeOpenLeft" to "EyeOpenRight",
            "EyeSmileLeft" to "EyeSmileRight",
            "EyeWideLeft" to "EyeWideRight",
            "MouthSmileLeft" to "MouthSmileRight",
            "MouthSadLeft" to "MouthSadRight",
            "MouthDimpleLeft" to "MouthDimpleRight",
            "MouthStretchLeft" to "MouthStretchRight",
            "EyeLeftY" to "EyeRightY",
            "EyeLeftX" to "EyeRightX"
        )

        private val MIRROR_NEGATE_KEYS = listOf(
            "MouthX",
            "EyeLeftX",
            "EyeRightX",
            "FaceAngleX",
            "FaceAngleZ",
            "FacePositionX"
        )

        /** 平移米 -> FacePosition，X/Y ×100、Z 取负（VTS 靠近为正） */
        private const val POSITION_SCALE_XY = 100f
        private const val POSITION_SCALE_Z = -100f

        private const val ANGLE_SCALE = 1f

        /** VTS 安全量程（VTS 报告值） */
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

        /** 反转通道中性存 vtsRaw 域 1.0，AR 域即 0 */
        const val EXPRESSION_BASE_MAX = 1f
        const val EXPRESSION_BASE_NEUTRAL = 0f

        private const val CLAMP_WARN_INTERVAL_MS = 5000L
    }
}
