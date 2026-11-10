package com.exarry.arvtubelink.ui.legal

import android.content.Context
import android.net.Uri
import android.util.Log
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.utils.InAppLog
import java.security.MessageDigest

/**
 * 协议版本与同意留痕。
 *
 * ## 为什么协议要版本化
 * 协议文本会随合规要求修订。用布尔值记录「已同意」会让老用户永远停留在
 * 旧版本条款上而不再看到新文本；改为记录**已同意的版本串**，冷启动时与
 * `res/raw/agreement.txt` 首行声明的版本比对，不一致就重新弹协议。
 *
 * ## 为什么导入要留痕
 * 模型资产由用户从外部带入，应用无法核实版权归属。用户点「同意并导入」时把
 * `hash(zip) + 时间戳 + 协议版本` 落盘并打日志，是「用户陈述与保证」这一
 * 法律主张的证据：日后若发生纠纷，可以证明是哪个文件、在何时、在哪个版本的
 * 条款下被用户确认导入。
 */
object AgreementConsent {

    private const val TAG = "Agreement"

    /** 应用设置域，与 MainActivity 的 appPrefs 同域（PREFS_APP）。 */
    private const val PREFS_APP = "app_settings"

    /** 导入留痕独立存放，避免与普通设置混杂、也便于整份导出取证。 */
    private const val PREFS_IMPORT_CONSENT = "import_consent"

    /** 冷启动比对用的键：值为用户已同意的协议版本串。 */
    const val KEY_AGREEMENT_VERSION = "agreement_version"

    /** 旧版布尔值键：仅用于升级迁移，读到 true 时视为同意过任意旧版本。 */
    const val LEGACY_KEY_AGREEMENT_ACCEPTED = "agreement_accepted"

    /** 解析失败时的兜底版本：宁可让协议每次弹出，也不要静默跳过。 */
    const val FALLBACK_VERSION = "0.0.0"

    /** 首行版本声明：`（版本 1.2.0）` / `版本 1.2.0` / `版本：1.2.0` 都认。 */
    private val VERSION_PATTERN = Regex("""版本\s*[:：]?\s*([0-9]+(?:\.[0-9]+)*)""")

    /**
     * 读取 `res/raw/agreement.txt` **首行**声明的协议版本。
     *
     * 约定：首行形如 `ARVTubeLink 用户协议与隐私声明（版本 1.2.0）`，
     * 也接受 `版本 1.2.0` / `版本：1.2.0` 这类变体。解析不到就返回
     * [FALLBACK_VERSION] —— 与任何已存版本都不相等，从而强制重新展示协议，
     * 而不是把不可读的协议当成「已同意」。
     */
    fun readAgreementVersion(context: Context): String {
        val text = runCatching {
            context.resources.openRawResource(R.raw.agreement)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse { e ->
            InAppLog.w(TAG, "读取 agreement.txt 失败，强制重新展示协议", e)
            return FALLBACK_VERSION
        }

        // 只看首行：版本声明固定在文件头，正文里的「版本」字样不应被误读
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return FALLBACK_VERSION
        val match = VERSION_PATTERN.find(firstLine) ?: return FALLBACK_VERSION
        return match.groupValues[1]
    }

    /**
     * 冷启动判定：已存版本与当前协议版本一致才算同意过。
     *
     * 兼容旧安装：早期版本只写了布尔值 `agreement_accepted`，读到时视为
     * 「同意过旧版本」，但仍然返回 false（版本必然不一致），从而让老用户
     * 在升级后重新阅读一次新文本。
     */
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

    /** 记录用户同意了哪个版本的协议。 */
    fun acceptCurrentVersion(context: Context, currentVersion: String) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGREEMENT_VERSION, currentVersion)
            .apply()
        InAppLog.i(TAG, "协议已同意: version=$currentVersion")
    }

    /**
     * 导入留痕：`hash(zip) + 时间戳 + 协议版本`。
     *
     * 写入 SharedPreferences 并打日志。日志同时进 Logcat 与 App 内运行日志，
     * 后者可由用户导出，作为设备端可查的证据副本。Logcat 一行**不受调试开关
     * 影响**（`InAppLog.i` 在 debug 关闭时会直接丢弃，证据不该被丢弃）。
     *
     * @param zipSha256 所选 zip 的 SHA-256（计算失败时为 "unavailable"）
     * @param modelId   导入后生成的模型目录 id（此时通常还未知，调用方传 null）
     */
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
        // 无条件 Logcat（证据链），InAppLog 只是 UI 侧的便捷副本
        Log.i(TAG, line)
        InAppLog.i(TAG, line)
    }

    /** 读取某一条留痕（诊断/取证用）；不存在返回 null。 */
    fun readImportConsent(context: Context, seq: Int): String? =
        context.getSharedPreferences(PREFS_IMPORT_CONSENT, Context.MODE_PRIVATE)
            .getString("$KEY_RECORD_PREFIX$seq", null)

    /**
     * 计算 `content://` / `file://` Uri 的 SHA-256。
     *
     * 失败不抛：留痕的价值在于「有记录」，把整个导入卡在哈希上反而更糟——
     * 失败时写入 "unavailable" 并在日志里带出原因。
     */
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
