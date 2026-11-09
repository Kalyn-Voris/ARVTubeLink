package com.exarry.arvtubelink.ui.live2d

import kotlin.math.pow

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
 * | MouthOpen | 0~0.5（outputScale=0.5） | ParamMouthOpenY | 0~1 | ×2，再按曲线重映射 |
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
 * ⚠️ 张嘴灵敏度的真实问题不在「量纲差 2 倍」，而在**线性映射与模型曲线不匹配**：
 * morph 的 MouthOpen 是「下颌张开角度归一化后的线性量」，而 Cubism 模型的
 * ParamMouthOpenY 驱动的是嘴部网格的**开合面积**。两者关系接近平方：
 * 半张嘴时面积只有满张的 ~25%。线性喂进去的结果就是「小幅度完全看不出来、
 * 稍大幅度直接满张」——这正好对应真机反馈的「要么不动，要么直接满」。
 * 处理办法是把线性值先做一次幂压缩（见 [MOUTH_OPEN_CURVE_EXPONENT]），
 * 再乘放大系数；两条一起调才有「线性可用」的手感。
 *
 * ## 未映射的 VTS 参数
 * MouthFunnel / MouthPucker / CheekPuff / MouthSad* / MouthDimple* / MouthStretch* /
 * BrowInnerUp / BrowOuterUp* / BrowDown* / Brows / MouthX / FacePosition* 在
 * 模型里常没有对应参数（或只有间接参数），本阶段不追求表情精度，
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
     * 张嘴曲线的压缩指数（< 1 = 小幅度抬起、大幅度压回）。
     *
     * 取 0.75 的依据：morph 的 Jaw_Open 是下颌角度线性量（deviceMax 0.30），
     * 而 ParamMouthOpenY 作用于嘴部网格开合面积，两者关系近似平方——
     * 线性喂进去会出现「小声说话看不出、稍一张就满」，也就是真机反馈的
     * 「要么不动，要么直接满」。开根号式压缩把低端抬起来、高端压回去，
     * 让整段下颌行程都均匀可见。取 0.75 而不是 0.5：纯平方会在正常音量
     * 就张得过大，0.75 更接近标准渲染器的观感。
     */
    private const val MOUTH_OPEN_CURVE_EXPONENT = 0.75f

    /**
     * 张嘴总放大系数。
     *
     * 与 [MOUTH_OPEN_CURVE_EXPONENT] 分工：指数管「形状」，系数管「幅度」。
     * 取 1.0 是因为指数压缩已经把低端抬够了：若再放大，正常说话（morph
     * MouthOpen ≈ 0.15~0.25）会落在 0.7 以上，变成「一开口就满」。
     * 实测区间（指数 0.75 / 增益 1.0）：
     *   0.05 → 0.18、0.10 → 0.30、0.20 → 0.50、0.30 → 0.68、0.50 → 1.00
     * 即轻音节就能看出开合，deviceMax 峰值仍留有余量。
     */
    private const val MOUTH_OPEN_GAIN = 1.0f

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

        // 张嘴：线性归一线 → 幂曲线重映射 → 放大 → 钳到 0~1。
        // 顺序不能反：先放大再压缩会把已经钳在 1 的值压回 0.9，满张永远到不了。
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
