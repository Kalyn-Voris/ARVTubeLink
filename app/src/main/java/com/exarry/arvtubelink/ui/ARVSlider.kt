package com.exarry.arvtubelink.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private val TrackHeightIdle = 4.dp

private val TrackHeightActive = 12.dp

private val ThumbSizeIdle = 12.dp

private val ThumbSizeActive = 20.dp

/** 视觉 4dp 轨道，热区靠根 Box 撑到 48dp 获得 */
private val SliderTouchTargetHeight = 48.dp

private const val TrackMorphMs = 150

private const val HaloAlphaIdle = 0.10f

private const val HaloAlphaActive = 0.24f

/** 取放大态 thumb 半径，保证 thumb 圆心不超出轨道可视范围 */
private val TrackInset = ThumbSizeActive / 2

/** 自绘参数滑块；m3 Slider 的 thumb/track 槽拿不到拖拽态 */
@Composable
fun ARVSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant,
    thumbColor: Color = Color.White,
) {
    // 内部监听 DragInteraction.Start / Stop / Cancel
    val dragging by interactionSource.collectIsDraggedAsState()

    // DrawScope 内访问不到 MaterialTheme，颜色在组合期取出
    val trackColor = inactiveColor
    val progressColor = activeColor
    val thumbRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    val dragSource = interactionSource

    // 轨道与光环 tween(150)，thumb 用带回弹的 spring
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) TrackHeightActive else TrackHeightIdle,
        animationSpec = tween(durationMillis = TrackMorphMs, easing = FastOutSlowInEasing),
        label = "arvSliderTrackHeight",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (dragging) ThumbSizeActive else ThumbSizeIdle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "arvSliderThumbSize",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (dragging) HaloAlphaActive else HaloAlphaIdle,
        animationSpec = tween(durationMillis = TrackMorphMs, easing = FastOutSlowInEasing),
        label = "arvSliderHaloAlpha",
    )

    // pointerInput 闭包会捕获陈旧引用，回调与值须包一层
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentValue by rememberUpdatedState(value)

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: 1f
    val segments = if (steps > 0) steps + 1 else 0
    val lower = if (start <= end) start else end
    val upper = if (start <= end) end else start

    // steps>0 按 (end-start)/(steps+1) 网格吸附，steps=0 只 clamp
    fun snap(raw: Float): Float {
        val coerced = raw.coerceIn(lower, upper)
        if (segments <= 0) return coerced
        val stepSize = span / segments
        return (start + ((coerced - start) / stepSize).roundToInt() * stepSize)
            .coerceIn(lower, upper)
    }

    val fraction = ((value - start) / span).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderTouchTargetHeight)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(start, end, steps) {
                    val inset = TrackInset.toPx()

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        fun valueAtX(x: Float): Float {
                            val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                            val f = ((x - inset) / trackWidth).coerceIn(0f, 1f)
                            return snap(start + f * span)
                        }

                        // DOWN 当帧即写入值，thumb 立刻跟手；纵向滚动则回滚
                        val valueBeforeDown = currentValue
                        currentOnValueChange(valueAtX(down.position.x))

                        var isDragging = false
                        var cancelled = false

                        // awaitEachGesture 是 restricted 挂起作用域，用非挂起的 tryEmit
                        val press = PressInteraction.Press(down.position)
                        val dragStart = DragInteraction.Start()
                        dragSource.tryEmit(press)
                        dragSource.tryEmit(dragStart)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break

                                if (!change.pressed) {
                                    // 抬手只消费事件，值在 DOWN 与拖动中已写入
                                    change.consume()
                                    break
                                }

                                // 用相对按下点的累计位移判定 slop，单帧增量在慢拖时永远小于 slop
                                val totalX = change.position.x - down.position.x
                                val totalY = change.position.y - down.position.y

                                if (!isDragging) {
                                    val slop = viewConfiguration.touchSlop
                                    if (abs(totalX) < slop && abs(totalY) < slop) {
                                        continue
                                    }
                                    if (abs(totalY) > abs(totalX)) {
                                        // 纵向占优让给外层 verticalScroll，不消费本事件
                                        cancelled = true
                                        break
                                    }
                                    isDragging = true
                                }

                                currentOnValueChange(valueAtX(change.position.x))
                                change.consume()
                            }
                        } finally {
                            // 正常与取消都在 finally 复位，Cancel/Stop 驱动轨道缩回
                            if (cancelled) {
                                currentOnValueChange(valueBeforeDown)
                                dragSource.tryEmit(DragInteraction.Cancel(dragStart))
                                dragSource.tryEmit(PressInteraction.Cancel(press))
                            } else {
                                dragSource.tryEmit(DragInteraction.Stop(dragStart))
                                dragSource.tryEmit(PressInteraction.Release(press))
                                currentOnFinished?.invoke()
                            }
                        }
                    }
                }
            )
            // 自绘控件须显式补 progressBarRangeInfo / setProgress 供读屏
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = currentValue,
                    range = valueRange,
                    steps = steps.coerceAtLeast(0),
                )
                if (enabled) {
                    setProgress { target ->
                        currentOnValueChange(snap(target))
                        currentOnFinished?.invoke()
                        true
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(SliderTouchTargetHeight)) {
            val tPx = trackHeight.toPx()
            val thumbPx = thumbSize.toPx()
            val insetPx = TrackInset.toPx()
            val trackWidth = (size.width - insetPx * 2f).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val thumbX = insetPx + trackWidth * fraction

            // 胶囊 = 圆角半径取高度一半
            drawRoundRect(
                color = if (enabled) trackColor else trackColor.copy(alpha = 0.5f),
                topLeft = Offset(insetPx, centerY - tPx / 2f),
                size = Size(trackWidth, tPx),
                cornerRadius = CornerRadius(tPx / 2f),
            )

            // 最小宽度取轨道高，value=0 时呈圆头起点
            val progressWidth = (thumbX - insetPx).coerceAtLeast(tPx)
            drawRoundRect(
                color = if (enabled) progressColor else progressColor.copy(alpha = 0.5f),
                topLeft = Offset(insetPx, centerY - tPx / 2f),
                size = Size(progressWidth, tPx),
                cornerRadius = CornerRadius(tPx / 2f),
            )

            // Compose 硬件层 setShadowLayer 不生效，用径向渐变圆代替阴影
            if (haloAlpha > 0.01f) {
                val haloRadius = thumbPx * 1.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(thumbColor.copy(alpha = haloAlpha), Color.Transparent),
                        center = Offset(thumbX, centerY),
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                    center = Offset(thumbX, centerY),
                )
            }

            // 浅色主题下纯白会融进背景，常驻 1dp 极淡描边
            drawCircle(
                color = thumbColor,
                radius = thumbPx / 2f,
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = thumbRingColor,
                radius = thumbPx / 2f,
                center = Offset(thumbX, centerY),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
