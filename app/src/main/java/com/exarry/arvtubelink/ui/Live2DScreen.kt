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
import com.exarry.arvtubelink.ui.live2d.Live2DRenderer

/**
 * Live2D 渲染页（P0：Purism Core 原生静态渲染）。
 *
 * ## 目的
 * 这是「用原生 C++/JNI 替换 WebView 方案」的第一阶段验证：
 * 只证明 **C++ 能把 .moc3 加载出来并画出人形几何面**，不贴图、不驱动、不播动作。
 * 原来的 WebView 方案（pixi-live2d-display）已整体移除，本页是唯一的 Live2D 宿主。
 *
 * ## 渲染链路
 * `GLSurfaceView`（EGL/GL 线程）→ [Live2DRenderer]（Kotlin 桥）→
 * `liblive2d_jni.so`（Purism Core 求值 + GLES2 绘制）。
 * 模型数据全部在 native 侧：Kotlin 只传 [android.content.res.AssetManager] 与
 * `.moc3` 的 assets 相对路径，顶点/索引/投影都在 C++ 里算。
 *
 * ## 为什么用 GLSurfaceView 而不是 Compose Canvas
 * 后续 P1 要接贴图与参数驱动，必须是真 GL 管线；P0 就按最终形态的骨架搭，
 * 避免中途换实现。Compose 只负责外层布局与状态文字。
 *
 * ## 生命周期
 * - `RENDERMODE_WHEN_DIRTY`：P0 是静态画面，画完一帧就没有新内容需要重绘，
 *   连续渲染只会白耗电。surface 建立/改变与显式 requestRender 各触发一次。
 * - 组合离开 → [Live2DRenderer.release] 释放 native 内存（模型缓冲区约 1.5MB）。
 * - 前后台：跟随 [LocalLifecycleOwner]，onPause 停渲染线程、onResume 恢复并重绘。
 *
 * ## UI
 * 只保留最小占位：右下角一行状态文字（加载中 / 就绪 / 失败原因）。
 * 不加任何模型切换、动作、表情入口——P0 明确不做。
 *
 * @param modifier 布局修饰符（由 AppRoot 传入，占满内容区）
 * @param mocAssetPath assets 内的 .moc3 相对路径，默认内置 Sample
 */
@Composable
fun Live2DContent(
    modifier: Modifier = Modifier,
    mocAssetPath: String = Live2DRenderer.DEFAULT_MOC_PATH,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Renderer 跨重组保持：内部持有 native 模型状态，重新 new 会丢掉已加载的模型。
    // 同时保存 GLSurfaceView 引用，供前后台控制与主动重绘使用。
    val renderer = remember(mocAssetPath) { Live2DRenderer(context.assets, mocAssetPath) }
    val surfaceViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

    var status by remember { mutableStateOf("正在加载模型…") }
    var isError by remember { mutableStateOf(false) }

    // 首次组合就把模型加载掉（不等 GL 线程），状态文字能立刻反映结果；
    // GL 侧 onSurfaceCreated 只消费已组装好的 CPU 几何。
    // 首帧若先于本 effect 到达 GL 线程，Renderer.onSurfaceCreated 内部有幂等补救。
    LaunchedEffect(renderer) {
        val ready = runCatching { renderer.init() }.getOrDefault(false)
        isError = !ready
        status = if (ready) "Purism Core 就绪" else (renderer.lastError ?: "模型加载失败")
        // 加载完成（无论成败）都请求一帧：成功=画出人形，失败=清屏+不残留上一帧
        surfaceViewRef[0]?.requestRender()
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
                    // 静态画面：只在 surface 变化/显式请求时重绘，不做连续渲染
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
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
