package com.exarry.arvtubelink.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.ui.live2d.Live2DParamMap
import com.exarry.arvtubelink.ui.live2d.Live2DRenderer
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Live2D 渲染页（Purism Core 原生渲染 + 面捕参数驱动）。
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
 *  - 读不到新快照时重复推同一份值是无害的（csmUpdateModel 幂等），
 *    而回调链一旦断链模型会冻住，排查成本高；
 *  - 协程挂在本页组合生命周期上，切走 Tab 自动取消，不会后台空转。
 *
 * ## 生命周期
 * - `RENDERMODE_CONTINUOUSLY`：参数每帧都可能变，必须连续渲染才能跟上。
 *   GL 线程空闲时只是清屏+一次 draw，代价远低于「改参数后忘了 requestRender
 *   导致画面卡住」的排查成本。
 * - 组合离开 → [Live2DRenderer.release] 释放 native 内存（模型缓冲区约 1.5MB）。
 * - 前后台：跟随 [LocalLifecycleOwner]，onPause 停渲染线程、onResume 恢复。
 *
 * ## UI
 * 只保留最小占位：右下角一行状态文字（加载中 / 就绪 / 失败原因）。
 * 不加模型切换、动作、表情入口——本阶段明确不做。
 *
 * @param modifier 布局修饰符（由 AppRoot 传入，占满内容区）
 * @param mocAssetPath assets 内的 .moc3 相对路径，默认内置 Sample
 * @param faceRenderer 面捕渲染器（参数源）；null = 只渲染静态姿态
 */
@Composable
fun Live2DContent(
    modifier: Modifier = Modifier,
    mocAssetPath: String = Live2DRenderer.DEFAULT_MOC_PATH,
    faceRenderer: FaceRenderer? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Renderer 跨重组保持：内部持有 native 模型状态，重新 new 会丢掉已加载的模型。
    // 同时保存 GLSurfaceView 引用，供前后台控制与主动重绘使用。
    val renderer = remember(mocAssetPath) { Live2DRenderer(context.assets, mocAssetPath) }
    val surfaceViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

    var status by remember { mutableStateOf("正在加载模型…") }
    var isError by remember { mutableStateOf(false) }

    // 加载 + 绑定 + 推流放在同一个 effect 里串行完成。
    // 拆成两个 effect 时，绑定后改写 key 会让推流循环取消再重启一次
    // （实测日志里「面捕驱动启动」出现两遍），单 effect 天然只启动一次。
    LaunchedEffect(renderer, faceRenderer) {
        val ready = runCatching { renderer.init() }.getOrDefault(false)
        isError = !ready
        if (!ready) {
            status = renderer.lastError ?: "模型加载失败"
            surfaceViewRef[0]?.requestRender()
            return@LaunchedEffect
        }

        // 参数下标只解析一次：模型里不存在的名字会被标成 -1 并在推流时跳过
        renderer.bindParameters(Live2DParamMap.TARGETS)
        surfaceViewRef[0]?.requestRender()

        if (faceRenderer == null) {
            status = "Purism Core 就绪（无面捕源）"
            return@LaunchedEffect
        }
        status = "Purism Core 就绪 · 面捕驱动中"

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
                InAppLog.i(TAG, if (faceLost) "人脸丢失，模型回中性姿态" else "人脸恢复，面捕驱动继续")
            }

            var i = 0
            for ((name, index) in bound) {
                val v = if (snapshot != null) {
                    // 快照缺这条源参数时用中性值：保持数组长度固定，语义也可预测
                    Live2DParamMap.valueFor(name, snapshot) ?: Live2DParamMap.neutralOf(name)
                } else {
                    // 人脸丢失：回中性姿态，而不是冻在最后一帧
                    Live2DParamMap.neutralOf(name)
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

    // 组合离开：释放 native 资源（GL 线程由 GLSurfaceView 保证）
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B10)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
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
                    // 连续渲染：参数每帧都可能变，静止画面靠 requestRender 不可靠
                    // （参数更新发生在非 GL 线程，拿不到「何时该画」的信号）。
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    surfaceViewRef[0] = this
                }
            },
            update = { /* 空实现：绝不在此重复 setRenderer（二次调用抛异常） */ },
            onRelease = {
                surfaceViewRef[0] = null
                // 不在这里 release native：Renderer 仍可能被 GLSurfaceView 引用，
                // 统一由上方 DisposableEffect 在组合真正离开时处理
            },
        )

        // 状态占位：右下角小字，不遮挡人形。P0 的唯一 UI。
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
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

    // 前后台跟随：后台必须停 GL 线程（否则持续跑空帧耗电）
    DisposableEffect(lifecycleOwner, renderer) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                surfaceViewRef[0]?.let { view ->
                    view.onResume()
                    view.requestRender()
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                surfaceViewRef[0]?.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val TAG = "Live2D"

/** 参数推流频率。30Hz 与 FaceRenderer 的发送上限一致，高于人眼可辨的跟随延迟。 */
private const val PARAM_PUSH_HZ = 30

/** 推流周期（毫秒）。用常量而非 1000/PARAM_PUSH_HZ，避免整除误差带来的漂移。 */
private const val PARAM_PUSH_INTERVAL_MS = 1000L / PARAM_PUSH_HZ
