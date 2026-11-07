package com.exarry.arvtubelink.ui.live2d

/**
 * VTS 参数名 → Cubism（Purism Core）参数名的映射与量纲换算。
 *
 * ## 为什么需要这一层
 * [com.exarry.arvtubelink.mapping.BlendShapeMapper] 的输出是 **VTube Studio 域**
 * 的参数（0~1 的开合、0.5 中性的表情、以「度」为单位的角度）。Cubism 模型的
 * 参数是另一套量纲（面部角度 ±30、嘴角形态 -1~1、口型开合 0~1）。
 * 本文件只做**读值 + 换算**，不参与任何算法：
 * 红线要求不修改 BlendShapeMapper，因此它的输出被视为既定事实。
 *
 * ## 已确认的量纲差异（对照 moc3 内参数量程实测）
 * | VTS 输出 | 域 | Cubism 目标 | 模型量程 | 换算 |
 * | --- | --- | --- | --- | --- |
 * | EyesOpen | 0~0.5，瞪眼叠加至 1.0 | ParamEyeLOpen/ROpen | 0~2（1=常态睁眼） | ×2 |
 * | MouthOpen | 0~0.5（outputScale=0.5） | ParamMouthOpenY | 0~1 | ×2 |
 * | MouthSmile | 0~1（0.5 中性） | ParamMouthForm | -1~1 | (v-0.5)×2 |
 * | BrowLeftY/BrowRightY | 0~1（0.5 中性） | ParamBrowLY/RY | -1~1 | (v-0.5)×2 |
 * | EyeLeftX/Y、EyeRightX/Y | -1~1 | ParamEyeBallX/Y | -1~1 | 左右取均值 |
 * | FaceAngleX/Y/Z | 度 | ParamAngleX/Y/Z | ±30 三轴 | 直传 |
 *
 * ⚠️ `ParamEyeLOpen` 上限是 **2.0** 而不是 1.0：Cubism 里 1.0 = 常态睁眼、
 * 2.0 = 瞪眼。mapper 的 EyeOpen 中性正好是 0.5（outputScale），乘 2 落在 1.0，
 * 与模型语义一致；wideBoost 叠加到 1.0 时乘 2 = 2.0，也正好顶到模型上限。
 * 若按 1.0 钳制，瞪眼会被截掉一半。
 *
 * ## 未映射的 VTS 参数
 * MouthFunnel / MouthPucker / CheekPuff / MouthSad* / MouthDimple* / MouthStretch* /
 * BrowInnerUp / BrowOuterUp* / BrowDown* / Brows / MouthX / FacePosition* 在本次
 * 内置的 Sample 模型里没有对应参数（或只有间接参数），本阶段不追求表情精度，
 * 只保证「头动、眼动、嘴动」三条主线成立。后续要做精细表情时在此扩表即可，
 * 无需改动 C++ 或渲染管线。
 */
internal object Live2DParamMap {

    /**
     * 需要绑定的 Cubism 参数名。
     *
     * 顺序即推流顺序；模型里不存在的名字由 [Live2DRenderer.bindParameters] 标成
     * -1 并自动跳过，因此这张表可以安全地比模型「大」。
     */
    val TARGETS: List<String> = listOf(
        // 头部姿态
        "ParamAngleX",
        "ParamAngleY",
        "ParamAngleZ",
        // 眼睛
        "ParamEyeLOpen",
        "ParamEyeROpen",
        "ParamEyeLSmile",
        "ParamEyeRSmile",
        "ParamEyeBallX",
        "ParamEyeBallY",
        // 嘴
        "ParamMouthOpenY",
        "ParamMouthForm",
        // 眉毛
        "ParamBrowLY",
        "ParamBrowRY",
        // 身体（跟随头部做轻微侧倾，纯函数、非动画）
        "ParamBodyAngleX",
        "ParamBodyAngleZ",
    )

    /** 头部角度到身体侧倾的跟随系数：只取一部分，避免整个人像在甩。 */
    private const val BODY_FOLLOW_X = 0.30f
    private const val BODY_FOLLOW_Z = 0.20f

    /**
     * 计算某个 Cubism 参数在本帧的取值。
     *
     * @param target  Cubism 参数名（[TARGETS] 之一）
     * @param params  VTS 域快照（BlendShapeMapper + Smoother 的输出）
     * @return 已按 Cubism 量纲换算并钳制的值；源参数缺失时返回 null
     */
    fun valueFor(target: String, params: Map<String, Float>): Float? = when (target) {
        "ParamAngleX" -> params["FaceAngleX"]?.coerceIn(-30f, 30f)
        "ParamAngleY" -> params["FaceAngleY"]?.coerceIn(-30f, 30f)
        "ParamAngleZ" -> params["FaceAngleZ"]?.coerceIn(-30f, 30f)

        // 0~0.5 → 0~2：模型里 EyeOpen 的 1.0 = 常态睁眼、2.0 = 瞪眼。
        // mapper 的睁眼中性 0.5 × 2 = 1.0 正好对上；瞪眼叠加到 1.0 × 2 = 2.0 顶满。
        // 若按 1.0 钳制，瞪眼会被砍掉一半（mapper 的 wideBoost 本来就为此留了余量）。
        "ParamEyeLOpen" -> params["EyeOpenLeft"]?.let { (it * 2f).coerceIn(0f, 2f) }
        "ParamEyeROpen" -> params["EyeOpenRight"]?.let { (it * 2f).coerceIn(0f, 2f) }
        "ParamEyeLSmile" -> params["EyeSmileLeft"]?.coerceIn(0f, 1f)
        "ParamEyeRSmile" -> params["EyeSmileRight"]?.coerceIn(0f, 1f)

        // 眼球方向：左右各一组，Cubism 是双眼共用，取均值
        "ParamEyeBallX" -> averageOf(params, "EyeLeftX", "EyeRightX")
            ?.let { it.coerceIn(-1f, 1f) }
        "ParamEyeBallY" -> averageOf(params, "EyeLeftY", "EyeRightY")
            ?.let { it.coerceIn(-1f, 1f) }

        // 0~0.5（outputScale=0.5）→ 0~1
        "ParamMouthOpenY" -> params["MouthOpen"]?.let { (it * 2f).coerceIn(0f, 1f) }
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

    /**
     * 人脸丢失时的中性值（Cubism 域默认姿态）。
     *
     * 刻意不向 C++ 要「模型默认值」：中性姿态是我们认定的语义（睁眼、闭嘴、
     * 正视前方），而 ParamDefault 未必等于画面上的中性表情。
     *
     * 注意 ParamMouthForm 的模型默认值是 1.0（嘴角上扬的默认表情），但中性应为 0；
     * 这里按语义给 0，避免人脸丢失瞬间嘴角突然拉出一个笑。
     */
    fun neutralOf(target: String): Float = when (target) {
        // 1.0 = 常态睁眼（模型上限 2.0 才是瞪眼）
        "ParamEyeLOpen", "ParamEyeROpen" -> 1f
        // 角度/形态/眼球/身体：中性均为 0
        else -> 0f
    }

    /** 双源取均值；两侧都缺返回 null，只有一侧命中就用那一侧。 */
    private fun averageOf(params: Map<String, Float>, a: String, b: String): Float? {
        val va = params[a]
        val vb = params[b]
        return when {
            va != null && vb != null -> (va + vb) * 0.5f
            else -> va ?: vb
        }
    }
}
