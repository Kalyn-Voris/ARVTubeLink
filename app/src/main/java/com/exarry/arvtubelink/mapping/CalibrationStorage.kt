package com.exarry.arvtubelink.mapping

import android.content.Context
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 灵敏度系数，与校准数据同文件持久化 */
data class SensitivityConfig(
    val angle: Float,
    val position: Float,
    val expression: Float
)

/** 校准数据与灵敏度持久化；写方法 suspend 且 commit 落盘后返回，读方法同步 */
class CalibrationStorage(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** IO 协程，commit 落盘后返回 */
    suspend fun save(data: CalibrationData): Unit = withContext(Dispatchers.IO) {
        val ok = prefs.edit()
            .putStringSet(KEY_BASE_ANGLES, encodeSet(data.baseAngles))
            .putStringSet(KEY_BASE_POSITIONS, encodeSet(data.basePositions))
            .putStringSet(KEY_BASE_EXPRESSIONS, encodeSet(data.baseExpressions))
            .commit()
        if (!ok) InAppLog.w(TAG, "校准数据写入 SharedPreferences 失败")
    }

    /** 同步 IO；未保存或条目损坏时对应类别返回空 Map */
    fun load(): CalibrationData = CalibrationData(
        baseAngles = decodeSet(prefs.getStringSet(KEY_BASE_ANGLES, null)),
        basePositions = decodeSet(prefs.getStringSet(KEY_BASE_POSITIONS, null)),
        baseExpressions = decodeSet(prefs.getStringSet(KEY_BASE_EXPRESSIONS, null))
    )

    /** 只清三类基准键，灵敏度保留 */
    suspend fun reset(): Unit = withContext(Dispatchers.IO) {
        val ok = prefs.edit()
            .remove(KEY_BASE_ANGLES)
            .remove(KEY_BASE_POSITIONS)
            .remove(KEY_BASE_EXPRESSIONS)
            .commit()
        if (!ok) InAppLog.w(TAG, "校准数据重置失败")
    }

    /** IO 协程，commit 落盘后返回 */    suspend fun saveSensitivities(config: SensitivityConfig): Unit =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit()
                .putFloat(KEY_SENS_ANGLE, config.angle)
                .putFloat(KEY_SENS_POSITION, config.position)
                .putFloat(KEY_SENS_EXPRESSION, config.expression)
                .commit()
            if (!ok) InAppLog.w(TAG, "灵敏度写入 SharedPreferences 失败")
        }

    /** 同步 IO，缺省 1.0 */
    fun loadSensitivities(): SensitivityConfig = SensitivityConfig(
        angle = prefs.getFloat(KEY_SENS_ANGLE, BlendShapeMapper.SENSITIVITY_DEFAULT),
        position = prefs.getFloat(KEY_SENS_POSITION, BlendShapeMapper.SENSITIVITY_DEFAULT),
        expression = prefs.getFloat(KEY_SENS_EXPRESSION, BlendShapeMapper.SENSITIVITY_DEFAULT)
    )

    private fun encodeSet(map: Map<String, Float>): Set<String> =
        map.map { (key, value) -> "$key=$value" }.toSet()

    private fun decodeSet(set: Set<String>?): Map<String, Float> {
        if (set.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Float>(set.size)
        for (entry in set) {
            val sep = entry.indexOf('=')
            if (sep <= 0 || sep == entry.length - 1) {
                InAppLog.w(TAG, "忽略损坏的校准条目：$entry")
                continue
            }
            val value = entry.substring(sep + 1).toFloatOrNull()
            if (value == null) {
                InAppLog.w(TAG, "忽略无法解析的校准条目：$entry")
                continue
            }
            out[entry.substring(0, sep)] = value
        }
        return out
    }

    companion object {
        private const val TAG = "CalibrationStorage"
        private const val PREFS_NAME = "calibration_prefs"
        private const val KEY_BASE_ANGLES = "base_angles"
        private const val KEY_BASE_POSITIONS = "base_positions"
        private const val KEY_BASE_EXPRESSIONS = "base_expressions"
        private const val KEY_SENS_ANGLE = "sens_angle"
        private const val KEY_SENS_POSITION = "sens_position"
        private const val KEY_SENS_EXPRESSION = "sens_expression"
    }
}
