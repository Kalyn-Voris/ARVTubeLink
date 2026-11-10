package com.exarry.arvtubelink.ui.live2d

import kotlin.math.pow

/**
 * VTS 参数名 → Cubism 参数量纲换算：EyeOpen ×2、MouthForm/Brow (v-0.5)×2、Angle 度直传
 */
internal object Live2DParamMap {

    /** 顺序即推流顺序，模型缺失的名字由 bindParameters 标 -1 跳过 */
    val TARGETS: List<String> = listOf(
        "ParamAngleX",
        "ParamAngleY",
        "ParamAngleZ",
        "ParamEyeLOpen",
        "ParamEyeROpen",
        "ParamEyeLSmile",
        "ParamEyeRSmile",
        "ParamEyeBallX",
        "ParamEyeBallY",
        "ParamMouthOpenY",
        "ParamMouthForm",
        "ParamBrowLY",
        "ParamBrowRY",
        "ParamBodyAngleX",
        "ParamBodyAngleZ",
    )

    /** 头部角度到身体侧倾的跟随系数 */
    private const val BODY_FOLLOW_X = 0.30f
    private const val BODY_FOLLOW_Z = 0.20f

    /** 张嘴曲线压缩指数，平方关系补偿 */
    private const val MOUTH_OPEN_CURVE_EXPONENT = 0.75f

    /** 张嘴放大系数：指数 0.75 / 增益 1.0 时 0.05→0.18、0.5→1.00 */
    private const val MOUTH_OPEN_GAIN = 1.0f

    fun valueFor(target: String, params: Map<String, Float>): Float? = when (target) {
        "ParamAngleX" -> params["FaceAngleX"]?.coerceIn(-30f, 30f)
        "ParamAngleY" -> params["FaceAngleY"]?.coerceIn(-30f, 30f)
        "ParamAngleZ" -> params["FaceAngleZ"]?.coerceIn(-30f, 30f)

        // 0~0.5 → 0~2：1.0 = 常态睁眼、2.0 = 瞪眼
        "ParamEyeLOpen" -> params["EyeOpenLeft"]?.let { (it * 2f).coerceIn(0f, 2f) }
        "ParamEyeROpen" -> params["EyeOpenRight"]?.let { (it * 2f).coerceIn(0f, 2f) }
        "ParamEyeLSmile" -> params["EyeSmileLeft"]?.coerceIn(0f, 1f)
        "ParamEyeRSmile" -> params["EyeSmileRight"]?.coerceIn(0f, 1f)

        // 眼球方向双眼共用，取左右均值
        "ParamEyeBallX" -> averageOf(params, "EyeLeftX", "EyeRightX")
            ?.let { it.coerceIn(-1f, 1f) }
        "ParamEyeBallY" -> averageOf(params, "EyeLeftY", "EyeRightY")
            ?.let { it.coerceIn(-1f, 1f) }

        // 归一 → 幂曲线 → 放大 → 钳 0~1，顺序不可反
        "ParamMouthOpenY" -> params["MouthOpen"]?.let { raw ->
            val linear = (raw * 2f).coerceIn(0f, 1f)
            val curved = linear.pow(MOUTH_OPEN_CURVE_EXPONENT)
            (curved * MOUTH_OPEN_GAIN).coerceIn(0f, 1f)
        }
        // 0~1 中性 0.5 → -1~1 中性 0
        "ParamMouthForm" -> params["MouthSmile"]?.let { ((it - 0.5f) * 2f).coerceIn(-1f, 1f) }

        "ParamBrowLY" -> params["BrowLeftY"]?.let { ((it - 0.5f) * 2f).coerceIn(-1f, 1f) }
        "ParamBrowRY" -> params["BrowRightY"]?.let { ((it - 0.5f) * 2f).coerceIn(-1f, 1f) }

        "ParamBodyAngleX" -> params["FaceAngleX"]
            ?.let { (it * BODY_FOLLOW_X).coerceIn(-10f, 10f) }
        "ParamBodyAngleZ" -> params["FaceAngleZ"]
            ?.let { (it * BODY_FOLLOW_Z).coerceIn(-10f, 10f) }

        else -> null
    }

    /** 人脸丢失时的中性值：睁眼 1.0，其余 0，不用模型 ParamDefault */
    fun neutralOf(target: String): Float = when (target) {
        "ParamEyeLOpen", "ParamEyeROpen" -> 1f
        else -> 0f
    }

    private fun averageOf(params: Map<String, Float>, a: String, b: String): Float? {
        val va = params[a]
        val vb = params[b]
        return when {
            va != null && vb != null -> (va + vb) * 0.5f
            else -> va ?: vb
        }
    }
}
