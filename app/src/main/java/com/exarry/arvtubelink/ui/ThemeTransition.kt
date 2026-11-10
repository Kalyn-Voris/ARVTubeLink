package com.exarry.arvtubelink.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val RevealDurationMs = 350

/** 主题切换过渡：旧主题截图盖在新主题上，圆外裁剪从点击位置向外扩散 */
@Composable
fun ThemeTransition(
    isDark: Boolean,
    screenshot: ImageBitmap?,
    revealOrigin: Offset?,
    transitionSignal: Int,
    onTransitionFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content(isDark)
        val shot = screenshot
        val origin = revealOrigin
        if (shot != null && origin != null) {
            TransitionOverlay(
                screenshot = shot,
                origin = origin,
                transitionSignal = transitionSignal,
                onTransitionFinished = onTransitionFinished,
            )
        }
    }
}

@Composable
private fun TransitionOverlay(
    screenshot: ImageBitmap,
    origin: Offset,
    transitionSignal: Int,
    onTransitionFinished: () -> Unit,
) {
    // 先取局部变量：onDispose 读外层参数会拿到重组后的新图
    val current = screenshot
    DisposableEffect(current) {
        onDispose {
            val bmp = current.asAndroidBitmap()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    // 组合阶段重建为 0f：首帧 draw 必是满屏旧图，LaunchedEffect 初始化会晚于首帧
    val progressState = remember(transitionSignal) { mutableStateOf(0f) }

    LaunchedEffect(transitionSignal) {
        if (transitionSignal == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = RevealDurationMs),
        ) { value, _ -> progressState.value = value }
        onTransitionFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 动画期吞掉全部指针事件，圆外露出的新主题 UI 也不可点
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            // EvenOdd：全屏矩形减圆 = 圆外区域；clipPath 须用 DrawScope 版，Modifier.clipPath 已移除
            .drawBehind {
                val radius = maxRevealRadius(origin, size) * progressState.value
                val outsideCircle = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(left = 0f, top = 0f, right = size.width, bottom = size.height))
                    addOval(Rect(center = origin, radius = radius))
                }
                clipPath(outsideCircle) {
                    drawImage(
                        image = screenshot,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                }
            },
    )
}

/** 圆心到最远角的距离：progress=1f 时圆覆盖全屏，EvenOdd 路径为空 */
private fun maxRevealRadius(origin: Offset, size: Size): Float = hypot(
    maxOf(origin.x, size.width - origin.x),
    maxOf(origin.y, size.height - origin.y),
)
