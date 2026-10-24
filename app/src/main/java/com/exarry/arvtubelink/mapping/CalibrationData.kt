package com.exarry.arvtubelink.mapping

/**
 * 校准数据（零点基准值）：记录用户点「校准」瞬间的当前帧原始数据。
 *
 * 处理链：原始值 -> 校准偏移 -> 灵敏度缩放 -> 钳制，见 [BlendShapeMapper]。
 *
 * 三张基准表默认空 Map，映射端对缺键按类别回退中性基准，所以默认构造等价于恒等映射
 * （灵敏度为 1 时输出等于原始值）：头姿角度与位置回退 [DEFAULT_POSE_BASE]；表情回退 0，
 * 但 EyeOpen* 是反转项、校准点锚定最大值，回退 1。
 *
 * 基准值取自 VTS 输出域：已做眨眼反转（Animoji_Eye_Blink -> 1-Blink），已做头姿角度与
 * 位置换算，未做校准偏移与灵敏度缩放。分区判定见 [ANGLE_KEYS] 与 [POSITION_KEYS]。
 *
 * 不可变：更新基准值时整体替换实例（[BlendShapeMapper.updateCalibration]），
 * GL 线程逐帧只读、UI 线程只写引用，无需加锁。
 */
data class CalibrationData(
    /** FaceAngleX/Y/Z 基准值，单位度，已含 ANGLE_SCALE。 */
    val baseAngles: Map<String, Float> = emptyMap(),
    /** FacePositionX/Y/Z 基准值，已乘 POSITION_SCALE（AR 平移米数）。 */
    val basePositions: Map<String, Float> = emptyMap(),
    /** VTS 表情参数基准值，0..1，已做眨眼反转。键如 EyeOpenLeft、MouthOpen。 */
    val baseExpressions: Map<String, Float> = emptyMap()
) {

    /** 三表皆空即未校准。 */
    val isCalibrated: Boolean
        get() = baseAngles.isNotEmpty() || basePositions.isNotEmpty() || baseExpressions.isNotEmpty()

    companion object {
        // 必须与 BlendShapeMapper 生成的参数同名，映射端据此分区回退
        const val KEY_FACE_ANGLE_X = "FaceAngleX"
        const val KEY_FACE_ANGLE_Y = "FaceAngleY"
        const val KEY_FACE_ANGLE_Z = "FaceAngleZ"
        const val KEY_FACE_POSITION_X = "FacePositionX"
        const val KEY_FACE_POSITION_Y = "FacePositionY"
        const val KEY_FACE_POSITION_Z = "FacePositionZ"

        val ANGLE_KEYS: Set<String> =
            setOf(KEY_FACE_ANGLE_X, KEY_FACE_ANGLE_Y, KEY_FACE_ANGLE_Z)

        val POSITION_KEYS: Set<String> =
            setOf(KEY_FACE_POSITION_X, KEY_FACE_POSITION_Y, KEY_FACE_POSITION_Z)

        /** 头姿中性基准取 0：映射值 = (Raw - 0) * 1.0 = Raw，恒等。 */
        const val DEFAULT_POSE_BASE = 0f
    }
}
