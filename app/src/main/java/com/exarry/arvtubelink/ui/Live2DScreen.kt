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
import com.exarry.arvtubelink.ui.legal.AgreementConsent
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Live2D 渲染页：GLSurfaceView 连续渲染 + live2d_jni.so，面捕快照打包成 [id, value, ...] 按 PARAM_PUSH_HZ 推给 native */
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

    // 模型全部来自用户导入，null = 空状态
    var currentModel by remember { mutableStateOf<Live2DModelStore.Model?>(null) }
    val availableModels = remember { mutableStateListOf<Live2DModelStore.Model>() }
    // 切换模型自增，作为加载协程 key
    var modelEpoch by remember { mutableIntStateOf(0) }
    // 已成功加载的 epoch，回前台跳过重读 moc3 与纹理
    var loadedModelEpoch by remember { mutableIntStateOf(-1) }

    var modelDialogVisible by remember { mutableStateOf(false) }
    // WorkManager 驱动的导入状态，此处只是界面侧快照
    var importInProgress by remember { mutableStateOf(false) }
    // 观测中的导入任务 id，跨重组保持
    var importWorkId by remember { mutableStateOf<UUID?>(null) }
    var pendingDelete by remember { mutableStateOf<Live2DModelStore.Model?>(null) }
    // 待确认导入声明的 zip，确认后才入队 WorkManager
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Renderer 跨重组保持：内部持有 native 模型状态，重建会丢模型，且 setRenderer 二次调用抛异常
    val renderer = remember { Live2DRenderer() }
    val surfaceViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

    var status by remember { mutableStateOf("尚未加载模型") }
    var isError by remember { mutableStateOf(false) }

    val motionGroups = remember { mutableStateListOf<Live2DRenderer.MotionGroup>() }
    val expressions = remember { mutableStateListOf<Live2DRenderer.ExpressionInfo>() }
    // -1 = 无
    var activeMotionGroup by remember { mutableStateOf(-1) }
    var activeExpression by remember { mutableStateOf(-1) }

    var fps by remember { mutableStateOf(0f) }

    // 与 native 同量纲：屏幕半宽/半高归一化
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    // 作为推流协程 key，每次 onResume 自增重启协程
    var resumeEpoch by remember { mutableIntStateOf(0) }

    // 轮询独立于推流，模型未就绪时也跑（显示 0）
    LaunchedEffect(renderer) {
        while (isActive) {
            val value = runCatching { renderer.fps() }.getOrDefault(0f)
            if (value > 0f) fps = value
            delay(FPS_POLL_INTERVAL_MS)
        }
    }

    // 模型清单：进页面读一次，导入/删除后手动刷新
    val refreshModels: () -> Unit = {
        scope.launch {
            val models = withContext(Dispatchers.IO) { Live2DModelStore.listModels(context) }
            availableModels.clear()
            availableModels.addAll(models)
            // 无当前模型时自动接管第一个
            if (currentModel == null) {
                currentModel = models.firstOrNull()
                if (currentModel != null) modelEpoch++
            }
        }
    }
    LaunchedEffect(Unit) { refreshModels() }

    // Activity 重建后接回仍在跑的导入任务：workId 存在 Compose 状态里已丢失，WorkManager 任务还在
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

    // 观测导入任务直到终态，key = importWorkId
    LaunchedEffect(importWorkId) {
        val workId = importWorkId ?: return@LaunchedEffect
        // 任务记录被 prune 后流正常结束，first{} 抛 NoSuchElementException，忽略即可
        runCatching {
            Live2DImportScheduler.observe(context, workId)
                .onEach { progress ->
                    when (progress) {
                        is Live2DImportProgress.Running -> {
                            importInProgress = true
                        }

                        is Live2DImportProgress.Success -> {
                            importInProgress = false
                            // 磁盘是唯一事实来源，重扫目录
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
                                // 目录有文件但凑不出可用模型（缺 moc3 等），listModels 已清理
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

    // 加载 + 绑定 + 推流同一 effect 串行完成：拆开会在绑定后重启推流循环
    // resumeEpoch 参与 key：onPause 销毁 EGL context 停掉 GL 线程，回前台只恢复 SurfaceView 不会有参数写入
    // modelEpoch 参与 key：切换模型后重新绑定参数、重读动作与表情清单
    LaunchedEffect(renderer, faceRenderer, resumeEpoch, modelEpoch) {
        // 没有模型时不碰 native，空状态由 UI 层呈现
        val model = currentModel
        if (model == null) {
            isError = false
            status = "尚未加载模型"
            surfaceViewRef[0]?.requestRender()
            return@LaunchedEffect
        }

        // 已加载过当前模型（例如只是回前台）：native 侧模型仍在
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

        // 参数下标只解析一次，模型里不存在的名字标 -1 并在推流时跳过
        renderer.bindParameters(Live2DParamMap.TARGETS)
        // 调试开关：合成面捕，仅由 prefs 打开
        renderer.setSyntheticFace(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYNTHETIC_FACE, false)
        )
        // 调试开关：A/B 兜底互斥，缺 pose3 的模型只保留一套手臂
        renderer.setPoseFallback(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_POSE_FALLBACK, true)
        )
        // 调试开关：物理逐帧曲线追踪，与 CubismPhysics 参考实现对照
        renderer.setPhysicsTrace(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_PHYSICS_TRACE, false)
        )
        // 调试开关：物理参考模式，旁路抑制项
        renderer.setPhysicsReferenceMode(
            context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                .getBoolean(KEY_PHYSICS_REFMODE, false)
        )
        surfaceViewRef[0]?.requestRender()

        // 动作与表情清单由 native 解析 model3.json 得到
        val (groups, exps) = renderer.listActions()
        motionGroups.clear()
        motionGroups.addAll(groups)
        expressions.clear()
        expressions.addAll(exps)
        // native 默认播第一组动作的第 0 个，UI 选中态对齐
        activeMotionGroup = if (groups.isNotEmpty()) groups.first().index else -1
        activeExpression = -1
        status = "Purism Core 就绪 · ${model.displayName}"

        if (faceRenderer == null) {
            status = "Purism Core 就绪（无面捕源）"
            return@LaunchedEffect
        }
        status = "Purism Core 就绪"
        val indices = renderer.parameterIndexMap()
        // 只保留模型拥有的参数，-1 不进数组，C++ 侧无需逐帧丢
        val bound = Live2DParamMap.TARGETS.mapNotNull { name ->
            val index = indices[name] ?: -1
            if (index >= 0) name to index else null
        }
        if (bound.isEmpty()) {
            InAppLog.w(TAG, "模型没有可驱动的参数，面捕驱动停用")
            return@LaunchedEffect
        }
        InAppLog.i(TAG, "面捕驱动启动: ${bound.size}/${Live2DParamMap.TARGETS.size} 参数 @ ${PARAM_PUSH_HZ}Hz")

        // 数组长度恒为 bound.size*2，可复用同一个 FloatArray，JNI 侧按偶数对解析
        val out = FloatArray(bound.size * 2)
        var faceLost = false
        var writeReported = false
        while (isActive) {
            val snapshot = faceRenderer.latestFaceSnapshot
            // 人脸有无变化各打一次日志
            if ((snapshot == null) != faceLost) {
                faceLost = snapshot == null
                InAppLog.i(TAG, if (faceLost) "人脸丢失，交给动作/表情驱动" else "人脸恢复，面捕驱动继续")
            }

            var i = 0
            for ((name, index) in bound) {
                // 人脸丢失推 NaN 而非中性值：C++ 把 NaN 视为本帧不覆盖，参数交给动作与表情
                val v = if (snapshot != null) {
                    // 快照缺该源参数时用中性值，保持数组长度固定
                    Live2DParamMap.valueFor(name, snapshot) ?: Live2DParamMap.neutralOf(name)
                } else {
                    Float.NaN
                }
                out[i] = index.toFloat()
                out[i + 1] = v
                i += 2
            }

            // 连续渲染下 GL 线程自取最新顶点，无需 requestRender
            // csmUpdateModel 是整模型求值，30Hz 放主线程会挤占 UI 帧
            val written = withContext(Dispatchers.Default) { renderer.updateParams(out) }
            // 首次成功写入打一条，区分「没脸」与「native 没收到参数」
            if (!writeReported && written > 0) {
                writeReported = true
                InAppLog.i(TAG, "参数写入成功: $written 个/帧")
            }
            delay(PARAM_PUSH_INTERVAL_MS)
        }
    }

    // 动态分辨率：未放大用 0.75× EGL 缓冲，zoom > 1.15 恢复全分辨率
    // 阈值 1.15 留迟滞，避免 1x 附近捏合抖动反复重建 EGL Surface
    LaunchedEffect(zoom) {
        val lod = surfaceViewRef[0] as? LodSurfaceView ?: return@LaunchedEffect
        // 诊断覆盖：仅当 /data/local/tmp/arvt_lod_scale 存在时生效
        val scale = lodScaleOverride()
            ?: if (zoom > LOD_FULL_ZOOM) 1.0f else LOD_DEFAULT_SCALE
        if (lod.lodScale != scale) {
            InAppLog.i(TAG, "LOD: zoom=$zoom → 缓冲倍率 $scale")
            lod.lodScale = scale
        }
    }

    // 组合离开先停 GL 线程再释放 native：切 Tab 时 GL 线程可能仍在 nativeDrawFrame 里跑
    DisposableEffect(renderer) {
        onDispose {
            // /data/local/tmp/arvt_teardown_legacy 存在时跳过 onPause，复现旧行为做对照
            val t = android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0
            InAppLog.i(TAG, "teardown: onPause begin t=%.1fms".format(t))
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
                    // Clipping Mask 需要模板缓冲：默认配置只有 RGB_888 + depth16，无 stencil
                    // 参数顺序：red, green, blue, alpha, depth, stencil
                    setEGLConfigChooser(8, 8, 8, 8, 16, 8)
                    // 必须先 setRenderer 再设 renderMode
                    setRenderer(renderer)
                    // 连续渲染：动作时间轴每帧推进，参数更新在非 GL 线程，无「何时该画」信号
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    surfaceViewRef[0] = this
                }
            },
            update = { /* 空实现：重复 setRenderer 会抛异常 */ },
            onRelease = { view ->
                // 先显式停线程：nativeRelease 可能已清空模型，而 GL 线程仍在 nativeDrawFrame
                InAppLog.i(
                    TAG,
                    "teardown: onRelease begin t=%.1fms".format(
                        android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000.0
                    )
                )
                view.onPause()
                surfaceViewRef[0] = null
                // 不在此 release native：Renderer 仍可能被 GLSurfaceView 引用，统一交给 DisposableEffect
            },
        )

        // 只盖在 GL 表面之上、按钮层之下，右侧切换模型按钮仍可点
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

        // 透明 Box 单独接手势：GLSurfaceView 的 dispatchTouchEvent 会先吃事件，AndroidView modifier 上的 pointerInput 收不全多指序列
        // 唤醒挂同一 pointerInput 的 awaitFirstDown（不消费事件），避免与 detectTransformGestures 抢消费状态
        // 回调经 rememberUpdatedState 取最新值，pointerInput(Unit) 不随重组重启
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
                        // Activity.onUserInteraction 只在 ACTION_DOWN 触发，拖拽期间靠这里续期空闲计时
                        currentOnUserActivity()
                        // 像素位移 → 归一化偏移（±1 = 屏幕半宽/半高）
                        // 屏幕 Y 向下为正，NDC 向上为正，故 panY 取负
                        val halfW = size.width.coerceAtLeast(1) * 0.5f
                        val halfH = size.height.coerceAtLeast(1) * 0.5f

                        // 边界依赖新 scale，必须先算 scale 再算 pan
                        val nextZoom = (zoom * zoomChange).coerceIn(SCALE_MIN, SCALE_MAX)
                        val limits = renderer.panLimits(nextZoom)

                        panX = (panX + pan.x / halfW).coerceIn(limits[0], limits[1])
                        panY = (panY - pan.y / halfH).coerceIn(limits[2], limits[3])
                        zoom = nextZoom
                        renderer.setTransform(panX, panY, zoom)
                    }
                },
        )

        // 重置视图 + 模型管理，右侧竖排垂直居中，沉浸模式下随其余控件淡出
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

                // 列表 / 导入 / 删除都在弹窗里
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

        // FPS HUD 左上角
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

        // 动作 / 表情按钮底部
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
                        // 「无」取消表情：native 侧 -1 = 清空
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

        // 状态小字右上角，左上角留给 FPS，底部留给动作/表情条
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

    // zip 选择用 SAF GetContent，导入交给 WorkManager：Compose 组合销毁不影响解压
    // GetContent 的 Uri 读授权只在 Activity 存活期内有效，enqueue 会先暂存到 cacheDir
    // 确认按钮与待确认状态共用这一条入队路径
    val enqueueImport: (Uri) -> Unit = { uri ->
        importInProgress = true
        importMessage = context.getString(R.string.live2d_import_background_hint)
        scope.launch {
            // 留痕先落盘再入队：hash(zip) + 时间戳 + 协议版本，读 zip 与首次 prefs 都是磁盘 IO
            withContext(Dispatchers.IO) {
                AgreementConsent.recordImportConsent(
                    context = context,
                    zipSha256 = AgreementConsent.sha256OfUri(context, uri),
                    agreementVersion = AgreementConsent.readAgreementVersion(context),
                )
            }
            // 暂存最多 256 MB，须离开主线程
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

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 只记录选择结果，等导入前确认弹窗同意后才入队
        pendingImportUri = uri
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

    // 删除确认，与导入分开
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
                        // 删的是当前模型：清空回空状态，刷新后自动接管剩余模型
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

    // 导入前确认闸：选中 zip 后先确认，同意才把 Uri 交给 WorkManager
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入前确认") },
            text = {
                Text(
                    "用户陈述与保证：您保证所导入内容拥有合法版权。" +
                        "若因导入内容引发纠纷，由您自行承担全部赔偿责任。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // 哈希要读整个 zip，交给 enqueueImport 的 IO 协程
                    pendingImportUri = null
                    enqueueImport(uri)
                }) {
                    Text("同意并导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 前后台跟随：后台停 GL 线程，否则持续跑空帧耗电
    DisposableEffect(lifecycleOwner, renderer) {
        // addObserver 会立刻按当前状态回调一次 onResume，用标记避免首次组合就重启推流协程
        var paused = false
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                surfaceViewRef[0]?.let { view ->
                    view.onResume()
                    view.requestRender()
                }
                if (paused) {
                    paused = false
                    // 回前台重启推流协程，见 resumeEpoch
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

/** 模型管理弹窗：切换 / 导入 / 删除，用独立窗口避免 BottomSheet 与底部 chips 抢触摸 */
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

/** 参数推流频率，30Hz 与 FaceRenderer 发送上限一致 */
private const val PARAM_PUSH_HZ = 30

/** 推流周期，不用 1000/PARAM_PUSH_HZ 以免整除漂移 */
private const val PARAM_PUSH_INTERVAL_MS = 1000L / PARAM_PUSH_HZ

/** FPS HUD 轮询周期，略快于 native 的 0.5s 统计窗口 */
private const val FPS_POLL_INTERVAL_MS = 400L

/** 调试 prefs，与 MainActivity 的 app_settings 分开 */
internal const val PREFS_DEBUG = "debug_repro"

/** 合成面捕开关：native 每帧注入 1Hz 正弦头动 + 0.5Hz 张嘴 */
internal const val KEY_SYNTHETIC_FACE = "synthetic_face"

/** A/B 部件兜底互斥开关，缺省 true；缺 pose3.json 的模型按同名 + A/B 后缀只保留一套 */
internal const val KEY_POSE_FALLBACK = "pose_fallback_ab"

/** 物理逐帧曲线追踪开关，打开后 native 每帧打一行 trace: */
internal const val KEY_PHYSICS_TRACE = "physics_trace"

/** 物理参考模式开关，旁路抑制项切回标准语义 */
internal const val KEY_PHYSICS_REFMODE = "physics_reference_mode"

/** 缩放边界，与 C++ 侧 kScaleMin / kScaleMax 一致；平移边界由 nativePanLimits 按包围盒实时算 */
private const val SCALE_MIN = 0.5f
private const val SCALE_MAX = 3.0f

/** LOD 未放大时的 EGL Surface 倍率，0.75 → 填充带宽约 56% */
private const val LOD_DEFAULT_SCALE = 0.75f

/** 诊断覆盖：/data/local/tmp/arvt_lod_scale 内容为浮点倍率，存在时忽略 LOD_DEFAULT_SCALE */
private fun lodScaleOverride(): Float? = runCatching {
    val f = java.io.File("/data/local/tmp/arvt_lod_scale")
    if (!f.exists()) return@runCatching null
    f.readText().trim().toFloatOrNull()?.takeIf { it in 0.25f..3.0f }
}.getOrNull()

/** 超过此缩放切全分辨率 */
private const val LOD_FULL_ZOOM = 1.15f

/** 带动态分辨率的 GLSurfaceView：surfaceCreated 内 holder.setSizeFromLayout() 会重置缓冲尺寸，须在其后按 lodScale 重设 */
private class LodSurfaceView(context: Context) : GLSurfaceView(context) {

    /** 像素缓冲倍率：1.0 全分辨率，<1 低精度，放大合成交给系统合成器 */
    @Volatile
    var lodScale: Float = LOD_DEFAULT_SCALE
        set(value) {
            if (field == value) return
            field = value
            applyLodSize()
        }

    /** 上次下发的尺寸（-1 = 未下发）：surfaceFrame 不同步更新，用它做幂等判据避免重建死循环 */
    private var appliedW = -1
    private var appliedH = -1

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // super 内部会把缓冲尺寸重置成 View 大小，之后重设才生效
        appliedW = -1
        appliedH = -1
        applyLodSize()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        // 布局层重置尺寸（旋转 / 分屏）后重新下发；appliedW/H 保证尺寸达标时不重建
        if (lodScale != 1.0f && (width != appliedW || height != appliedH)) {
            applyLodSize()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 用 onSizeChanged 而非 onMeasure：setSizeFromLayout() 会 requestLayout，放 onMeasure 里互相触发
        appliedW = -1
        appliedH = -1
        applyLodSize()
    }

    private fun applyLodSize() {
        if (width <= 0 || height <= 0) return
        val scale = lodScale
        // 严格等于 1.0 才跟随布局；>1 的诊断倍率同样走 setFixedSize
        if (scale == 1.0f) {
            if (appliedW == width && appliedH == height) return
            appliedW = width
            appliedH = height
            holder.setSizeFromLayout()
            return
        }
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (appliedW == w && appliedH == h) return
        appliedW = w
        appliedH = h
        holder.setFixedSize(w, h)
    }
}
