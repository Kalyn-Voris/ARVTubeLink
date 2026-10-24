package com.exarry.arvtubelink.adapt

import android.content.Context
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.utils.InAppLog
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已存自适应条目（load 命中时返回）。 */
data class StoredAdaptation(
    val modelId: String,
    val modelName: String,
    val overrides: MapperOverrides
)

/**
 * 参数自适应覆盖层的持久化存储（adaptation_prefs，独立于校准的 calibration_prefs
 * 与应用级 app_settings，见 AdaptationController KDoc 的三层存储分工）。
 *
 * 四键：schema_version（格式/公式语义变更时 bump，不匹配即弃用回默认）、
 * model_id（匹配键，VTS 模型稳定 GUID）、model_name（展示）、overrides_json
 * （Gson 序列化的 [MapperOverrides]，可空字段缺省即省略，天然前向兼容）。
 *
 * 线程约定（与 CalibrationStorage 同款）：
 *  - [save] 为 suspend（withContext(Dispatchers.IO)，commit 落盘后才返回）；
 *  - [load] 为同步（首次调用含磁盘 IO 与 JSON 解析，调用方放 IO 协程——
 *    MainActivity 触发链在 IO 协程调用）。
 * 写入失败只记日志不抛：写盘失败不作废探测结果（本次会话 overrides 已生效，
 * 仅不持久化，下次同模型到达会重新探测）。
 */
class AdaptationStorage(context: Context) {

    private val gson = Gson()
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存自适应覆盖（IO 协程，commit 落盘后返回）。 */
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

    /**
     * 读取已存自适应条目（同步 IO，调用方放 IO 协程）。
     * 未保存过返回 null；schema 版本不匹配或 JSON 损坏时删除损坏数据并返回 null
     * （回默认映射，下一次模型身份到达走重新探测；数值越界由 mapper 读取点的
     * 防御性钳制兜底，此处不做深度校验）。
     *
     * 版本判定用 `!=` 而非 `<`：**任何**版本不符一律作废（含未来版本/回滚场景），
     * 保证读到的 JSON 一定由当前字段集产出。v1 → v2 的作废是本次故障修复的关键一环：
     * 旧条目里存着错误公式算出的眉毛低基座，若被容忍就会静默复现故障。
     */
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

        /**
         * 存储格式版本：overrides 字段语义/探测换算公式变更时 bump。
         *
         * 2：[MapperOverrides] 删除 browNeutralBase / browHalfRange，
         * 探测不再覆盖眉毛三通道。v1 条目里存着旧公式算出的低眉毛基座，
         * 若继续按 v1 解析会静默保留该值——bump 后 [load] 直接清空 adaptation_prefs，
         * 强制下一次模型身份到达走重新探测（新探测不含眉毛）。
         */
        const val SCHEMA_VERSION = 2
    }
}
