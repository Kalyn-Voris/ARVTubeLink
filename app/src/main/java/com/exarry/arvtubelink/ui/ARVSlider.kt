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

// ---------------------------------------------------------------------------
// ARVSlider 规格常量（真机微调集中改这里）
// ---------------------------------------------------------------------------

/** 静止态轨道高度：极细深灰圆角线。 */
private val TrackHeightIdle = 4.dp

/** 拖拽态轨道高度：胶囊状粗圆角矩形（平滑加粗的目标）。 */
private val TrackHeightActive = 12.dp

/** 静止态滑块直径：小的白色圆点。 */
private val ThumbSizeIdle = 12.dp

/** 拖拽态滑块直径：白色圆点放大 + 光环。 */
private val ThumbSizeActive = 20.dp

/**
 * 触摸热区高度：视觉只有 4dp，但手指可点区域必须有 48dp。
 * 实现上是根 [Box] 撑到 48dp 高、Canvas 在其内部居中绘制 4dp 轨道——
 * 热区靠「容器高」获得，而不是给轨道加 padding。
 */
private val SliderTouchTargetHeight = 48.dp

/** 轨道粗细 / 进度条粗细的过渡时长（ms）。 */
private const val TrackMorphMs = 150

/** 静息光环透明度（有阴影感但不抢眼）。 */
private const val HaloAlphaIdle = 0.10f

/** 拖拽光环透明度。 */
private const val HaloAlphaActive = 0.24f

/** 轨道两侧内缩基准：取放大态 thumb 半径，保证 thumb 圆心在轨道可视范围内。 */
private val TrackInset = ThumbSizeActive / 2

/**
 * 极简现代风格参数滑块（v1.2.6）。
 *
 * ## 为什么自绘而不用 Material3 `Slider`
 * m3 `Slider` 的 `thumb` / `track` 槽只能替换「静态外观」：轨道高度、thumb 尺寸在
 * 组合期就定死，拿不到「是否正在拖拽」这一层状态，更无法让粗细随手势连续形变。
 * 而本设计的三条核心要求全部依赖拖拽态：
 *  1. 轨道 4dp ↔ 12dp 平滑加粗；
 *  2. thumb 12dp ↔ 20dp 放大 + 光环；
 *  3. 过渡 150ms。
 * 故改为 `Canvas` + `pointerInput` 自绘，用 [MutableInteractionSource] 的
 * `DragInteraction.Start/Stop` 驱动形变。
 *
 * ## 交互模型
 *  - **热区 48dp**：根 Box 高 48dp，任意位置都能触发（见 [SliderTouchTargetHeight]）。
 *  - **按下即高亮**：手指落下立刻 emit `DragInteraction.Start`，视觉反馈先于位移，
 *    不会出现「已经开始拖了但轨道还是细的」的滞后感。
 *  - **不抢纵向滚动**：横向位移未超过 touchSlop 前，若纵向占优就发 `Cancel` 且
 *    不消费事件，父级 LazyColumn 可正常滚动（对齐 m3 `Slider` 行为）。
 *  - **点击定位**：落下后未发生拖动就抬起，按抬起点定位（tap-to-seek）。
 *
 * ## 取值与步进
 * [steps] > 0 时按 m3 同款语义吸附：`(end - start) / (steps + 1)` 为一步，
 * 共 `steps + 2` 个可达值（含两端）。
 *
 * @param value 当前值（受控，外部为唯一真源）
 * @param onValueChange 拖动中持续回调（已吸附到步进网格）
 * @param onValueChangeFinished 手势结束回调（用于落盘）
 * @param valueRange 值域
 * @param steps 内部刻度数；0 = 连续
 * @param enabled false 时半透明且不响应
 * @param interactionSource 供外部观察拖拽状态（默认内部自建）
 */
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
    // 拖拽态真源：collectIsDraggedAsState 内部即监听 DragInteraction.Start / Stop / Cancel
    val dragging by interactionSource.collectIsDraggedAsState()

    // 颜色在此处（组合期）读取：DrawScope 内不可访问 MaterialTheme
    val trackColor = inactiveColor
    val progressColor = activeColor
    val thumbRingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    // 交互源必须显式 remember 成稳定的一个实例：pointerInput 的挂起块里要用，
    // 而 awaitEachGesture 是 restricted 挂起作用域，不能直接调 interactionSource.emit()。
    // 解法是在 CoroutineScope 里起一个 emit 协程（见下方 pointerInput），并保证实例稳定。
    val dragSource = interactionSource

    // 形变：轨道 tween(150) 顺滑；thumb 用带回弹的 spring，放大时有轻微「弹出来」的手感
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

    // 手势协程里读到的必须是最新回调和最新值，否则 pointerInput 闭包会捕获陈旧引用
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentValue by rememberUpdatedState(value)

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: 1f
    val segments = if (steps > 0) steps + 1 else 0
    val lower = if (start <= end) start else end
    val upper = if (start <= end) end else start

    // 吸附：与 m3 同款网格。steps=0（连续）时只做 clamp。
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
            // ★ 热区：48dp 高容器 + 视觉轨道居中绘制（见 SliderTouchTargetHeight 说明）
            .height(SliderTouchTargetHeight)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(start, end, steps) {
                    // 轨道两侧内缩半个放大态 thumb 半径：thumb 圆心不会溢出容器被裁
                    val inset = TrackInset.toPx()

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        fun valueAtX(x: Float): Float {
                            val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                            val f = ((x - inset) / trackWidth).coerceIn(0f, 1f)
                            return snap(start + f * span)
                        }

                        // ★ 按下即跳值（修复「展开了但不跟手」）：
                        // 绝大多数滑动条的直觉是「点哪里滑块就跳到哪里」。原先要等位移超过
                        // touchSlop 才写第一个值，于是轨道已经因 DragInteraction.Start 展开、
                        // 视觉进入拖拽态，但 thumb 还钉在旧位置——就是用户说的「展开了但不跟手」。
                        // 这里在 DOWN 当帧就把 value 对齐到手指 X，thumb 立刻到位。
                        //
                        // 副作用与回滚：本控件嵌在 verticalScroll 列表里，用户可能只是
                        // 「手指从滑块上起手去滚列表」。DOWN 改值后若判定成纵向滚动，必须把
                        // 值恢复成按下前的快照，否则一次滚动手势就会把参数改掉——
                        // 这是「按下即跳值」方案必须配套的补偿，否则换来一个更糟的 bug。
                        val valueBeforeDown = currentValue
                        currentOnValueChange(valueAtX(down.position.x))

                        var isDragging = false
                        var cancelled = false

                        // Compose 的标准交互模型：DragInteraction.Start 需要挂在一个
                        // PressInteraction.Press 上（它代表「这次拖拽源自哪次按下」）。
                        // 用非挂起的 tryEmit 而非 emit：awaitEachGesture 的块是 restricted
                        // 挂起作用域，不能直接调用 emit；tryEmit 同步入队，语义等价。
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
                                    // ★ 抬起：这里**不再做任何补偿性定位**。
                                    // 原先的 `if (!isDragging) valueAtX(change.position.x)` 是
                                    // 「跳跃」的第二个来源：慢速拖动时（见下方 slop 说明）
                                    // isDragging 恒为 false，拖动全程不更新，直到松手才一次性
                                    // 跳到手指位置——看起来像滑块突然瞬移。
                                    // 现在 DOWN 已跳值、拖动中也持续跟手，抬手只需消费结束事件。
                                    // isDragging=false 的纯点击场景，值在 DOWN 时就已经正确。
                                    change.consume()
                                    break
                                }

                                // ★ 累计位移，而非单帧增量（修复「拖动中不跟手」的根因）：
                                // positionChange() 返回的是「相对上一次事件的增量」。慢速拖动
                                // 时每帧只移动 1~2px，永远小于 touchSlop(≈8dp)，于是原先
                                // `abs(delta.x) < slop && abs(delta.y) < slop -> continue`
                                // 会一直命中、isDragging 永远置不上，值一次都不更新。
                                // 必须用「相对按下点」的累计位移来判断是否越过 slop。
                                val totalX = change.position.x - down.position.x
                                val totalY = change.position.y - down.position.y

                                if (!isDragging) {
                                    val slop = viewConfiguration.touchSlop
                                    if (abs(totalX) < slop && abs(totalY) < slop) {
                                        continue // 累计位移仍在抖动范围内，继续等待方向判定
                                    }
                                    if (abs(totalY) > abs(totalX)) {
                                        // ★ 纵向占优 = 用户在滚列表，让给外层 verticalScroll。
                                        // 关键：cancel 后**不消费**本事件，且立即返回，
                                        // 让父级 pointerInput 从当前这个事件开始接管——
                                        // 因此取消时不会吞掉父容器的滚动。
                                        cancelled = true
                                        break
                                    }
                                    isDragging = true
                                }

                                currentOnValueChange(valueAtX(change.position.x))
                                change.consume()
                            }
                        } finally {
                            // 无论正常结束还是取消，状态都必须在 finally 里复位：
                            // Cancel 让轨道平滑缩回 4dp；Stop 同理并触发落盘回调。
                            if (cancelled) {
                                // 判定为纵向滚动 = 用户不是要调这个滑块：把 DOWN 时
                                // 抢先写入的值回滚到按下前，避免滚列表顺手改掉参数。
                                currentOnValueChange(valueBeforeDown)
                                dragSource.tryEmit(DragInteraction.Cancel(dragStart))
                                dragSource.tryEmit(PressInteraction.Cancel(press))
                            } else {
                                dragSource.tryEmit(DragInteraction.Stop(dragStart))
                                dragSource.tryEmit(PressInteraction.Release(press))
                                // 落盘只在「用户确实调整过值」时触发：纵向滚动的误触不该写盘
                                currentOnFinished?.invoke()
                            }
                        }
                    }
                }
            )
            // 无障碍：读屏与 Switch Access 依赖 progressBarRangeInfo / setProgress。
            // 自绘控件必须显式补上（m3 Slider 是内建这些语义的）。
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

            // ① 背景轨道：极细深灰圆角线（胶囊 = 圆角半径取高度一半）
            drawRoundRect(
                color = if (enabled) trackColor else trackColor.copy(alpha = 0.5f),
                topLeft = Offset(insetPx, centerY - tPx / 2f),
                size = Size(trackWidth, tPx),
                cornerRadius = CornerRadius(tPx / 2f),
            )

            // ② 已滑动进度：同样的细线、主题色；最小宽度 = 轨道高，value=0 时呈圆头起点
            val progressWidth = (thumbX - insetPx).coerceAtLeast(tPx)
            drawRoundRect(
                color = if (enabled) progressColor else progressColor.copy(alpha = 0.5f),
                topLeft = Offset(insetPx, centerY - tPx / 2f),
                size = Size(progressWidth, tPx),
                cornerRadius = CornerRadius(tPx / 2f),
            )

            // ③ 光环：径向渐变模拟柔光/阴影。Compose 在硬件层上 setShadowLayer 不生效，
            //    故用渐变圆代替真阴影，跨平台效果一致。拖拽时更明显。
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

            // ④ 滑块：白色圆点。浅色主题下纯白会与背景融为一体，故常驻一圈极淡描边，
            //    保证两种主题下都清晰可辨（观感仍是「白色圆点」）
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
