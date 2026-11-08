package com.exarry.arvtubelink.ui.live2d

import android.content.Context
import android.net.Uri
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 自定义 Live2D 模型的本地仓库：导入（zip 解压）、枚举、删除。
 *
 * ## 与 WebView 版的差别
 * 原生渲染器不需要 URL：它直接 `fopen` 本地文件。因此 [Model] 暴露的是
 * [Model.mocFile]（`.moc3` 的绝对路径），不再有虚拟域 URL / URL 编码那一层。
 *
 * ## 存储布局
 * ```
 * filesDir/live2d_models/            <- [userModelsRoot]
 *   ├── 1730000000000/               <- 每次导入一个独立目录（id = 目录名）
 *   │   ├── xxx.model3.json
 *   │   ├── xxx.moc3
 *   │   └── textures/ ...
 *   └── 1730000000123/
 *       └── inner/xxx.model3.json    <- zip 内自带子目录也能用（递归查找）
 * ```
 * 用「时间戳目录」而不是模型名做 id，理由：
 *  1. 模型名来自不可信的 zip（可能是中文/emoji/`..`/超长），直接当目录名要额外做一轮
 *     文件名校验，且同名模型会互相覆盖；
 *  2. 时间戳天然唯一、纯 ASCII，且天然满足「删除整个子目录」的语义。
 *  真正的显示名从 model3.json 里读，见 [readDisplayName]。
 *
 * ## 为什么必须有 Zip Slip 防范（重点）
 * zip 条目名是**攻击者完全可控的字符串**。`ZipEntry.name` 可以是 `../../shared_prefs/x.xml`，
 * 也可以是 `/data/data/com.exarry.arvtubelink/databases/y.db`（绝对路径）。朴素实现
 * `File(destDir, entry.name)` 会拼出一个跳出 destDir 的路径，解压即等于「任意文件写入」——
 * 攻击者可以先诱导用户导入一个恶意 zip，覆盖 shared_prefs / 其他应用可见文件，甚至写
 * 开机自启相关配置。这类漏洞即 CVE 类别中的 Zip Slip / path traversal。
 *
 * 本实现的防线（[extractZip]）：
 *  1. **规范化 + 前缀校验**：先 `canonicalFile` 再比对是否落在解压根目录内。用 canonical
 *     而非 `absolute`，是因为 canonical 会解析 `..` 与符号链接，堵住「先建软链再穿越」变体；
 *  2. **前缀必须带分隔符**：`/a/bc` 不是 `/a/b` 的子路径。只比 `startsWith("/a/b")` 会把
 *     兄弟目录 `/a/bc` 误判为合法，故比对串统一补 `File.separator`；
 *  3. **拒绝绝对路径与盘符**：`File.isAbsolute` 与 `contains(':')` 先行短路，给出清晰日志；
 *  4. **不信任 entry.isDirectory**：只按名字判断是否以 `/` 结尾，避免攻击者用
 *     「目录条目 + 恶意 name」组合绕过；
 *  5. **总量 / 条目数 / 单文件上限**：防 zip 炸弹（解压后几十 GB 撑爆 /data 分区）。
 *     超限一律中止并**回滚整个目录**，不留半个模型。
 *
 * 任一条目违规 → 抛 [ZipSlipException] / [ZipLimitException] → 上层删除整个目标目录并报错，
 * 绝不做「跳过坏条目继续解压」的宽容处理：一个含穿越条目的 zip 整体就是恶意的。
 */
object Live2DModelStore {

    private const val TAG = "Live2D"

    /** filesDir 下的根目录名。 */
    const val USER_MODELS_DIR_NAME = "live2d_models"

    /** 内置模型哨兵 id：与任何时间戳目录名都不冲突。 */
    const val BUILTIN_MODEL_ID = "__builtin__"

    /** Cubism 模型清单的固定后缀。 */
    private const val MODEL_SUFFIX = ".model3.json"

    /** MOC 文件后缀。 */
    private const val MOC_SUFFIX = ".moc3"

    /** 解压总量上限（解压后字节数）。Live2D 模型通常 5~40 MB，256 MB 足够宽松。 */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** 单文件上限，防单个超大条目（合法模型没有 64 MB 以上的单文件）。 */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** 条目数上限，防「百万空文件」耗尽 inode。 */
    private const val MAX_ENTRIES = 4096

    /** 单次拷贝缓冲。 */
    private const val COPY_BUFFER = 64 * 1024

    /** 用户模型根目录（不存在时创建）。 */
    fun userModelsRoot(context: Context): File =
        File(context.filesDir, USER_MODELS_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 一个可展示的模型。
     *
     * @param id 目录名（内置模型为 [BUILTIN_MODEL_ID]）
     * @param displayName 展示名（内置固定 "Sample"，用户模型见 [readDisplayName]）
     * @param mocPath 交给 native 的 `.moc3` 路径：内置模型是 assets 相对路径，
     *        用户模型是**绝对路径**
     * @param dir 用户模型目录；内置模型为 null
     */
    data class Model(
        val id: String,
        val displayName: String,
        val mocPath: String,
        val dir: File?,
    ) {
        /** 内置模型不可删除。 */
        val isBuiltin: Boolean get() = id == BUILTIN_MODEL_ID
    }

    /** 内置 Sample（列表第一项，不可删除）。 */
    val builtinModel = Model(
        id = BUILTIN_MODEL_ID,
        displayName = "Sample（内置）",
        mocPath = DEFAULT_MOC_ASSET,
        dir = null,
    )

    /** 内置模型的 .moc3 assets 相对路径（与 Live2DRenderer.DEFAULT_MOC_PATH 一致）。 */
    private const val DEFAULT_MOC_ASSET = "live2d_demo/model/sample/sample_greeter_t03.moc3"

    // ========================================================================
    //  枚举
    // ========================================================================

    /**
     * 扫描用户模型根目录，返回「内置 + 全部用户模型」。
     *
     * 目录里没有 model3.json（或没有对应 .moc3）的子目录直接跳过并顺手清理，
     * 保证列表里不会出现点不开的僵尸项。IO 较重，调用方应放在后台线程。
     */
    fun listModels(context: Context): List<Model> {
        val root = userModelsRoot(context)
        val userModels = root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name } // 时间戳目录名 = 导入时间，新的排前面
            ?.mapNotNull { dir -> modelOf(root, dir) }
            ?: emptyList()
        return listOf(builtinModel) + userModels
    }

    /** 单个目录 -> [Model]；找不到可用的 model3.json/.moc3 时返回 null（并删除空壳目录）。 */
    private fun modelOf(root: File, dir: File): Model? {
        val json = findModelJson(dir)
        if (json == null) {
            InAppLog.w(TAG, "模型目录缺少 model3.json，清理: ${dir.name}")
            runCatching { dir.deleteRecursively() }
            return null
        }
        val moc = findMocFile(json.parentFile ?: dir)
        if (moc == null) {
            InAppLog.w(TAG, "模型目录缺少 .moc3，清理: ${dir.name}")
            runCatching { dir.deleteRecursively() }
            return null
        }
        return Model(
            id = dir.name,
            displayName = readDisplayName(json) ?: dir.name,
            mocPath = moc.absolutePath,
            dir = dir,
        )
    }

    /** 递归找目录下第一个 `*.model3.json`。找不到返回 null。 */
    private fun findModelJson(dir: File): File? =
        findFirstBySuffix(dir, MODEL_SUFFIX)

    /** 递归找目录下第一个 `*.moc3`。找不到返回 null。 */
    private fun findMocFile(dir: File): File? =
        findFirstBySuffix(dir, MOC_SUFFIX)

    /**
     * 广度优先找第一个指定后缀的文件。
     *
     * 同层内按名字排序，保证多次调用结果稳定（`listFiles` 的顺序不保证）。
     */
    private fun findFirstBySuffix(dir: File, suffix: String): File? {
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val children = cur.listFiles() ?: continue
            for (f in children.sortedBy { it.name }) {
                if (f.isDirectory) {
                    stack.addLast(f)
                } else if (f.name.lowercase(Locale.US).endsWith(suffix)) {
                    return f
                }
            }
        }
        return null
    }

    /**
     * 读显示名：优先 model3.json 里的 `name` 字段，其次文件名去掉 `.model3.json`，
     * 最后返回 null（调用方回退目录名）。
     *
     * 说明：Cubism 标准 model3.json 规范（Version/FileReferences/Groups/HitAreas）**没有**
     * `name` 字段，但社区模型常自行加一个；有就用，没有就用文件名——比纯时间戳目录名友好。
     */
    private fun readDisplayName(json: File): String? {
        runCatching {
            val obj = JSONObject(json.readText())
            obj.optString("name").takeIf { it.isNotBlank() }?.let { return it.trim().take(48) }
        }
        val base = json.name
        val cut = base.length - MODEL_SUFFIX.length
        if (cut > 0) {
            return base.substring(0, cut).takeIf { it.isNotBlank() }?.take(48)
        }
        return null
    }

    // ========================================================================
    //  导入
    // ========================================================================

    /** 导入结果。失败原因已是可直接展示给用户的中文文案。 */
    sealed interface ImportResult {
        data class Success(val model: Model) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /**
     * 把用户选中的 zip 解压到 `live2d_models/<timestamp>/`，并校验其中确实有模型。
     *
     * 失败时**整个目标目录被删除**，磁盘上不会留下半成品。
     * 阻塞 IO，已在 [Dispatchers.IO] 内执行。
     *
     * @param uri `ActivityResultContracts.GetContent()` 返回的 content Uri
     */
    suspend fun importZip(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val root = userModelsRoot(context)
        val targetDir = newModelDir(root)
        InAppLog.i(TAG, "导入开始: $uri -> ${targetDir.name}")

        try {
            val stream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext fail(targetDir, "无法读取所选文件")
            stream.use { extractZip(it, targetDir) }

            val json = findModelJson(targetDir)
                ?: return@withContext fail(targetDir, "压缩包内没有找到 *.model3.json，请检查模型包结构")
            if (findMocFile(json.parentFile ?: targetDir) == null) {
                return@withContext fail(targetDir, "压缩包内没有找到 *.moc3 模型文件")
            }

            val model = modelOf(root, targetDir)
                ?: return@withContext fail(targetDir, "模型清单读取失败")

            InAppLog.i(TAG, "导入成功: ${model.displayName} (${model.id}) -> ${model.mocPath}")
            ImportResult.Success(model)
        } catch (e: ZipSlipException) {
            InAppLog.e(TAG, "Zip Slip 拦截: ${e.message}")
            fail(targetDir, "压缩包含非法路径（疑似 Zip Slip 攻击），已拒绝导入")
        } catch (e: ZipLimitException) {
            InAppLog.e(TAG, "解压超限: ${e.message}")
            fail(targetDir, "压缩包过大或条目过多（${e.message}），已拒绝导入")
        } catch (e: IOException) {
            InAppLog.e(TAG, "解压 IO 失败", e)
            fail(targetDir, "解压失败：${e.message ?: "IO 错误"}")
        } catch (e: Exception) {
            InAppLog.e(TAG, "解压异常", e)
            fail(targetDir, "导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 删除目录并返回失败结果（所有失败分支共用）。 */
    private fun fail(dir: File, reason: String): ImportResult.Failure {
        runCatching { dir.deleteRecursively() }
        return ImportResult.Failure(reason)
    }

    /** 生成一个不与现有目录冲突的时间戳目录。 */
    private fun newModelDir(root: File): File {
        var candidate = File(root, System.currentTimeMillis().toString())
        var n = 1
        // 同毫秒内连续导入（或系统时钟回拨）时追加序号
        while (candidate.exists()) {
            candidate = File(root, "${System.currentTimeMillis()}-${n++}")
        }
        candidate.mkdirs()
        return candidate
    }

    // ========================================================================
    //  解压（安全核心）
    // ========================================================================

    /**
     * 安全解压 [input] 到 [destDir]。
     *
     * 每个条目都经 [resolveSafely] 校验，任一违规立即抛异常中止（不做跳过式容错）。
     * 采用 [ZipInputStream] 顺序读取而非 `ZipFile`：content Uri 不是文件路径，只能拿到流。
     *
     * internal 而非 private：单元测试要直接喂构造好的恶意 zip 走这条真实路径，
     * 否则只能靠 UI 手测，回归时极易漏掉。
     */
    internal fun extractZip(input: InputStream, destDir: File) {
        val rootCanonical = destDir.canonicalFile
        // 前缀校验串统一补分隔符：`/a/bc` 不能因为 startsWith("/a/b") 被误判为子路径
        val rootPrefix = rootCanonical.path + File.separator

        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER)

        ZipInputStream(input.buffered(COPY_BUFFER)).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw ZipLimitException("条目数超过 $MAX_ENTRIES")
                }

                // 注意：不用 entry.isDirectory（由 zip 头部的属性位决定，可被伪造）。
                // 只看名字是否以 '/' 结尾，这是解压器的通行约定。
                val rawName = entry.name
                val isDir = rawName.endsWith("/") || rawName.endsWith("\\")

                val outFile = resolveSafely(rootCanonical, rootPrefix, rawName)

                if (isDir) {
                    if (!outFile.isDirectory && !outFile.mkdirs()) {
                        throw IOException("无法创建目录: ${outFile.name}")
                    }
                    zis.closeEntry()
                    continue
                }

                outFile.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("无法创建目录: ${parent.name}")
                    }
                }

                var entryBytes = 0L
                FileOutputStream(outFile).use { fos ->
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        entryBytes += n
                        totalBytes += n
                        // 边写边判，不等到解压完才发现超限（否则磁盘已经被撑爆）
                        if (entryBytes > MAX_ENTRY_BYTES) {
                            throw ZipLimitException("单文件超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB")
                        }
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw ZipLimitException("解压总量超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
                        }
                        fos.write(buffer, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }
        InAppLog.i(TAG, "解压完成: $entryCount 个条目, ${totalBytes / 1024} KB")
    }

    /**
     * 把 zip 条目名解析成 destDir 内的安全 File（**Zip Slip 的唯一防线**）。
     *
     * @param rootCanonical 解压根目录的 canonicalFile
     * @param rootPrefix 根目录 canonical 路径 + 分隔符
     * @param rawName zip 条目原始名
     * @throws ZipSlipException 名字绝对路径 / 含盘符 / 规范化后跳出根目录
     */
    private fun resolveSafely(rootCanonical: File, rootPrefix: String, rawName: String): File {
        if (rawName.isBlank()) throw ZipSlipException("空条目名")

        // 统一分隔符：zip 规范用 '/'，但恶意包可混入 '\'（在部分解压器上会被当分隔符）
        val normalized = rawName.replace('\\', '/')

        // 绝对路径（Unix）或带盘符（Windows 风格，Android 上无害但仍视为恶意）
        if (normalized.startsWith("/") || normalized.contains(':')) {
            throw ZipSlipException("绝对路径条目: $rawName")
        }
        // 显式穿越标记：先给清晰日志，真正的判定交给下面的 canonical 校验
        if (normalized.split('/').any { it == ".." }) {
            throw ZipSlipException("路径穿越条目: $rawName")
        }

        val candidate = File(rootCanonical, normalized)
        // canonicalFile 会解析 ".." 与已存在的符号链接 —— 这是最后也是最关键的一道闸
        val canonical = candidate.canonicalFile
        if (!canonical.path.startsWith(rootPrefix)) {
            throw ZipSlipException("条目跳出目标目录: $rawName -> ${canonical.path}")
        }
        // 条目名恰好等于根目录本身（如 "./"）时 canonical == root，不写文件
        if (canonical == rootCanonical) throw ZipSlipException("条目指向根目录: $rawName")
        return canonical
    }

    /** Zip Slip（路径穿越）被拦截。 */
    internal class ZipSlipException(message: String) : IOException(message)

    /** 解压超限（zip 炸弹防护）。 */
    internal class ZipLimitException(message: String) : IOException(message)

    // ========================================================================
    //  删除
    // ========================================================================

    /**
     * 删除一个用户模型（整个 `live2d_models/<id>` 子目录）。
     *
     * 安全约束：内置模型拒绝删除；id 必须能在根目录下解析出一个**真实子目录**。
     * 由于 id 来自 UI（列表项），理论上不会被外部控制，但仍显式校验它不含路径分隔符，
     * 避免「id = ../../databases」这类通过其它途径注入的值把删除扩大到根目录之外。
     *
     * @return true = 目录已不存在
     */
    fun deleteModel(context: Context, id: String): Boolean {
        if (id == BUILTIN_MODEL_ID) {
            InAppLog.w(TAG, "内置模型不可删除")
            return false
        }
        if (id.contains('/') || id.contains('\\') || id.contains("..")) {
            InAppLog.e(TAG, "非法模型 id，拒绝删除: $id")
            return false
        }
        val root = userModelsRoot(context).canonicalFile
        val dir = File(root, id).canonicalFile
        if (dir.parentFile?.canonicalPath != root.canonicalPath) {
            InAppLog.e(TAG, "模型目录不在根目录下，拒绝删除: ${dir.path}")
            return false
        }
        val ok = dir.deleteRecursively()
        InAppLog.i(TAG, "删除模型 $id -> $ok")
        return ok
    }
}
