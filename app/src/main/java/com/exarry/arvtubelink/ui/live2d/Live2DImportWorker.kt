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

/**
 * 模型导入的 WorkManager 后台任务。
 *
 * ## 为什么必须是 Worker 而不是界面协程
 * 界面协程挂在 Compose 组合 / Activity 生命周期上：切到后台、锁屏、系统回收 Activity，
 * 都会让解压协程随宿主一起被取消，留下半个目录。WorkManager 把任务登记到系统
 * JobScheduler，由**进程级**调度器执行，并在执行期间持有 wake lock：
 *  - 用户按 Home / 锁屏 → 解压继续跑完；
 *  - 进程被低内存杀掉 → 任务未被标记完成，系统重启后会重新投递（幂等由目标目录
 *    「先建后删」语义保证：失败/中断的目录在下次 [Live2DModelStore.listModels]
 *    扫描时因缺 model3.json 被清理）；
 *  - 走**前台服务**（[getForegroundInfo]）拿到用户可见通知，避免被 OOM-killer
 *    当成可回收的普通后台任务。
 *
 * ## 为什么前台服务而非普通 Worker
 * 普通 Worker 受 JobScheduler 的 10 分钟执行上限约束，且在高内存压力下更容易被
 * 直接停掉。模型包动辄几十 MB，前台服务是唯一既能跑久、又能在后台稳定存活的选择。
 * 代价是一条常驻通知（Android 的强制要求，不是我们的设计选择）。
 *
 * ## 结果如何回到 UI
 * 三条通道，各司其职（对应任务要求「通过 LiveData 或 Flow 通知 UI 刷新」）：
 *  1. **进度**：[setProgress] 写入 `stage`，UI 通过 [Live2DImportScheduler.observe] 的
 *     `WorkInfo.progress` 读到，用于更新通知与界面文案；
 *  2. **终态**：`Result.success(outputData)` 里带 `success` / `modelId` /
 *     `displayName` / `mocPath` / `reason`，UI 从 `WorkInfo.outputData` 解析；
 *  3. **清单变化**：UI 收到终态后调用 `Live2DModelStore.listModels` 重扫目录，
 *     磁盘是唯一事实来源，不额外维护一份内存副本。
 */
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

        // 先升前台再干活：FGS 的启动窗口只有几秒，必须在做重 IO 之前完成
        runCatching { setForeground(foregroundInfo(ImportStage.STAGING)) }
            .onFailure { InAppLog.w(TAG, "升前台失败，继续以普通后台任务执行", it) }

        val result = try {
            Live2DModelStore.importZip(
                applicationContext,
                uri = uriString?.let(Uri::parse),
                stagedFile = stagedPath?.let(::File),
            ) { stage ->
                runCatching { setProgress(workDataOf(KEY_STAGE to stage.name)) }
                runCatching {
                    setForeground(foregroundInfo(stage))
                }.onFailure { InAppLog.w(TAG, "更新前台通知失败: $stage", it) }
            }
        } finally {
            // 暂存文件是导入专用的中间产物，成功失败都不留在 cache 里
            stagedPath?.let { runCatching { File(it).delete() } }
        }

        return when (result) {
            is Live2DModelStore.ImportResult.Success -> {
                InAppLog.i(TAG, "后台导入成功: ${result.model.displayName}")
                val model = result.model
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
                // 注意：这里的 failure 指**导入**失败，任务本身是正常完成的，
                // 所以用 Result.success 回传，避免 WorkManager 把状态标成 FAILED
                // 后反复重试一个必然失败的恶意包。
                InAppLog.w(TAG, "后台导入失败: ${result.reason}")
                Result.success(failureData(result.reason))
            }
        }
    }

    /** WorkManager 在任务被系统提升为前台时调用（含进程重启后的恢复路径）。 */
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

        // 点通知回到主界面：不建新任务栈，避免出现第二个 MainActivity
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

        // dataSync：解压本质是本地数据处理，避免落到「未声明类型」的 FGS 限制里
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
                // LOW：常驻进度通知不该响铃/震动，但要始终可见
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

/**
 * 导入进度的 UI 侧视图模型。
 *
 * [Idle] 只用于「已入队但 WorkManager 还没吐出第一条 WorkInfo」的极短窗口，
 * 让界面在有界时间内不会停留在无反馈状态。
 */
sealed interface Live2DImportProgress {
    data class Running(val stage: Live2DModelStore.ImportStage?) : Live2DImportProgress

    data class Success(
        val modelId: String,
        val displayName: String,
        val mocPath: String,
    ) : Live2DImportProgress

    data class Failure(val reason: String) : Live2DImportProgress

    /** 任务被取消（用户清空队列 / 系统限制），不是导入失败。 */
    data object Cancelled : Live2DImportProgress
}

/**
 * 入队 + 观测的唯一入口，界面不直接碰 [WorkManager]。
 *
 * ## Uri 授权为什么会过期（这里最容易踩的坑）
 * `ActivityResultContracts.GetContent()` 返回的 Uri 只带**本次进程**的临时读授权，
 * 且该授权随发起它的 Activity 结束而回收。而 WorkManager 的任务是进程级的，
 * 可能在 Activity 已经销毁之后才真正去读这个 Uri —— 那时 `openInputStream` 抛
 * SecurityException，「切后台继续解压」的目标直接落空。
 *
 * 所以入队**之前**先把内容复制到我们自己的 cache 文件（带体积上限），
 * Worker 读的是这个暂存文件，与 Uri 授权生命周期彻底解耦。代价是一次额外拷贝，
 * 换来「入队后任何时候执行都成立」的确定性。
 *
 * ## 为什么按 workId 观测而不是按 unique work name
 * `enqueueUniqueWork(name, KEEP, request)` 在已有同名任务运行时会**静默丢弃**新请求，
 * 此时 `request.id` 永远不会有 WorkInfo —— 界面就会一直转圈。而 UI 已经用
 * `importInProgress` 禁用了导入按钮（文件选择器本身也是模态的），并发入口实际已被
 * 关掉，因此这里用普通 `enqueue` + 按 id 观测，语义最直接、失败模式最少。
 */
object Live2DImportScheduler {

    /**
     * 把 Uri 内容暂存到 cacheDir 并用暂存文件入队，返回可立即用于观测的 workId。
     *
     * suspend + IO：暂存最多搬 256 MB，绝不能放主线程。
     *
     * @throws IOException 读不到 Uri / 文件过大 / 空文件；此时**不会**入队
     */
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

    /**
     * 观测某个导入任务，直到进入终态。
     *
     * 用 [WorkManager.getWorkInfoByIdFlow]（2.9+ 的 Flow API）而不是 LiveData：
     * Compose 侧本来就在协程里收流，`LaunchedEffect` 直接 `collect` 即可，
     * 不需要把 LiveData 包成 `observeAsState`（LiveData 还要额外引 lifecycle-livedata，
     * 而本项目 UI 状态统一走 State，没有 LiveData 生态）。
     */
    fun observe(context: Context, workId: UUID): Flow<Live2DImportProgress> =
        WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(workId)
            .map { info -> info?.toProgress() ?: Live2DImportProgress.Running(null) }

    /**
     * 找出「当前仍在跑」的导入任务。
     *
     * 为什么需要它：进程被杀 / Activity 重建后，界面侧的 `workId` 状态随之丢失，
     * 但 WorkManager 里的任务还在跑。重新进页面时靠它把界面接回正在进行的导入，
     * 否则用户会看到一个「没有任何导入」的界面，而通知栏里仍在解压。
     */
    suspend fun findActiveImport(context: Context): UUID? =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(TAG_ALL)
            .first()
            .lastOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            ?.id

    /** 清理已结束的历史任务记录（WorkManager 的数据库会一直留着终态记录）。 */
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
