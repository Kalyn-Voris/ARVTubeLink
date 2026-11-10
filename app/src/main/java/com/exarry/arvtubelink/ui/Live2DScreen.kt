package com.exarry.arvtubelink.ui

import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.ui.live2d.Live2DImportProgress
import com.exarry.arvtubelink.ui.live2d.Live2DImportScheduler
import com.exarry.arvtubelink.ui.live2d.Live2DModelStore
import com.exarry.arvtubelink.ui.live2d.Live2DParamMap
import com.exarry.arvtubelink.ui.live2d.Live2DRenderer
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Live2D 渲染页（Purism Core 原生渲染 + 面捕参数驱动 + 动作/表情）。
 *
 * ## 目的
 * 用原生 C++/JNI 替代 WebView 方案，并在其上把 AR 面捕数据实时推给模型：
 * 头动、眼动、嘴动三条主线跟随人脸。
 *
 * ## 渲染链路
 * `GLSurfaceView`（EGL/GL 线程）→ [Live2DRenderer]（Kotlin 桥）→
 * `liblive2d_jni.so`（Purism Core 求值 + GLES2 绘制）。
 * 模型数据全部在 native 侧：Kotlin 只传 [android.content.res.AssetManager] 与
 * `.moc3` 的 assets 相对路径，顶点/索引/投影都在 C++ 里算。
 *
 * ## 面捕驱动（P3）
 * 数据源是 [FaceRenderer.latestFaceSnapshot] —— 即 BlendShapeMapper + Smoother
 * 的既有输出，本页**只读**，不参与任何映射算法（红线）。
 * 推流协程以 [PARAM_PUSH_HZ] 读取快照，经 [Live2DParamMap] 换算成 Cubism 量纲，
 * 打包成 `[id, value, ...]` 交给 [Live2DRenderer.updateParams]。
 *
 * 为什么是「定时拉」而不是「人脸回调推」：
 *  - 拉取频率独立于相机帧率（AR 帧率随设备波动，30Hz 是稳定上限）；
 *  - 读不到新快照时重复推同一份值是无害的，而回调链一旦断链模型会冻住；
 *  - 协程挂在本页组合生命周期上，切走 Tab 自动取消，不会后台空转。
 *
 * ## 动作 / 表情（P4）
 * 动作与表情由 native 侧解析 model3.json 得到，本页只用两个横向按钮条呈现：
 * 动作组（Idle / Tap…）与表情列表（f00…f0N）。默认在 native 侧自动播第一组
 * 动作的第 0 个，因此页面一打开就是「活着」的。
 *
 * ## 手势（P5 / P6）
 * `detectTransformGestures` 同时给出拖拽位移与双指缩放，两者换算成
 * 「屏幕半宽/半高」为单位的归一化偏移后交给 [Live2DRenderer.setTransform]，
 * 由 C++ 侧合成到 MVP 上。
 *
 * 边界规则（P6 起）：**不再是「模型中心必须在屏幕内」**，而是按模型包围盒算——
 * 只要 bbox 与视口的交集还剩 [Live2DRenderer] 侧定义的 80px，就允许继续拖。
 * 因此放大后可以把头部拖到屏幕中心（旧规则会硬顶住，这是「放大后拖不动」的根因）。
 * 可行区间由 native 的 `nativePanLimits` 给出（单一事实来源），这里用它把
 * Compose 侧累加值同步钳住，避免「UI 以为还能拖、画面已经不动」的割裂。
 *
 * 缩放边界 0.5~3.0 仍由 native 统一钳制。
 *
 * ## 重置视图（P6）
 * 右侧悬浮按钮把 panX/panY 归零、缩放回 1x（初始适配大小）。放在**右边缘垂直居中**
 * 而不是顶栏/底栏：沉浸模式下（系统栏被隐藏、顶栏随遮罩让位）它仍然在拇指
 * 可达范围内，且不与左上 FPS、右上状态、底部动作条抢位置。
 *
 * ## 生命周期
 * - `RENDERMODE_CONTINUOUSLY`：动作时间轴与参数每帧都可能变，必须连续渲染。
 * - 组合离开 → [Live2DRenderer.release] 释放 native 内存。
 * - 前后台：跟随 [LocalLifecycleOwner]，onPause 停渲染线程、onResume 恢复。
 * - **回前台必须重启推流协程**：EGL context 在 onPause 时被销毁，GL 线程随之
 *   停摆；若协程在后台期间已因宿主移除组合而取消，只恢复 GLSurfaceView 是不够的
 *   （模型有渲染但没有任何参数写入）。用 [resumeEpoch] 做 key，每次 onResume
 *   都让推流协程重新走一遍「加载 → 绑定 → 推流」，与首次进入同一条路径。
 *
 * @param modifier 布局修饰符（由 AppRoot 传入，占满内容区）
 * @param faceRenderer 面捕渲染器（参数源）；null = 只渲染静态姿态
 * @param onUserActivity 用户在画布上的任意触摸（点击/拖动）：宿主据此唤醒沉浸 UI 并重启空闲计时
 * @param chromeVisible 页内控件（FPS / 状态 / 动作表情条 / 侧边按钮）是否可见；沉浸模式下收起
 * @param chromePadding 页内控件的安全区内边距（App 顶/底栏占位）。画布本身恒定铺满，
 *   只有悬浮控件让位——否则进出沉浸时 GLSurfaceView 会跟着改尺寸、模型整体缩放。
 */
@Composable
fun Live2DContent(
    modifier: Modifier = Modifier,
    faceRenderer: FaceRenderer? = null,
    onUserActivity: () -> Unit = {},
    chromeVisible: Boolean = true,
    chromePadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---- P6：自定义模型管理 ----
    // 应用不内置任何模型：null = 用户还没有导入过，页面呈现空状态引导。
    var currentModel by remember { mutableStateOf<Live2DModelStore.Model?>(null) }
    val availableModels = remember { mutableStateListOf<Live2DModelStore.Model>() }
    // 每次「切换模型」自增，作为加载协程的 key。0 = 首次加载（走 init 的幂等路径）。
    var modelEpoch by remember { mutableIntStateOf(0) }
    // 已成功加载到哪个 epoch：回前台时不必重新读 moc3/解码纹理
    var loadedModelEpoch by remember { mutableIntStateOf(-1) }

    var modelDialogVisible by remember { mutableStateOf(false) }
    // 导入状态由 WorkManager 驱动：本变量只做「界面侧快照」，真正的任务在进程级后台执行。
    var importInProgress by remember { mutableStateOf(false) }
    // 当前观测中的导入任务 id。跨重组保持，避免每次重组重复接流。
    var importWorkId by remember { mutableStateOf<UUID?>(null) }
    var pendingDelete by remember { mutableStateOf<Live2DModelStore.Model?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Renderer 跨重组保持：内部持有 native 模型状态，重新 new 会丢掉已加载的模型。
    // 首次创建时不带模型（null），等 listModels 恢复出当前模型后再 loadModel。
    // 不重建 renderer（重建会让 GLSurfaceView 的 setRenderer 二次调用抛异常）。
    val renderer = remember { Live2DRenderer(context.assets) }
    val surfaceViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

    var status by remember { mutableStateOf("尚未加载模型") }
    var isError by remember { mutableStateOf(false) }

    // ---- P4：动作 / 表情清单与当前选择 ----
    val motionGroups = remember { mutableStateListOf<Live2DRenderer.MotionGroup>() }
    val expressions = remember { mutableStateListOf<Live2DRenderer.ExpressionInfo>() }
    // 当前选中的动作组下标（-1 = 无）与表情下标（-1 = 无表情）
    var activeMotionGroup by remember { mutableStateOf(-1) }
    var activeExpression by remember { mutableStateOf(-1) }

    // ---- 任务 3：FPS HUD ----
    var fps by remember { mutableStateOf(0f) }

    // ---- P5：手势变换状态（与 native 侧同量纲：屏幕半宽/半高归一化）----
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    // ---- P5：回前台世代号 ----
    // 作为推流协程的 key：每次 onResume 自增，让协程重新启动一遍。
    // 用 Int 而不是 Boolean，避免「连续两次 onResume」被当成同一个值而不重启。
    var resumeEpoch by remember { mutableIntStateOf(0) }

    // 独立协程轮询 FPS：与参数推流互不干扰，模型没就绪时也照常跑（显示 0）
    LaunchedEffect(renderer) {
        while (isActive) {
            val value = runCatching { renderer.fps() }.getOrDefault(0f)
            if (value > 0f) fps = value
            delay(FPS_POLL_INTERVAL_MS)
        }
    }

    // 模型清单：进页面读一次；导入/删除后手动刷新
    val refreshModels: () -> Unit = {
        scope.launch {
            val models = withContext(Dispatchers.IO) { Live2DModelStore.listModels(context) }
            availableModels.clear()
            availableModels.addAll(models)
            // 首次进入或当前模型已不存在：自动接管列表里的第一个（没有就保持空状态）
            if (currentModel == null) {
                currentModel = models.firstOrNull()
                if (currentModel != null) modelEpoch++
            }
        }
    }
    LaunchedEffect(Unit) { refreshModels() }

    // 进程被杀 / Activity 重建后把界面接回仍在跑的导入任务：
    // workId 只存在 Compose 状态里，重建后丢失，但 WorkManager 的任务还在继续。
    LaunchedEffect(Unit) {
        val active = withContext(Dispatchers.IO) {
            Live2DImportScheduler.findActiveImport(context)
        }
        if (active != null && importWorkId == null) {
            InAppLog.i(TAG, "接回正在进行的导入任务: $active")
            importInProgress = true
            importWorkId = active
        }
    }

    // 观测导入任务直到终态。key = importWorkId：每次新入队都重开一条流。
    //
    // 用 Flow 而不是 LiveData：见 Live2DImportScheduler.observe 的注释。
    // 终态处理完把 importWorkId 置回 null，避免重复消费同一份结果。
    LaunchedEffect(importWorkId) {
        val workId = importWorkId ?: return@LaunchedEffect
        // runCatching：WorkManager 的生活周期很短，任务记录被 prune 后流会正常结束，
        // 此时 first{} 找不到终态元素会抛 NoSuchElementException——不是错误，忽略即可。
        runCatching {
            Live2DImportScheduler.observe(context, workId)
                .onEach { progress ->
                    when (progress) {
                        is Live2DImportProgress.Running -> {
                            importInProgress = true
                        }

                        is Live2DImportProgress.Success -> {
                            importInProgress = false
                            // 磁盘是唯一事实来源：重扫目录而不是把 Worker 的输出拼进列表
                            refreshModels()
                            val restored = withContext(Dispatchers.IO) {
                                Live2DModelStore.listModels(context)
                                    .firstOrNull { it.id == progress.modelId }
                            }
                            if (restored != null) {
                                currentModel = restored
                                modelEpoch++
                                importMessage = "已导入：${restored.displayName}"
                            } else {
                                // 目录里有文件但凑不出可用模型（缺 moc3 等），listModels 已清理
                                importMessage = "导入完成，但模型不可用（缺少 model3.json 或 .moc3）"
                            }
                            importWorkId = null
                        }

                        is Live2DImportProgress.Failure -> {
                            importInProgress = false
                            importWorkId = null
                            importMessage = progress.reason.ifBlank { "导入失败" }
                        }

                        Live2DImportProgress.Cancelled -> {
                            importInProgress = false
                            importWorkId = null
                            importMessage = "导入已取消"
                        }
                    }
                }
                .first { it !is Live2DImportProgress.Running }
        }
    }

    // 加载 + 绑定 + 推流放在同一个 effect 里串行完成。
    // 拆成两个 effect 时，绑定后改写 key 会让推流循环取消再重启一次
    // （实测日志里「面捕驱动启动」出现两遍），单 effect 天然只启动一次。
    //
    // resumeEpoch 参与 key：切后台再回前台时协程必须重新启动。
    // 否则「GL 线程恢复 + 模型重算」都做了，却没有任何一帧参数被写进去，
    // 表现为画面恢复但面捕失效（严重时配合 pose 中间态只剩衣服）。
    //
    // modelEpoch 参与 key：切换模型后必须重新绑定参数、重读动作/表情清单。
    LaunchedEffect(renderer, faceRenderer, resumeEpoch, modelEpoch) {
        // 没有模型（用户还没导入）：空状态由 UI 层呈现，这里不碰 native
        val model = currentModel
        if (model == null) {
            isError = false
            status = "尚未加载模型"
            surfaceViewRef[0]?.requestRender()
            return@LaunchedEffect
        }

        // 已经加载过当前模型（例如只是回前台）：native 侧模型仍在，跳过重读
        val ready = if (loadedModelEpoch == modelEpoch) {
            renderer.modelReady
        } else {
            val ok = runCatching { renderer.loadModel(model.mocPath) }.getOrDefault(false)
            if (ok) loadedModelEpoch = modelEpoch
            ok
        }

        isError = !ready
        if (!ready) {
            status = renderer.lastError ?: "模型加载失败"
            surfaceViewRef[0]?.requestRender()
            return@LaunchedEffect
        }

        // 参数下标只解析一次：模型里不存在的名字会被标成 -1 并在推流时跳过
        renderer.bindParameters(Live2DParamMap.TARGETS)
        // 调试开关：合成面捕（无摄像头时复现物理抽风）。默认关闭，仅由 prefs 打开，
        // 发布路径不受影响；写 prefs 后重新进入本页（或换模型）即生效。
        renderer.setSyntheticFace(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYNTHETIC_FACE, false)
        )
        // 调试开关：A/B 兜底互斥（缺 pose3 的模型只保留一套手臂）。默认开启，
        // 仅在 prefs 里显式写 false 时关闭，用于真机开/关 A/B 对比。
        renderer.setPoseFallback(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_POSE_FALLBACK, true)
        )
        // 调试开关：物理逐帧曲线追踪（与CubismPhysics 参考实现对照）
        renderer.setPhysicsTrace(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_PHYSICS_TRACE, false)
        )
        // 调试开关：物理参考模式（旁路 P8 抑制项，与标准语义对齐）
        renderer.setPhysicsReferenceMode(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_PHYSICS_REFMODE, false)
        )
        surfaceViewRef[0]?.requestRender()

        // ---- P4：读取动作与表情清单 ----
        val (groups, exps) = renderer.listActions()
        motionGroups.clear()
        motionGroups.addAll(groups)
        expressions.clear()
        expressions.addAll(exps)
        // native 侧默认播第一组动作的第 0 个，这里把 UI 选中态对齐
        activeMotionGroup = if (groups.isNotEmpty()) groups.first().index else -1
        activeExpression = -1
        status = "Purism Core 就绪 · ${model.displayName}"

        if (faceRenderer == null) {
            status = "Purism Core 就绪（无面捕源）"
            return@LaunchedEffect
        }
        status = "Purism Core 就绪"
        val indices = renderer.parameterIndexMap()
        // 只保留模型真正拥有的参数：-1 的项直接不进数组，C++ 侧无需逐帧丢
        val bound = Live2DParamMap.TARGETS.mapNotNull { name ->
            val index = indices[name] ?: -1
            if (index >= 0) name to index else null
        }
        if (bound.isEmpty()) {
            InAppLog.w(TAG, "模型没有可驱动的参数，面捕驱动停用")
            return@LaunchedEffect
        }
        InAppLog.i(TAG, "面捕驱动启动: ${bound.size}/${Live2DParamMap.TARGETS.size} 参数 @ ${PARAM_PUSH_HZ}Hz")

        // 每帧写入的参数对数固定 = bound.size，数组长度恒为 bound.size*2：
        // 长度固定才能直接复用同一个 FloatArray（JNI 侧按偶数对解析，无需拷贝）。
        val out = FloatArray(bound.size * 2)
        var faceLost = false
        var writeReported = false
        while (isActive) {
            val snapshot = faceRenderer.latestFaceSnapshot
            // 人脸从有到无 / 从无到有各打一次日志，便于装机时确认链路
            if ((snapshot == null) != faceLost) {
                faceLost = snapshot == null
                InAppLog.i(TAG, if (faceLost) "人脸丢失，交给动作/表情驱动" else "人脸恢复，面捕驱动继续")
            }

            var i = 0
            for ((name, index) in bound) {
                // 人脸丢失时推 NaN 而不是中性值：C++ 侧把 NaN 当作「本帧不覆盖」，
                // 参数于是由动作与表情决定。早期版本推中性值会把表情的
                // ParamEyeLSmile 等按 0 覆盖掉，表现就是「点了表情没反应」。
                val v = if (snapshot != null) {
                    // 快照缺这条源参数时用中性值：保持数组长度固定，语义也可预测
                    Live2DParamMap.valueFor(name, snapshot) ?: Live2DParamMap.neutralOf(name)
                } else {
                    Float.NaN
                }
                out[i] = index.toFloat()
                out[i + 1] = v
                i += 2
            }

            // 连续渲染模式下 GL 线程会自己取最新顶点，无需 requestRender。
            // 切到 Default：csmUpdateModel 是整模型求值，30Hz 放主线程会挤占 UI 帧。
            val written = withContext(Dispatchers.Default) { renderer.updateParams(out) }
            // 首次成功写入打一条：即使没脸（推的是中性值）也证明 JNI 写通，
            // 这样「模型不动」能被明确区分成「没脸」还是「native 没收到参数」。
            if (!writeReported && written > 0) {
                writeReported = true
                InAppLog.i(TAG, "参数写入成功: $written 个/帧")
            }
            delay(PARAM_PUSH_INTERVAL_MS)
        }
    }

    // ---- P14：动态分辨率（LOD）----
    //
    // 用户建议的「全身低精度、放大高精度」：模型未放大时用 0.75× 的 EGL 缓冲，
    // 放大（zoom > 1.15）时恢复全分辨率。
    //
    // 为什么用 SurfaceHolder.setFixedSize 而不是 FBO：
    //  - setFixedSize 直接缩小 **EGL Surface 的像素缓冲**，片元着色与显存带宽
    //    按 0.75² = 56% 计，是最省的一档；
    //  - 放大合成交给系统合成器（Hardware Composer / GPU 双线性），不占我们的
    //    draw call，也不需要额外的 FBO + 全屏 quad + shader（GLES2 下还要自己
    //    管理 FBO 的模板缓冲 —— 我们的遮罩依赖 stencil，多一套 FBO 就多一份风险）；
    //  - 缩小时 onSurfaceChanged 会带新的宽高回来，视口与投影自动按新尺寸重算，
    //    宽高比不变 → 构图完全不动。
    //
    // 阈值 1.15 本身带迟滞：1x 附近的小幅手势（捏合抖动）不会把分辨率
    // 在 0.75× 与 1.0× 之间反复切换 —— 每次切换都要重建 EGL Surface
    // （surfaceCreated → 纹理重传），代价远大于那一点填充率的收益。
    //
    // 关键顺序（为什么必须走 [LodSurfaceView] 而不是在这里直接调 setFixedSize）：
    // GLSurfaceView 的 surfaceCreated 内部会先执行 `holder.setSizeFromLayout()`，
    // 把缓冲尺寸重置回 View 大小。任何在 surface 创建**之前**（或之后不重设）调用
    // setFixedSize 的写法都会被它覆盖，实测首帧仍是全分辨率。
    LaunchedEffect(zoom) {
        val lod = surfaceViewRef[0] as? LodSurfaceView ?: return@LaunchedEffect
        // P15 诊断覆盖：仅当 /data/local/tmp/arvt_lod_scale 存在时生效
        val scale = lodScaleOverride()
            ?: if (zoom > LOD_FULL_ZOOM) 1.0f else LOD_DEFAULT_SCALE
        if (lod.lodScale != scale) {
            InAppLog.i(TAG, "LOD: zoom=$zoom → 缓冲倍率 $scale")
            lod.lodScale = scale
        }
    }

    // 组合离开：先停 GL 线程再释放 native 资源    //
    // P14：旧实现只调 renderer.release()，依赖 GLSurfaceView 在 detach 时自己
    // 收线程。问题在于 detach 与本 onDispose 的先后不确定：切 Tab 时 GL 线程
    // 可能还在 nativeDrawFrame 里跑（RENDERMODE_CONTINUOUSLY），而 nativeRelease
    // 已经把 CPU 侧模型/顶点清空 —— 这一帧要么白跑，要么在锁上等待，表现为
    // 「切 Tab 的瞬间卡一下」。显式 onPause 让渲染线程在释放前就停下。
    DisposableEffect(renderer) {
        onDispose {
            // P15 诊断：切 Tab 时序对照。这里打时间戳，与 AndroidView.onRelease
            // 及 nativeRelease 的日志连起来看「停线程是否真的先于清模型」。
            val t = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0
            InAppLog.i(TAG, "teardown: onPause begin t=%.1fms".format(t))
            // P15 A/B 对照开关：/data/local/tmp/arvt_teardown_legacy 存在时
            // 跳过 onPause，复现 P14 之前「只 release、靠 detach 收线程」的行为，
            // 用于测同一版本下的切 Tab 卡顿对照。默认（文件不存在）走新路径。
            val legacy = java.io.File("/data/local/tmp/arvt_teardown_legacy").exists()
            if (!legacy) surfaceViewRef[0]?.onPause()
            InAppLog.i(
                TAG,
                "teardown: onPause done t=%.1fms legacy=%b".format(
                    android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0, legacy
                )
            )
            renderer.release()
            InAppLog.i(
                TAG,
                "teardown: release done t=%.1fms".format(
                    android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B10)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                LodSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    // ★ P2 Clipping Mask 的前提：Surface 必须带模板缓冲。
                    // GLSurfaceView 默认配置（setEGLConfigChooser(true)）只有
                    // RGB_888 + depth16，**没有 stencil**；不显式指定的话
                    // glGetIntegerv(GL_STENCIL_BITS) 返回 0，所有 stencil 调用都是空操作，
                    // 头发顶部那块「平切」就还在（mask 内容按完整几何直接画）。
                    // 参数顺序：red, green, blue, alpha, depth, stencil
                    setEGLConfigChooser(8, 8, 8, 8, 16, 8)
                    // 必须先 setRenderer 再设 renderMode（GLSurfaceView 的约束）
                    setRenderer(renderer)
                    // 连续渲染：动作时间轴每帧推进，静止画面靠 requestRender 不可靠
                    // （参数更新发生在非 GL 线程，拿不到「何时该画」的信号）。
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    surfaceViewRef[0] = this
                }
            },
            update = { /* 空实现：绝不在此重复 setRenderer（二次调用抛异常） */ },
            onRelease = { view ->
                // P14：切 Tab / 离开页面时 View 从这里被回收。旧实现只清引用，
                // 依赖 GLSurfaceView 在 detach 时才停线程；而 nativeRelease 可能
                // 已经在此之前把模型清空，GL 线程还在 nativeDrawFrame 里跑 →
                // 切 Tab 瞬间卡一下（甚至拿到半清空的几何）。先显式停线程。
                InAppLog.i(
                    TAG,
                    "teardown: onRelease begin t=%.1fms".format(
                        android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0
                    )
                )
                view.onPause()
                surfaceViewRef[0] = null
                // 不在这里 release native：Renderer 仍可能被 GLSurfaceView 引用，
                // 统一由上方 DisposableEffect 在组合真正离开时处理
            },
        )

        // ---- 空状态：应用不内置任何模型，首次进入必须引导用户导入 ----
        // 只盖在 GL 表面之上、按钮层之下：右侧「切换模型」按钮仍然可点。
        if (currentModel == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.live2d_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Text(
                    text = stringResource(R.string.live2d_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ---- P5：手势层（覆盖 GLSurfaceView，位于按钮条之下）----
        // 为什么单独铺一层而不是直接给 AndroidView 加 modifier：
        // GLSurfaceView 是原生 View，它自己的 dispatchTouchEvent 会先吃掉事件，
        // Compose 侧挂在 AndroidView modifier 上的 pointerInput 收不全序列
        // （多指缩放尤其明显）。铺一层透明 Box 后事件走纯 Compose 分发，稳定。
        // 唤醒：不进 pointerInput，直接挂 onTouchEvent 之前的 Compose 监听会与
        // detectTransformGestures 抢消费状态；这里用同一 pointerInput 的初始按下
        // （awaitFirstDown 不消费事件）通知宿主，手势序列仍完整下发给 transform。
        // 回调经 rememberUpdatedState 取最新值：pointerInput(Unit) 不随重组重启。
        val currentOnUserActivity by rememberUpdatedState(onUserActivity)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        currentOnUserActivity()
                    }
                }
                .pointerInput(renderer) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        // 拖动/缩放期间持续续期空闲计时：Activity.onUserInteraction
                        // 只在 ACTION_DOWN 触发，长时间拖拽会被 2s 计时打断。
                        currentOnUserActivity()
                        // 像素位移 → 归一化偏移（±1 = 屏幕半宽/半高）。
                        // 屏幕 Y 向下为正，NDC 向上为正，故 panY 取负。
                        val halfW = size.width.coerceAtLeast(1) * 0.5f
                        val halfH = size.height.coerceAtLeast(1) * 0.5f

                        // 先按缩放后的包围盒取可行区间，再钳制累加值。
                        // 顺序很重要：边界依赖新 scale，必须先算 scale 再算 pan。
                        val nextZoom = (zoom * zoomChange).coerceIn(SCALE_MIN, SCALE_MAX)
                        val limits = renderer.panLimits(nextZoom)

                        panX = (panX + pan.x / halfW).coerceIn(limits[0], limits[1])
                        panY = (panY - pan.y / halfH).coerceIn(limits[2], limits[3])
                        zoom = nextZoom
                        renderer.setTransform(panX, panY, zoom)
                    }
                },
        )

        // ---- P6：重置视图 + 模型管理（右侧竖排，垂直居中）----
        // 沉浸模式下随其余控件一起淡出：唤醒后重新出现，位置不变。
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(chromePadding),
        ) {
            Column(
                modifier = Modifier.padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        panX = 0f
                        panY = 0f
                        zoom = 1f
                        renderer.resetTransform()
                        InAppLog.i(TAG, "重置视图: 1x 居中")
                    },
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White.copy(alpha = 0.9f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "重置视图",
                    )
                }

                // 模型管理：列表 / 导入 / 删除都在弹窗里
                SmallFloatingActionButton(
                    onClick = { modelDialogVisible = true },
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White.copy(alpha = 0.9f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "切换模型",
                    )
                }
            }
        }

        // ---- 任务 3：FPS HUD（左上角）----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(chromePadding),
        ) {
            Surface(
                modifier = Modifier.padding(12.dp),
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = if (fps > 0f) "%.0f fps".format(fps) else "-- fps",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        // ---- 任务 4：动作 / 表情按钮（底部）----
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(chromePadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (motionGroups.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (group in motionGroups) {
                            FilterChip(
                                selected = activeMotionGroup == group.index,
                                onClick = {
                                    activeMotionGroup = group.index
                                    val played = renderer.playMotion(group.index, -1)
                                    InAppLog.i(TAG, "点击动作 ${group.name} → 实际播放 $played")
                                },
                                label = { Text(group.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    labelColor = Color.White.copy(alpha = 0.85f),
                                ),
                            )
                        }
                    }
                }
                if (expressions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 「无」按钮取消表情：native 侧 -1 = 清空
                        AssistChip(
                            onClick = {
                                activeExpression = -1
                                renderer.setExpression(-1)
                            },
                            label = { Text("无表情") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color.Black.copy(alpha = 0.45f),
                                labelColor = Color.White.copy(alpha = 0.85f),
                            ),
                        )
                        for (exp in expressions) {
                            FilterChip(
                                selected = activeExpression == exp.index,
                                onClick = {
                                    activeExpression = exp.index
                                    renderer.setExpression(exp.index)
                                },
                                label = { Text(exp.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    labelColor = Color.White.copy(alpha = 0.85f),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // 状态占位：右上角小字（左上角留给 FPS，底部留给动作/表情按钮条）
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(chromePadding),
        ) {
            Surface(
                modifier = Modifier.padding(12.dp),
                color = if (isError) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                } else {
                    Color.Black.copy(alpha = 0.45f)
                },
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        Color.White.copy(alpha = 0.8f)
                    },
                )
            }
        }
    }

    // ---- P6：zip 选择器（GetContent 拿 content Uri，导入交给 WorkManager）----
    // 关键：这里**不**起解压协程。切后台/锁屏时 Compose 组合会被销毁，界面协程随之取消，
    // 解压就会半途而废；WorkManager 把任务交给系统调度器，界面消失也不影响它跑完。
    //
    // GetContent 走 SAF，不需要任何存储权限。但它的读授权只在当前 Activity 存活期内有效，
    // 而 Worker 可能在那之后才执行——所以 enqueue 会先把内容暂存到 cacheDir，
    // Worker 读暂存文件，与 Uri 授权生命周期解耦（详见 Live2DImportScheduler 注释）。
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importInProgress = true
        importMessage = context.getString(R.string.live2d_import_background_hint)
        scope.launch {
            // 暂存最多 256 MB，必须离开主线程；这一步仍然绑在界面生命周期上，
            // 但它只是「拷贝文件」，耗时远小于解压。真正的解压由 WorkManager 接手。
            val workId = runCatching { Live2DImportScheduler.enqueue(context, uri) }
                .getOrElse { e ->
                    importInProgress = false
                    importWorkId = null
                    importMessage = e.message ?: "无法读取所选文件"
                    InAppLog.e(TAG, "导入入队失败", e)
                    return@launch
                }
            importWorkId = workId
            InAppLog.i(TAG, "导入已入队 WorkManager: $workId")
        }
    }

    if (modelDialogVisible) {
        ModelManagerDialog(
            models = availableModels,
            currentModelId = currentModel?.id,
            importInProgress = importInProgress,
            message = importMessage,
            onDismiss = {
                modelDialogVisible = false
                importMessage = null
            },
            onPickModel = { model ->
                if (model.id != currentModel?.id) {
                    currentModel = model
                    modelEpoch++
                }
                modelDialogVisible = false
                importMessage = null
            },
            onImport = { zipPicker.launch("application/zip") },
            onDelete = { model -> pendingDelete = model },
        )
    }

    // 删除确认：与导入分开，避免误触把模型删掉
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型") },
            text = { Text("确定删除「${target.displayName}」？此操作不可恢复") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            Live2DModelStore.deleteModel(context, target.id)
                        }
                        importMessage = if (ok) "已删除：${target.displayName}" else "删除失败"
                        // 删掉的正是当前模型：清空回空状态，刷新后自动接管剩余模型
                        if (ok && target.id == currentModel?.id) {
                            currentModel = null
                            motionGroups.clear()
                            expressions.clear()
                            activeMotionGroup = -1
                            activeExpression = -1
                            modelEpoch++
                        }
                        refreshModels()
                    }
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 前后台跟随：后台必须停 GL 线程（否则持续跑空帧耗电）
    DisposableEffect(lifecycleOwner, renderer) {
        // addObserver 会立刻按当前状态回调一次 onResume。若不加这个标记，
        // 首次组合完成时就会白白重启一次推流协程（日志里「面捕驱动启动」出现两遍）。
        // 只在「真的从后台回来」时才让协程重来。
        var paused = false
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                surfaceViewRef[0]?.let { view ->
                    view.onResume()
                    view.requestRender()
                }
                if (paused) {
                    paused = false
                    // 回前台：让推流协程重新启动（见 resumeEpoch 注释）。
                    resumeEpoch++
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                paused = true
                surfaceViewRef[0]?.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val TAG = "Live2D"

/**
 * 模型管理弹窗：切换 / 导入 / 删除。
 *
 * 为什么用 AlertDialog 而不是 BottomSheet：页面已经叠了手势层与底部动作条，
 * BottomSheet 会和底部 chips 抢触摸区域；弹窗是独立窗口，层叠关系最简单。
 *
 * 列表里每一项都是用户自己导入的模型，因此删除按钮一律显示。
 */
@Composable
private fun ModelManagerDialog(
    models: List<Live2DModelStore.Model>,
    currentModelId: String?,
    importInProgress: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onPickModel: (Live2DModelStore.Model) -> Unit,
    onImport: () -> Unit,
    onDelete: (Live2DModelStore.Model) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Live2D 模型") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (importInProgress) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在解压…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (models.isEmpty() && !importInProgress) {
                    Text(
                        text = "还没有模型。点下方「导入 zip」选择模型包",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (model in models) {
                    val selected = model.id == currentModelId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !selected) { onPickModel(model) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onPickModel(model) })
                        Text(
                            text = model.displayName,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { onDelete(model) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除 ${model.displayName}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = !importInProgress) {
                Text("导入 zip")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 参数推流频率。30Hz 与 FaceRenderer 的发送上限一致，高于人眼可辨的跟随延迟。 */
private const val PARAM_PUSH_HZ = 30

/** 推流周期（毫秒）。用常量而非 1000/PARAM_PUSH_HZ，避免整除误差带来的漂移。 */
private const val PARAM_PUSH_INTERVAL_MS = 1000L / PARAM_PUSH_HZ

/** FPS HUD 轮询周期：比 native 的 0.5s 统计窗口略快，读数看起来是连续的。 */
private const val FPS_POLL_INTERVAL_MS = 400L

/**
 * 调试用 prefs（与 MainActivity 的 app_settings 分开）。
 *
 * 为什么单独一个文件：合成面捕是**真机复现工具**，不属于用户设置，也不该
 * 出现在设置页的开关里。用独立 prefs 文件，调试时 `adb shell run-as ... cat >
 * shared_prefs/debug_repro.xml` 即可打开，无需改代码重新打包。
 */
internal const val PREFS_DEBUG = "debug_repro"

/** 合成面捕开关（true = native 每帧注入 1Hz 正弦头动 + 0.5Hz 张嘴）。 */
internal const val KEY_SYNTHETIC_FACE = "synthetic_face"

/**
 * A/B 部件兜底互斥开关（缺省 true）。
 *
 * 缺 pose3.json 的模型会把两套互斥手臂都导出成默认可显示，native 侧按
 * 「同名 + A/B 后缀」推断互斥组、只保留一套。设为 false 可恢复原始行为，
 * 用于同一段录屏做开/关 A/B 对比（见 local_debug/repro/FINDINGS.md §2.1）。
 */
internal const val KEY_POSE_FALLBACK = "pose_fallback_ab"

/**
 * 物理逐帧曲线追踪开关（缺省 false）。
 *
 * 打开后 native 每帧打一行 `trace:`（时间 / dt / ParamHairFront），供与
 * CubismPhysics 参考实现逐帧比对。只用于取证，默认关闭。
 */
internal const val KEY_PHYSICS_TRACE = "physics_trace"

/**
 * 物理参考模式开关（缺省 false）。
 *
 * 打开后旁路 P8 的抑制项，把自研求解器切回标准语义，用于判定
 * 「与标准参考实现的偏差来自抑制项还是积分数学」。仅取证用。
 */
internal const val KEY_PHYSICS_REFMODE = "physics_reference_mode"

/**
 * 缩放边界（与 C++ 侧 kScaleMin / kScaleMax 保持一致）。
 *
 * 真正的钳制在 native 侧（单一事实来源）；这里的常量只用于让 Compose 状态
 * 与画面同步，避免「UI 以为还能再放大、画面已经不动」的割裂感。
 *
 * 平移**没有**这里的常量：边界依赖模型包围盒与当前缩放，只能由 native 的
 * `nativePanLimits` 按实时尺寸算出来（见 [Live2DRenderer.panLimits]）。
 */
private const val SCALE_MIN = 0.5f
private const val SCALE_MAX = 3.0f

/**
 * LOD：未放大时的 EGL Surface 分辨率倍率（P14）。
 *
 * 0.75 → 片元与填充带宽降到 56%，视觉上只有极轻微的柔化（模型本身是 4K 贴图
 * 缩到 1080p 显示，本来就有一次降采样）。取 0.75 而不是更低：0.5 时边缘锯齿
 * 在深色背景上已经明显。
 */
private const val LOD_DEFAULT_SCALE = 0.75f

/**
 * P15 诊断：GPU 瓶颈对照用的缓冲倍率覆盖。
 *
 * 文件 `/data/local/tmp/arvt_lod_scale` 内容是一个浮点倍率（如 `1.0` / `1.5`）。
 * 存在时忽略 [LOD_DEFAULT_SCALE]，把渲染分辨率拉到足够高，让填充带宽成为瓶颈，
 * 从而量化 scissor 模板清除的真实收益（90fps vsync 上限下，非瓶颈改动的帧时间
 * 看不出差别）。仅在诊断时手动 `adb shell su -c 'echo 1.5 > ...'` 打开。
 */
private fun lodScaleOverride(): Float? = runCatching {
    val f = java.io.File("/data/local/tmp/arvt_lod_scale")
    if (!f.exists()) return@runCatching null
    f.readText().trim().toFloatOrNull()?.takeIf { it in 0.25f..3.0f }
}.getOrNull()

/** 超过这个缩放才切换到全分辨率（用户「放大看细节」的意图阈值）。 */
private const val LOD_FULL_ZOOM = 1.15f

/**
 * 带动态分辨率的 GLSurfaceView（P14）。
 *
 * 为什么必须是子类：[GLSurfaceView.surfaceCreated] 在通知 Renderer 之前会执行
 * `holder.setSizeFromLayout()`，把像素缓冲重置为 View 尺寸。外部（组合层）
 * 无论在哪一帧调用 setFixedSize，只要 surface 被重建（切后台回来、LOD 切换、
 * 旋转）就会被这条重置覆盖。这里在 super 调用**之后**立刻按 [lodScale] 重设，
 * 时机是唯一可靠的。
 *
 * setFixedSize 不会立即生效：它通过 SurfaceHolder 回调触发一次真实的
 * onSurfaceChanged，视口与投影随之按新尺寸重算（宽高比不变，构图不动）。
 */
private class LodSurfaceView(context: Context) : GLSurfaceView(context) {

    /** 像素缓冲倍率：1.0 = 全分辨率，<1 = 低精度（放大合成交给系统合成器）。 */
    @Volatile
    var lodScale: Float = LOD_DEFAULT_SCALE
        set(value) {
            if (field == value) return
            field = value
            applyLodSize()
        }

    /**
     * 上一次真正下发给 SurfaceHolder 的尺寸（-1 = 未下发）。
     *
     * 为什么需要它而不是查 holder.surfaceFrame：setFixedSize 之后的
     * surfaceFrame 不是同步更新的，用它做幂等判据会在回调里读回旧值而反复
     * 重建 Surface；而 surfaceChanged 又是 setFixedSize 的回调入口，两者叠加
     * 会变成「重建 — 回调 — 再重建」的死循环。
     */
    private var appliedW = -1
    private var appliedH = -1

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // super 内部会把缓冲尺寸重置成 View 大小 —— 这一步之后重设才生效
        appliedW = -1
        appliedH = -1
        applyLodSize()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        // 布局层重置尺寸（旋转 / 分屏）后，把 LOD 目标重新下发一次。
        // appliedW/H 保证「尺寸已经是我们想要的」时不会再次重建。
        if (lodScale != 1.0f && (width != appliedW || height != appliedH)) {
            applyLodSize()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化后按新 View 尺寸重算（用 onSizeChanged 而不是 onMeasure：
        // setSizeFromLayout() 会 requestLayout，放在 onMeasure 里会互相触发）
        appliedW = -1
        appliedH = -1
        applyLodSize()
    }

    private fun applyLodSize() {
        if (width <= 0 || height <= 0) return
        val scale = lodScale
        // 严格等于 1.0 才是「跟随布局」；>1 的高倍率（诊断用 GPU 瓶颈）同样走
        // setFixedSize —— 否则大缓冲设不上，对照实验就没法做。
        if (scale == 1.0f) {
            if (appliedW == width && appliedH == height) return   // 已是全分辨率
            appliedW = width
            appliedH = height
            holder.setSizeFromLayout()
            return
        }
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (appliedW == w && appliedH == h) return                // 已达标
        appliedW = w
        appliedH = h
        holder.setFixedSize(w, h)
    }
}
