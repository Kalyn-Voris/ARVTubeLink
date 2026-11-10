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

/** 自定义 Live2D 模型本地仓库：zip 导入、枚举、删除；id 为时间戳目录名 */
object Live2DModelStore {

    private const val TAG = "Live2D"

    const val USER_MODELS_DIR_NAME = "live2d_models"

    private const val MODEL_SUFFIX = ".model3.json"

    private const val MOC_SUFFIX = ".moc3"

    /** 单文件解压上限 450 MB，高分辨率贴图集通常单张 < 20 MB */
    private const val MAX_ENTRY_BYTES = 450L * 1024 * 1024

    /** 解压总量上限 900 MB，取单文件上限的 2 倍 */
    private const val MAX_TOTAL_BYTES = 900L * 1024 * 1024

    /** 压缩包体积上限 256 MB，防撑满 cacheDir */
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    /** 条目数上限 4096，防百万空文件耗尽 inode */
    private const val MAX_ENTRIES = 4096

    /** 压缩比上限 200:1，PNG/JPEG 约 1:1、moc3 约 2~4:1 */
    private const val MAX_COMPRESSION_RATIO = 200L

    /** 压缩比判定松弛 1 MB，避免小文件 deflate 头开销误判 */
    private const val RATIO_SLACK_BYTES = 1024L * 1024

    private const val COPY_BUFFER = 64 * 1024

    /** 压缩算法白名单：stored(0) / deflate(8) */
    private const val ZIP_METHOD_STORED = 0
    private const val ZIP_METHOD_DEFLATE = 8

    fun userModelsRoot(context: Context): File =
        File(context.filesDir, USER_MODELS_DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 一个可展示的模型；mocPath 恒为 filesDir 下的绝对路径 */
    data class Model(
        val id: String,
        val displayName: String,
        val mocPath: String,
        val dir: File,
    )

    /** 扫描用户模型根目录，跳过并清理缺少 model3.json/.moc3 的子目录；IO 重，须后台线程 */
    fun listModels(context: Context): List<Model> {
        val root = userModelsRoot(context)
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name } // 时间戳目录名 = 导入时间，新的排前面
            ?.mapNotNull { dir -> modelOf(dir) }
            ?: emptyList()
    }

    /** 单个目录 -> Model；缺少 model3.json/.moc3 时删除该目录并返回 null */
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

    private fun findModelJson(dir: File): File? =
        findFirstBySuffix(dir, MODEL_SUFFIX)

    private fun findMocFile(dir: File): File? =
        findFirstBySuffix(dir, MOC_SUFFIX)

    /** 广度优先找第一个指定后缀文件；同层按名字排序，listFiles 顺序不保证 */
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

    /** 读显示名：model3.json 的 name 字段，其次文件名去后缀；Cubism 规范无 name 字段 */
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

    /** 导入阶段，供 WorkManager 更新前台通知 */
    enum class ImportStage { STAGING, EXTRACTING, VALIDATING }

    /** 导入结果；失败原因已是可直接展示的中文文案 */
    sealed interface ImportResult {
        data class Success(val model: Model) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /** 导入唯一入口：纯 suspend + Dispatchers.IO，失败删除整个目标目录；stagedFile 优先于 uri */
    suspend fun importZip(
        context: Context,
        uri: Uri?,
        stagedFile: File? = null,
        onStage: (suspend (ImportStage) -> Unit)? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val root = userModelsRoot(context)
        val targetDir = newModelDir(root)
        InAppLog.i(TAG, "导入开始: ${stagedFile ?: uri} -> ${targetDir.name}")

        // 只删除自己创建的暂存文件，调用方传入的不删
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

    /** content Uri 复制到 cacheDir 临时文件：属性校验需随机访问，Uri 读授权随 Activity 回收过期 */
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

    /** 删除目录并返回失败结果 */
    private fun fail(dir: File, reason: String): ImportResult.Failure {
        runCatching { dir.deleteRecursively() }
        return ImportResult.Failure(reason)
    }

    /** 生成不与现有目录冲突的时间戳目录 */
    private fun newModelDir(root: File): File {
        var candidate = File(root, System.currentTimeMillis().toString())
        var n = 1
        // 同毫秒重复导入时追加序号
        while (candidate.exists()) {
            candidate = File(root, "${System.currentTimeMillis()}-${n++}")
        }
        candidate.mkdirs()
        return candidate
    }

    /** 兼容入口：流先落临时文件再走 extractZipFile；internal 供回归测试喂恶意 zip */
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

    /** 安全解压：审计中央目录 -> 对齐条目 -> 逐条预检 -> 边写边数 */
    internal fun extractZipFile(archive: File, destDir: File) {
        val rootCanonical = destDir.canonicalFile
        // 前缀补分隔符：/a/bc 不能因 startsWith("/a/b") 误判为子路径
        val rootPrefix = rootCanonical.path + File.separator

        val audited = Live2DZipAuditor.audit(archive)
        if (audited.size > MAX_ENTRIES) {
            throw ZipLimitException("条目数超过 $MAX_ENTRIES")
        }

        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER)

        // 静态元数据必须在打开 ZipFile 前判完：重名会让两套语义分叉，加密条目上 JDK ZipFile 只抛无上下文异常
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
            // 审计器与 JDK ZipFile 的条目数须一致，不一致即解析差异
            if (entries.size != audited.size) {
                throw ZipFormatException("中央目录条目数不一致（${audited.size} != ${entries.size}）")
            }

            for (index in entries.indices) {
                val zipEntry = entries[index]
                val meta = audited[index]
                if (++entryCount > MAX_ENTRIES) {
                    throw ZipLimitException("条目数超过 $MAX_ENTRIES")
                }
                if (zipEntry.name != meta.name) {
                    throw ZipFormatException(
                        "两套解析的条目名不一致（${meta.name} != ${zipEntry.name}）"
                    )
                }
                if (zipEntry.size != meta.uncompressedSize ||
                    zipEntry.compressedSize != meta.compressedSize
                ) {
                    throw ZipFormatException("两套解析的条目长度不一致: ${meta.name}")
                }

                val rawName = meta.name
                val outFile = resolveSafely(rootCanonical, rootPrefix, rawName)

                if (meta.kind == Live2DZipAuditor.Kind.DIRECTORY) {
                    if (!outFile.isDirectory && !outFile.mkdirs()) {
                        throw IOException("无法创建目录: ${outFile.name}")
                    }
                    continue
                }

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
                            // 边写边数，声明值可造假
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

                if (entryBytes != meta.uncompressedSize) {
                    throw ZipFormatException("条目实际长度与声明不符: $rawName")
                }
            }
        }
        InAppLog.i(TAG, "解压完成: $entryCount 个条目, ${totalBytes / 1024} KB")
    }

    /** 用中央目录声明值预检单文件上限、压缩比、剩余总量；声明值可造假 */
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
        // 压缩比为 0 的合法情形：空文件、stored(0)
        if (declared > 0 && compressed > 0) {
            val inflated = declared > compressed * MAX_COMPRESSION_RATIO + RATIO_SLACK_BYTES
            if (inflated) {
                throw ZipLimitException("压缩比异常（$declared / $compressed）: ${meta.name}")
            }
        }
    }

    /** 条目名解析成 destDir 内的安全 File，Zip Slip 的唯一防线；canonical 而非 absolute 才能解析 .. 与符号链接 */
    private fun resolveSafely(rootCanonical: File, rootPrefix: String, rawName: String): File {
        if (rawName.isBlank()) throw ZipSlipException("空条目名")

        // zip 规范用 '/'，恶意包可混入 '\'
        val normalized = rawName.replace('\\', '/')

        // 拒绝绝对路径与盘符
        if (normalized.startsWith("/") || normalized.contains(':')) {
            throw ZipSlipException("绝对路径条目: $rawName")
        }
        // 显式 .. 标记先给清晰日志
        if (normalized.split('/').any { it == ".." }) {
            throw ZipSlipException("路径穿越条目: $rawName")
        }

        val candidate = File(rootCanonical, normalized)
        // canonical 解析 .. 与已存在符号链接，是最后一道闸
        val canonical = candidate.canonicalFile
        if (!canonical.path.startsWith(rootPrefix)) {
            throw ZipSlipException("条目跳出目标目录: $rawName -> ${canonical.path}")
        }
        // canonical == root（如 "./"）不写文件
        if (canonical == rootCanonical) throw ZipSlipException("条目指向根目录: $rawName")
        return canonical
    }

    /** 路径穿越条目 */
    internal class ZipSlipException(message: String) : IOException(message)

    /** 特殊文件类型 / 加密 / 重名条目 */
    internal class ZipUnsafeEntryException(message: String) : IOException(message)

    /** 解压超限（zip 炸弹） */
    internal class ZipLimitException(message: String) : IOException(message)

    /** 删除 live2d_models/<id> 整个子目录；id 不含分隔符且须是根目录真实子目录 */
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
