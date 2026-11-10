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
import java.util.zip.ZipFile

/**
 * 自定义 Live2D 模型的本地仓库：导入（zip 解压）、枚举、删除。
 *
 * ## 与 WebView 版的差别
 * 原生渲染器不需要 URL：它直接 `fopen` 本地文件。因此 [Model] 暴露的是
 * [Model.mocPath]（`.moc3` 的绝对路径），不再有虚拟域 URL / URL 编码那一层。
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
 * 真正的显示名从 model3.json 里读，见 [readDisplayName]。
 *
 * ## 为什么必须有 Zip Slip 防范（重点）
 * zip 条目名是**攻击者完全可控的字符串**。`ZipEntry.name` 可以是 `../../shared_prefs/x.xml`，
 * 也可以是 `/data/data/com.exarry.arvtubelink/databases/y.db`（绝对路径）。朴素实现
 * `File(destDir, entry.name)` 会拼出一个跳出 destDir 的路径，解压即等于「任意文件写入」——
 * 攻击者可以先诱导用户导入一个恶意 zip，覆盖 shared_prefs / 其他应用可见文件，甚至写
 * 开机自启相关配置。这类漏洞即 CVE 类别中的 Zip Slip / path traversal。
 *
 * ## 为什么先落盘再解压（本次加固的结构性改动）
 * 旧实现用 [java.util.zip.ZipInputStream] 顺序读流，原因是 content Uri 拿不到文件路径。
 * 但流式解压有一个结构性缺陷：**只能看到本地头，看不到中央目录**，于是
 *  1. 拿不到条目的 unix mode / external attributes，无法识别符号链接与设备文件；
 *  2. 无法做「中央目录 vs 本地头」的一致性校验（解析差异攻击的温床）；
 *  3. 进程被杀时解压到一半没有任何可审计的中间态。
 * 现在先把 Uri 内容复制到 cacheDir 的临时文件（带体积上限），再用 [ZipFile] 随机访问：
 * 中央目录与本地头都能读到，属性检查才成立。临时文件与模型无关，导入结束即删。
 *
 * ## 本实现的防线（[extractZipFile]，由内到外）
 *  0. **条目类型白名单**（借鉴 7-Zip）：[Live2DZipAuditor] 读中央目录的 platform +
 *     external attributes，只有普通文件与目录允许落盘，符号链接 / 字符设备 / 块设备 /
 *     FIFO / socket 一律整包拒绝；
 *  1. **压缩算法白名单**：只放行 stored(0) 与 deflate(8)；加密位直接拒绝；
 *  2. **规范化 + 前缀校验**（[resolveSafely]，原有的核心逻辑，语义对齐）：先 `canonicalFile`
 *     再比对是否落在解压根目录内。用 canonical 而非 `absolute`，是因为 canonical 会解析
 *     `..` 与符号链接，堵住「先建软链再穿越」变体；
 *  3. **前缀必须带分隔符**：`/a/bc` 不是 `/a/b` 的子路径，比对串统一补 `File.separator`；
 *  4. **拒绝绝对路径与盘符**：`File.isAbsolute` 与 `contains(':')` 先行短路；
 *  5. **不信任 entry.isDirectory**：目录判定同时看名字分隔符与中央目录里的类型位；
 *  6. **总量 / 条目数 / 单文件上限 + 压缩比**：防 zip 炸弹。上限既按中央目录的**声明值**
 *     预检，也在写盘时**边写边数**——声明值可以是假的。超限一律中止并**回滚整个目录**；
 *  7. **重名条目拒绝**：同名条目后写覆盖先写，是「用合法文件掩护恶意文件」的常见手法。
 *
 * 任一条目违规 → 抛 [ZipSlipException] / [ZipUnsafeEntryException] / [ZipLimitException] /
 * [ZipFormatException] → 上层删除整个目标目录并报错，绝不做「跳过坏条目继续解压」的宽容处理：
 * 一个含穿越或特殊文件条目的 zip 整体就是恶意的。
 */
object Live2DModelStore {

    private const val TAG = "Live2D"

    /** filesDir 下的根目录名。 */
    const val USER_MODELS_DIR_NAME = "live2d_models"

    /** Cubism 模型清单的固定后缀。 */
    private const val MODEL_SUFFIX = ".model3.json"

    /** MOC 文件后缀。 */
    private const val MOC_SUFFIX = ".moc3"

    /**
     * 单文件解压后上限：450 MB（任务口径）。
     * 合法 Live2D 模型最重的是高分辨率贴图集，单张通常 < 20 MB，450 MB 已极宽松。
     */
    private const val MAX_ENTRY_BYTES = 450L * 1024 * 1024

    /** 解压总量上限：取 2 倍单文件上限，兜底又不与单文件规则互相矛盾。 */
    private const val MAX_TOTAL_BYTES = 900L * 1024 * 1024

    /** 压缩包（未解压）体积上限，防把 cacheDir 撑满。 */
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    /** 条目数上限，防「百万空文件」耗尽 inode。 */
    private const val MAX_ENTRIES = 4096

    /**
     * 压缩比上限（解压后 / 压缩后）。合法模型里的 PNG/JPEG/音频几乎不可再压（≈1:1），
     * .moc3 约 2~4:1；能到 200:1 的只有「全零填充」这类炸弹。
     * 另给 1 MB 松弛量，避免小文件因 deflate 头开销被误判。
     */
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val RATIO_SLACK_BYTES = 1024L * 1024

    /** 单次拷贝缓冲。 */
    private const val COPY_BUFFER = 64 * 1024

    private const val ZIP_METHOD_STORED = 0
    private const val ZIP_METHOD_DEFLATE = 8

    /** 用户模型根目录（不存在时创建）。 */
    fun userModelsRoot(context: Context): File =
        File(context.filesDir, USER_MODELS_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 一个可展示的模型。
     *
     * 模型全部由用户导入：所有条目都来自用户导入的 zip，
     * 因此 [mocPath] 恒为 `filesDir` 下的绝对路径，[dir] 恒非空。
     *
     * @param id 目录名（导入时生成的时间戳）
     * @param displayName 展示名，见 [readDisplayName]
     * @param mocPath 交给 native 的 `.moc3` 绝对路径
     * @param dir 用户模型目录
     */
    data class Model(
        val id: String,
        val displayName: String,
        val mocPath: String,
        val dir: File,
    )

    // ========================================================================
    //  枚举
    // ========================================================================

    /**
     * 扫描用户模型根目录，返回全部可用模型（空表 = 用户还没有导入过）。
     *
     * 目录里没有 model3.json（或没有对应 .moc3）的子目录直接跳过并顺手清理，
     * 保证列表里不会出现点不开的僵尸项。IO 较重，调用方应放在后台线程。
     */
    fun listModels(context: Context): List<Model> {
        val root = userModelsRoot(context)
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name } // 时间戳目录名 = 导入时间，新的排前面
            ?.mapNotNull { dir -> modelOf(dir) }
            ?: emptyList()
    }

    /** 单个目录 -> [Model]；找不到可用的 model3.json/.moc3 时返回 null（并删除空壳目录）。 */
    private fun modelOf(dir: File): Model? {
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

    /** 导入阶段，供 WorkManager 更新前台通知与日志。 */
    enum class ImportStage { STAGING, EXTRACTING, VALIDATING }

    /** 导入结果。失败原因已是可直接展示给用户的中文文案。 */
    sealed interface ImportResult {
        data class Success(val model: Model) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /**
     * 把用户选中的 zip 解压到 `live2d_models/<timestamp>/`，并校验其中确实有模型。
     *
     * 这是导入流程的**唯一入口**（UI 与 [Live2DImportWorker] 都走它）：
     * 不持有 Activity / Compose 状态，纯 `suspend` + `Dispatchers.IO`，
     * 因此可以被 WorkManager 在进程级后台执行器中安全调用，
     * 界面被销毁或 App 进入后台都不影响它继续跑完。
     *
     * 失败时**整个目标目录被删除**，磁盘上不会留下半成品。
     *
     * @param uri `ActivityResultContracts.GetContent()` 返回的 content Uri；可空
     * @param stagedFile 已经落盘的压缩包（由 [stageUriToCache] 产生）；可空。
     *        两者都给时优先用 [stagedFile]——它的读权限不过期，是后台任务的正解
     * @param onStage 阶段回调（Worker 用它更新前台通知）；可空
     */
    suspend fun importZip(
        context: Context,
        uri: Uri?,
        stagedFile: File? = null,
        onStage: (suspend (ImportStage) -> Unit)? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val root = userModelsRoot(context)
        val targetDir = newModelDir(root)
        InAppLog.i(TAG, "导入开始: ${stagedFile ?: uri} -> ${targetDir.name}")

        // 由本函数负责删除的暂存文件（调用方传入的不删，可能是它自己的中间产物）
        var ownedStaging: File? = null
        try {
            val archive: File = when {
                stagedFile != null && stagedFile.isFile -> stagedFile
                uri != null -> {
                    onStage?.invoke(ImportStage.STAGING)
                    stageUriToCache(context, uri).also { ownedStaging = it }
                }
                else -> return@withContext fail(targetDir, "无法读取所选文件")
            }

            onStage?.invoke(ImportStage.EXTRACTING)
            extractZipFile(archive, targetDir)

            onStage?.invoke(ImportStage.VALIDATING)
            val json = findModelJson(targetDir)
                ?: return@withContext fail(targetDir, "压缩包内没有找到 *.model3.json，请检查模型包结构")
            if (findMocFile(json.parentFile ?: targetDir) == null) {
                return@withContext fail(targetDir, "压缩包内没有找到 *.moc3 模型文件")
            }

            val model = modelOf(targetDir)
                ?: return@withContext fail(targetDir, "模型清单读取失败")

            InAppLog.i(TAG, "导入成功: ${model.displayName} (${model.id}) -> ${model.mocPath}")
            ImportResult.Success(model)
        } catch (e: ZipSlipException) {
            InAppLog.e(TAG, "Zip Slip 拦截: ${e.message}")
            fail(targetDir, "压缩包含非法路径（疑似 Zip Slip 攻击），已拒绝导入")
        } catch (e: ZipUnsafeEntryException) {
            InAppLog.e(TAG, "特殊文件条目拦截: ${e.message}")
            fail(targetDir, "压缩包含不支持的特殊文件（符号链接/设备文件等），已拒绝导入")
        } catch (e: ZipFormatException) {
            InAppLog.e(TAG, "zip 结构校验失败: ${e.message}")
            fail(targetDir, "压缩包结构异常（${e.message}），已拒绝导入")
        } catch (e: ZipLimitException) {
            InAppLog.e(TAG, "解压超限: ${e.message}")
            fail(targetDir, "压缩包过大或条目过多（${e.message}），已拒绝导入")
        } catch (e: IOException) {
            InAppLog.e(TAG, "解压 IO 失败", e)
            fail(targetDir, "解压失败：${e.message ?: "IO 错误"}")
        } catch (e: Exception) {
            InAppLog.e(TAG, "解压异常", e)
            fail(targetDir, "导入失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            ownedStaging?.delete()
        }
    }

    /**
     * 把 content Uri 的内容复制到 cacheDir 的临时文件并返回。
     *
     * 为什么要落盘（两个理由，缺一不可）：
     *  1. **属性校验需要随机访问**：content Uri 只能顺序读，而条目类型与
     *     「中央目录 vs 本地头」一致性校验都必须先读到中央目录（见类注释
     *     「为什么先落盘再解压」）；
     *  2. **Uri 读授权会过期**：`GetContent` 的授权随发起它的 Activity 回收，
     *     后台 Worker 可能在那之后才执行——先落盘就把授权生命周期解耦了。
     *
     * 复制过程带体积上限，避免攻击者用一个超大 Uri 先撑爆 cacheDir。
     *
     * @throws IOException 读不到 Uri 或超过 [MAX_ARCHIVE_BYTES]
     */
    @Throws(IOException::class)
    fun stageUriToCache(context: Context, uri: Uri): File {
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        val staging = File.createTempFile("live2d-import-", ".zip", context.cacheDir)
        try {
            stream.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_ARCHIVE_BYTES) {
                            throw ZipLimitException("压缩包超过 ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB")
                        }
                        output.write(buffer, 0, n)
                    }
                    if (copied == 0L) throw ZipFormatException("所选文件为空")
                }
            }
            return staging
        } catch (e: Throwable) {
            staging.delete()
            throw e
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
     * 兼容入口：把流写到临时文件后交给 [extractZipFile]。
     *
     * 保留这个签名是为了让既有回归测试（构造 `ByteArrayInputStream` 喂恶意 zip）
     * 仍然走**同一条**真实解压路径，而不是另开一条只有测试才用的分支。
     *
     * internal 而非 private：单元测试要直接喂构造好的恶意 zip 走这条真实路径，
     * 否则只能靠 UI 手测，回归时极易漏掉。
     */
    internal fun extractZip(input: InputStream, destDir: File) {
        val staging = File.createTempFile("live2d-extract-", ".zip")
        try {
            FileOutputStream(staging).use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    copied += n
                    if (copied > MAX_ARCHIVE_BYTES) {
                        throw ZipLimitException("压缩包超过 ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB")
                    }
                    out.write(buffer, 0, n)
                }
                if (copied == 0L) throw ZipFormatException("压缩包为空")
            }
            extractZipFile(staging, destDir)
        } finally {
            staging.delete()
        }
    }

    /**
     * 安全解压 [archive] 到 [destDir]（[ZipFile] 随机访问版）。
     *
     * 顺序：
     *  1. [Live2DZipAuditor] 读中央目录 + 校验每个本地头，得到逐条目的类型与声明长度；
     *  2. 条目数与 [ZipFile] 枚举结果对齐（按下标配对，避免编码解码差异造成错位）；
     *  3. 逐条目做类型 / 算法 / 加密 / 压缩比 / 剩余总量预检；
     *  4. 落盘时再边写边数，防止中央目录声明值造假。
     *
     * 任一条目违规立即抛异常中止（不做跳过式容错）。
     */
    internal fun extractZipFile(archive: File, destDir: File) {
        val rootCanonical = destDir.canonicalFile
        // 前缀校验串统一补分隔符：`/a/bc` 不能因为 startsWith("/a/b") 被误判为子路径
        val rootPrefix = rootCanonical.path + File.separator

        val audited = Live2DZipAuditor.audit(archive)
        if (audited.size > MAX_ENTRIES) {
            throw ZipLimitException("条目数超过 $MAX_ENTRIES")
        }

        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER)

        // 预检 1：静态元数据。**必须在打开 ZipFile 之前**做完：
        //  - 重名条目会让「按名字索引」和「按中央目录顺序遍历」两套语义分叉；
        //  - 加密条目上 JDK 自己的 ZipFile 会直接抛 `invalid CEN header (encrypted entry)`，
        //    那是一个不带上下文的 ZipException，用户只看到「解压失败」却不知道是安全拦截。
        //    这里先判掉，异常才带得上「加密条目 / 符号链接」这种可解释的信息。
        val seenNames = HashSet<String>(audited.size * 2)
        for (meta in audited) {
            when (meta.kind) {
                Live2DZipAuditor.Kind.SYMLINK ->
                    throw ZipUnsafeEntryException("符号链接条目: ${meta.name}")
                Live2DZipAuditor.Kind.DEVICE ->
                    throw ZipUnsafeEntryException("设备/FIFO/socket 条目: ${meta.name}")
                Live2DZipAuditor.Kind.OTHER ->
                    throw ZipUnsafeEntryException("不支持的特殊文件类型: ${meta.name}")
                Live2DZipAuditor.Kind.DIRECTORY, Live2DZipAuditor.Kind.REGULAR -> Unit
            }
            if (meta.isEncrypted) {
                throw ZipUnsafeEntryException("加密条目: ${meta.name}")
            }
            if (meta.method != ZIP_METHOD_STORED && meta.method != ZIP_METHOD_DEFLATE) {
                throw ZipFormatException("不支持的压缩算法 ${meta.method}: ${meta.name}")
            }
            if (!seenNames.add(meta.name)) {
                throw ZipUnsafeEntryException("重复条目名: ${meta.name}")
            }
        }

        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            // 预检 2：两套解析器（本项目的审计器 / JDK 的 ZipFile）看到的条目数必须一致。
            // 不一致意味着至少有一方读错了结构，属于解析差异，直接拒绝。
            if (entries.size != audited.size) {
                throw ZipFormatException("中央目录条目数不一致（${audited.size} != ${entries.size}）")
            }

            for (index in entries.indices) {
                val zipEntry = entries[index]
                val meta = audited[index]
                if (++entryCount > MAX_ENTRIES) {
                    throw ZipLimitException("条目数超过 $MAX_ENTRIES")
                }
                // 两套解析对同一下标给出的名字必须相同
                if (zipEntry.name != meta.name) {
                    throw ZipFormatException(
                        "两套解析的条目名不一致（${meta.name} != ${zipEntry.name}）"
                    )
                }
                // 长度也必须一致：任何一方读错都是解析差异
                if (zipEntry.size != meta.uncompressedSize ||
                    zipEntry.compressedSize != meta.compressedSize
                ) {
                    throw ZipFormatException("两套解析的条目长度不一致: ${meta.name}")
                }

                // ① 类型 / 算法 / 加密 / 重名已在打开 ZipFile 之前统一预检，这里只剩逐条目处理

                val rawName = meta.name
                val outFile = resolveSafely(rootCanonical, rootPrefix, rawName)

                if (meta.kind == Live2DZipAuditor.Kind.DIRECTORY) {
                    if (!outFile.isDirectory && !outFile.mkdirs()) {
                        throw IOException("无法创建目录: ${outFile.name}")
                    }
                    continue
                }

                // ② 声明值预检（便宜，先拦住明显炸弹，避免白写盘）
                checkDeclaredLimits(meta, totalBytes)

                outFile.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("无法创建目录: ${parent.name}")
                    }
                }

                var entryBytes = 0L
                zip.getInputStream(zipEntry).use { ins ->
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val n = ins.read(buffer)
                            if (n <= 0) break
                            entryBytes += n
                            totalBytes += n
                            // 边写边判，不等到解压完才发现超限（否则磁盘已经被撑爆）
                            if (entryBytes > MAX_ENTRY_BYTES) {
                                throw ZipLimitException(
                                    "单文件超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB"
                                )
                            }
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw ZipLimitException(
                                    "解压总量超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB"
                                )
                            }
                            fos.write(buffer, 0, n)
                        }
                    }
                }

                // ④ 声明长度与实测不符：中央目录在说谎，按恶意处理
                if (entryBytes != meta.uncompressedSize) {
                    throw ZipFormatException("条目实际长度与声明不符: $rawName")
                }
            }
        }
        InAppLog.i(TAG, "解压完成: $entryCount 个条目, ${totalBytes / 1024} KB")
    }

    /**
     * 用中央目录的声明值做预检：单文件上限、压缩比、剩余总量。
     *
     * 声明值可以造假（落盘循环里以实际字节为准），但预检能在「恶意包故意声明一个 3 GB
     * 的文件」时省掉无谓的磁盘写入。
     */
    private fun checkDeclaredLimits(meta: Live2DZipAuditor.Entry, totalBytesSoFar: Long) {
        val declared = meta.uncompressedSize
        if (declared > MAX_ENTRY_BYTES) {
            throw ZipLimitException(
                "单文件声明 ${declared / 1024 / 1024} MB，超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB"
            )
        }
        if (totalBytesSoFar + declared > MAX_TOTAL_BYTES) {
            throw ZipLimitException("解压总量超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
        }

        val compressed = meta.compressedSize
        // 压缩比为 0 的两类合法情形：空文件、stored（压缩后 == 解压后）
        if (declared > 0 && compressed > 0) {
            val inflated = declared > compressed * MAX_COMPRESSION_RATIO + RATIO_SLACK_BYTES
            if (inflated) {
                throw ZipLimitException("压缩比异常（$declared / $compressed）: ${meta.name}")
            }
        }
    }

    /**
     * 把 zip 条目名解析成 destDir 内的安全 File（**Zip Slip 的唯一防线**）。
     *
     * 逻辑与加固前严格一致——这是红线要求保留的核心校验，新增防线全部叠加在它之上。
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

    /** 特殊文件类型 / 加密 / 重名条目被拦截。 */
    internal class ZipUnsafeEntryException(message: String) : IOException(message)

    /** 解压超限（zip 炸弹防护）。 */
    internal class ZipLimitException(message: String) : IOException(message)

    // ========================================================================
    //  删除
    // ========================================================================

    /**
     * 删除一个用户模型（整个 `live2d_models/<id>` 子目录）。
     *
     * 安全约束：id 必须能在根目录下解析出一个**真实子目录**。
     * 由于 id 来自 UI（列表项），理论上不会被外部控制，但仍显式校验它不含路径分隔符，
     * 避免「id = ../../databases」这类通过其它途径注入的值把删除扩大到根目录之外。
     *
     * @return true = 目录已不存在
     */
    fun deleteModel(context: Context, id: String): Boolean {
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
