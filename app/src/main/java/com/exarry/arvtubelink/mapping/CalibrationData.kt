package com.exarry.arvtubelink.mapping

/** 处理链：原始值 -> 校准偏移 -> 灵敏度缩放 -> 钳制 */
data class CalibrationData(
    val baseAngles: Map<String, Float> = emptyMap(),
    val basePositions: Map<String, Float> = emptyMap(),
    val baseExpressions: Map<String, Float> = emptyMap()
) {

    val isCalibrated: Boolean
        get() = baseAngles.isNotEmpty() || basePositions.isNotEmpty() || baseExpressions.isNotEmpty()

    companion object {
        // 键名须与 BlendShapeMapper 输出参数名一致，映射端据此分区
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

        const val DEFAULT_POSE_BASE = 0f
    }
}
