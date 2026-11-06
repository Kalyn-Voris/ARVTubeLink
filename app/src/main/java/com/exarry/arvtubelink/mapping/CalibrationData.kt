package com.exarry.arvtubelink.mapping

/** 校准零点基准值：处理链为原始值 -> 校准偏移 -> 灵敏度缩放 -> 钳制 */
data class CalibrationData(
    /** FaceAngleX/Y/Z 基准值，单位度，已含 ANGLE_SCALE */
    val baseAngles: Map<String, Float> = emptyMap(),
    /** FacePositionX/Y/Z 基准值，已乘 POSITION_SCALE */
    val basePositions: Map<String, Float> = emptyMap(),
    /** VTS 表情参数基准值 0..1，已做眨眼反转 */
    val baseExpressions: Map<String, Float> = emptyMap()
) {

    /** 三表皆空即未校准 */
    val isCalibrated: Boolean
        get() = baseAngles.isNotEmpty() || basePositions.isNotEmpty() || baseExpressions.isNotEmpty()

    companion object {
        // 键名须与 BlendShapeMapper 生成的参数一致，映射端据此分区回退
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

        /** 中性基准取 0，映射值等于原始值 */
        const val DEFAULT_POSE_BASE = 0f
    }
}
