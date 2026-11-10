package com.exarry.arvtubelink.ui.live2d

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.exarry.arvtubelink.MainActivity
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ui.live2d.Live2DModelStore.ImportStage
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** WorkManager 后台任务：JobScheduler 进程级执行并持 wake lock，getForegroundInfo 升前台服务避 10 分钟上限 */
class Live2DImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val stagedPath = inputData.getString(KEY_STAGED_PATH)?.takeIf { it.isNotBlank() }
        val uriString = inputData.getString(KEY_URI)?.takeIf { it.isNotBlank() }
        if (stagedPath == null && uriString == null) {
            return Result.failure(failureData("未收到有效的文件地址"))
        }

        InAppLog.i(TAG, "后台导入开始: ${uriString ?: stagedPath}")

        // 先升前台再做重 IO：FGS 启动窗口只有几秒
        runCatching { setForeground(foregroundInfo(ImportStage.STAGING)) }
            .onFailure { InAppLog.w(TAG, "升前台失败，继续以普通后台任务执行", it) }

        val result = try {
            Live2DModelStore.importZip(
                applicationContext,
                uri = uriString?.let(Uri::parse),
                stagedFile = stagedPath?.let(::File),
            ) { stage ->
                // 进度写 setProgress 的 stage 字段，UI 从 WorkInfo.progress 读
                runCatching { setProgress(workDataOf(KEY_STAGE to stage.name)) }
                runCatching {
                    setForeground(foregroundInfo(stage))
                }.onFailure { InAppLog.w(TAG, "更新前台通知失败: $stage", it) }
            }
        } finally {
            stagedPath?.let { runCatching { File(it).delete() } }
        }

        return when (result) {
            is Live2DModelStore.ImportResult.Success -> {
                InAppLog.i(TAG, "后台导入成功: ${result.model.displayName}")
                val model = result.model
                // 终态 outputData 字段：success / modelId / displayName / mocPath
                Result.success(
                    workDataOf(
                        KEY_SUCCESS to true,
                        KEY_MODEL_ID to model.id,
                        KEY_DISPLAY_NAME to model.displayName,
                        KEY_MOC_PATH to model.mocPath,
                    )
                )
            }
            is Live2DModelStore.ImportResult.Failure -> {
                // 导入失败也用 Result.success 回传，避免 WorkManager 反复重试必然失败的包
                InAppLog.w(TAG, "后台导入失败: ${result.reason}")
                Result.success(failureData(result.reason))
            }
        }
    }

    /** 系统把任务提升为前台时调用，进程重启恢复也走这里 */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(ImportStage.STAGING)

    private fun foregroundInfo(stage: ImportStage): ForegroundInfo {
        val context = applicationContext
        ensureChannel(context)
        val text = context.getString(
            when (stage) {
                ImportStage.STAGING -> R.string.live2d_import_stage_staging
                ImportStage.EXTRACTING -> R.string.live2d_import_stage_extracting
                ImportStage.VALIDATING -> R.string.live2d_import_stage_validating
            }
        )

        // 不建新任务栈，避免出现第二个 MainActivity
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_live2d_import)
            .setContentTitle(context.getString(R.string.live2d_import_notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()

        // Android 14 起 FGS 必须声明类型，dataSync 对应本地数据处理
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live2d_import_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.live2d_import_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun failureData(reason: String): Data =
        workDataOf(KEY_SUCCESS to false, KEY_REASON to reason)

    companion object {
        const val TAG = "Live2D"

        const val KEY_URI = "uri"
        const val KEY_STAGED_PATH = "stagedPath"
        const val KEY_STAGE = "stage"
        const val KEY_SUCCESS = "success"
        const val KEY_REASON = "reason"
        const val KEY_MODEL_ID = "modelId"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_MOC_PATH = "mocPath"

        private const val CHANNEL_ID = "live2d_import"
        private const val NOTIFICATION_ID = 0x10E2D
    }
}

sealed interface Live2DImportProgress {
    data class Running(val stage: Live2DModelStore.ImportStage?) : Live2DImportProgress

    data class Success(
        val modelId: String,
        val displayName: String,
        val mocPath: String,
    ) : Live2DImportProgress

    data class Failure(val reason: String) : Live2DImportProgress

    /** 任务被取消，不是导入失败 */
    data object Cancelled : Live2DImportProgress
}

/** 入队与观测入口；GetContent 的 Uri 只带本进程临时读授权，入队前先复制到 cache */
object Live2DImportScheduler {

    /** 暂存到 cacheDir 后入队并返回 workId；暂存失败抛 IOException 且不入队 */
    suspend fun enqueue(context: Context, uri: Uri): UUID = withContext(Dispatchers.IO) {
        val staged = Live2DModelStore.stageUriToCache(context, uri)
        val request = OneTimeWorkRequestBuilder<Live2DImportWorker>()
            .setInputData(
                workDataOf(
                    Live2DImportWorker.KEY_URI to uri.toString(),
                    Live2DImportWorker.KEY_STAGED_PATH to staged.path,
                )
            )
            .addTag(TAG_ALL)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        request.id
    }

    fun observe(context: Context, workId: UUID): Flow<Live2DImportProgress> =
        WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(workId)
            .map { info -> info?.toProgress() ?: Live2DImportProgress.Running(null) }

    /** 进程被杀或 Activity 重建后接回仍在跑的导入 */
    suspend fun findActiveImport(context: Context): UUID? =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(TAG_ALL)
            .first()
            .lastOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            ?.id

    fun prune(context: Context) {
        WorkManager.getInstance(context).pruneWork()
    }

    private const val TAG_ALL = "live2d-import"

    private fun WorkInfo.toProgress(): Live2DImportProgress {
        val stage = progress.getString(Live2DImportWorker.KEY_STAGE)
            ?.let { name -> runCatching { Live2DModelStore.ImportStage.valueOf(name) }.getOrNull() }

        return when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                Live2DImportProgress.Running(stage)

            WorkInfo.State.SUCCEEDED -> {
                val output = outputData
                if (output.getBoolean(Live2DImportWorker.KEY_SUCCESS, false)) {
                    Live2DImportProgress.Success(
                        modelId = output.getString(Live2DImportWorker.KEY_MODEL_ID).orEmpty(),
                        displayName = output.getString(Live2DImportWorker.KEY_DISPLAY_NAME).orEmpty(),
                        mocPath = output.getString(Live2DImportWorker.KEY_MOC_PATH).orEmpty(),
                    )
                } else {
                    Live2DImportProgress.Failure(
                        output.getString(Live2DImportWorker.KEY_REASON).orEmpty()
                    )
                }
            }

            WorkInfo.State.FAILED -> Live2DImportProgress.Failure(
                outputData.getString(Live2DImportWorker.KEY_REASON).orEmpty()
            )

            WorkInfo.State.CANCELLED -> Live2DImportProgress.Cancelled
        }
    }
}
