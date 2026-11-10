package com.exarry.arvtubelink.adapt

import android.content.Context
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.utils.InAppLog
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredAdaptation(
    val modelId: String,
    val modelName: String,
    val overrides: MapperOverrides
)

class AdaptationStorage(context: Context) {

    private val gson = Gson()
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun save(modelId: String, modelName: String, overrides: MapperOverrides): Unit =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_MODEL_ID, modelId)
                .putString(KEY_MODEL_NAME, modelName)
                .putString(KEY_OVERRIDES_JSON, gson.toJson(overrides))
                .commit()
            if (!ok) InAppLog.w(TAG, "自适应覆盖写入 SharedPreferences 失败（本次会话仍生效，仅不持久化）")
        }

    /** 同步读盘，须在 IO 协程调用 */
    fun load(): StoredAdaptation? {
        val modelId = prefs.getString(KEY_MODEL_ID, null)
        val json = prefs.getString(KEY_OVERRIDES_JSON, null)
        if (modelId.isNullOrEmpty() || json.isNullOrEmpty()) return null
        val version = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (version != SCHEMA_VERSION) {
            InAppLog.w(
                TAG,
                "自适应存储 schema 版本不匹配（$version != $SCHEMA_VERSION），弃用旧条目回默认映射" +
                    if (version < SCHEMA_VERSION) "——旧版含眉毛覆盖（browNeutralBase/browHalfRange），" +
                        "正是眉毛静息低位故障来源，必须重探" else ""
            )
            prefs.edit().clear().apply()
            return null
        }
        val overrides = runCatching { gson.fromJson(json, MapperOverrides::class.java) }.getOrNull()
        if (overrides == null) {
            InAppLog.w(TAG, "自适应覆盖 JSON 解析失败，删除损坏数据回默认映射")
            prefs.edit().clear().apply()
            return null
        }
        return StoredAdaptation(
            modelId = modelId,
            modelName = prefs.getString(KEY_MODEL_NAME, null) ?: modelId,
            overrides = overrides
        )
    }

    companion object {
        private const val TAG = "AdaptationStorage"
        private const val PREFS_NAME = "adaptation_prefs"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_OVERRIDES_JSON = "overrides_json"

        /** 字段语义变更时 bump */
        const val SCHEMA_VERSION = 2
    }
}
