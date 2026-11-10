package com.exarry.arvtubelink.ui.legal

import android.content.Context
import android.net.Uri
import android.util.Log
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.utils.InAppLog
import java.security.MessageDigest

/** 协议版本与同意留痕：存已同意的版本串，冷启动与 agreement.txt 首行版本比对 */
object AgreementConsent {

    private const val TAG = "Agreement"

    private const val PREFS_APP = "app_settings"

    private const val PREFS_IMPORT_CONSENT = "import_consent"

    const val KEY_AGREEMENT_VERSION = "agreement_version"

    /** 旧版布尔键，仅升级迁移用 */
    const val LEGACY_KEY_AGREEMENT_ACCEPTED = "agreement_accepted"

    /** 解析失败兜底：与任何已存版本都不相等，强制重新弹协议 */
    const val FALLBACK_VERSION = "0.0.0"

    /** 首行版本声明：`（版本 1.2.0）` / `版本 1.2.0` / `版本：1.2.0` 都认 */
    private val VERSION_PATTERN = Regex("""版本\s*[:：]?\s*([0-9]+(?:\.[0-9]+)*)""")

    /** 只解析首个非空行的版本串，解析不到返回 FALLBACK_VERSION */
    fun readAgreementVersion(context: Context): String {
        val text = runCatching {
            context.resources.openRawResource(R.raw.agreement)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse { e ->
            InAppLog.w(TAG, "读取 agreement.txt 失败，强制重新展示协议", e)
            return FALLBACK_VERSION
        }

        // 正文里的「版本」字样不参与解析
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return FALLBACK_VERSION
        val match = VERSION_PATTERN.find(firstLine) ?: return FALLBACK_VERSION
        return match.groupValues[1]
    }

    /** 版本串相等才算同意过；旧布尔记录只打日志，仍返回 false 强制重读 */
    fun hasAcceptedCurrentVersion(context: Context, currentVersion: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_AGREEMENT_VERSION, null)
        if (stored != null) return stored == currentVersion

        val legacy = prefs.getBoolean(LEGACY_KEY_AGREEMENT_ACCEPTED, false)
        if (legacy) {
            InAppLog.i(TAG, "检测到旧版布尔同意记录，协议已版本化，需重新确认")
        }
        return false
    }

    /** 记录用户同意的版本串 */
    fun acceptCurrentVersion(context: Context, currentVersion: String) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGREEMENT_VERSION, currentVersion)
            .apply()
        InAppLog.i(TAG, "协议已同意: version=$currentVersion")
    }

    /** 导入留痕 `hash(zip) + 时间戳 + 协议版本`；Logcat 一行不受调试开关影响 */
    fun recordImportConsent(
        context: Context,
        zipSha256: String,
        agreementVersion: String,
        timestampMs: Long = System.currentTimeMillis(),
        modelId: String? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS_IMPORT_CONSENT, Context.MODE_PRIVATE)
        val seq = prefs.getInt(KEY_SEQ, 0) + 1
        val record = buildString {
            append("v=").append(agreementVersion)
            append(";sha256=").append(zipSha256)
            append(";ts=").append(timestampMs)
            if (modelId != null) append(";model=").append(modelId)
            append(";seq=").append(seq)
        }
        prefs.edit()
            .putInt(KEY_SEQ, seq)
            .putString("$KEY_RECORD_PREFIX$seq", record)
            .commit()

        val line = "导入同意留痕 seq=$seq version=$agreementVersion " +
            "sha256=$zipSha256 ts=$timestampMs"
        // InAppLog.i 在 debug 关闭时会丢弃，证据链用 Log.i
        Log.i(TAG, line)
        InAppLog.i(TAG, line)
    }

    /** 读取某条留痕，不存在返回 null */
    fun readImportConsent(context: Context, seq: Int): String? =
        context.getSharedPreferences(PREFS_IMPORT_CONSENT, Context.MODE_PRIVATE)
            .getString("$KEY_RECORD_PREFIX$seq", null)

    /** 计算 Uri 的 SHA-256，失败返回 "unavailable" 不抛 */
    fun sha256OfUri(context: Context, uri: Uri): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@runCatching "unavailable"
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse { e ->
        InAppLog.w(TAG, "计算 zip SHA-256 失败", e)
        "unavailable"
    }

    private const val KEY_SEQ = "seq"
    private const val KEY_RECORD_PREFIX = "record_"
}
