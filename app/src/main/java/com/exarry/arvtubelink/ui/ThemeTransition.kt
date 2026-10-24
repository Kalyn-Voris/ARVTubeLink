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

/** 主题切换过渡时长(ms)：圆形从满屏收缩到 0。 */
private const val RevealDurationMs = 350

/**
 * 主题切换过渡层(截图覆盖模式)：
 *
 * ## 原理与调用时序
 *
 * 切换瞬间由调用方(AppRoot)在**主题翻转前**同步截取旧主题帧([screenshot])，本组件把该帧
 * 作为覆盖层浮在 [content](已是新主题)之上，用圆形裁剪把圆心 [revealOrigin] 一带挖空——
 * 圆外画旧截图、圆内不画，露出底层新主题；圆随动画扩大，视觉即"新主题从点击位置向外扩散"。
 * 完整
 * 时序：
 *  1. 用户点主题 Switch → SwitchRow 先上报圆心 → AppRoot 在圆心回调里同步 drawToBitmap
 *     (此刻重组未发生，View 画的仍是旧主题)并递增 [transitionSignal]；
 *  2. 同一次重组：content 翻到新主题 + 覆盖层出现(progress=0f，圆半径 0，EvenOdd 路径 =
 *     全屏矩形，满屏旧截图，无缝接住翻转首帧，不露新主题)；
 *  3. LaunchedEffect 驱动 progress 0f→1f([RevealDurationMs] 时长圆形扩大)；
 *  4. 动画结束回调 [onTransitionFinished](清 origin + screenshot)→ 覆盖层移除。
 *
 * ## 关键设计
 *
 *  - **无 Animatable**：progress 是 `remember(transitionSignal) { mutableStateOf(0f) }` 的
 *    普通 State，由无状态 [animate] 驱动。signal 变化时 state 在**组合阶段**重建为 0f——
 *    覆盖层首帧必然满屏旧截图。初始化不经 LaunchedEffect（其协程调度可能晚于首帧 draw，
 *    首帧会用残留旧值）；覆盖层移除即 state 销毁，跨动画零残留。
 *  - **clipPath 极性**：Path = 全屏矩形 + 圆（PathFillType.EvenOdd 相减）= 圆外区域；
 *    `clipPath { drawImage }` = 圆外画截图、圆内不画(露新主题)。progress 0f→1f = 圆扩大
 *    = 新主题从圆心扩散。
 *  - **交互拦截**：覆盖层 Box 不裁剪触摸(clipPath 只影响绘制)，pointerInput 吞掉全部指针
 *    事件——动画期间圆外露出的新主题 UI 也不可点，防半透明状态误操作。
 *  - **快速连点双防线**：主防线是动画期全屏吞触摸(Switch 收不到点击)；兜底是
 *    `LaunchedEffect(transitionSignal)` 重启——若 signal 穿透，progress 重建为 0f、动画
 *    从满屏重跑，同样零残留。
 *  - **Bitmap 回收**(单主路径 + onDispose 兜底，双路径天然互补)：主路径在 AppRoot 覆盖
 *    旧值前 recycle(连点穿透场景)；兜底是本组件 DisposableEffect 持有进入组合时的那张图，
 *    覆盖层离组(动画正常结束 / 穿透换图 / 整体离开组合)时回收。`Bitmap.recycle()` 幂等，
 *    双路径同时触发安全；真正会崩的是"还在被画时回收"——两条路径触发时新图均已就位、
 *    旧图均不再被画(详见 AppRoot 圆心回调注释)。
 *
 * ## 已知限制(有意接受，勿当作 bug)
 *
 *  - **GL 区域截不到**：GLSurfaceView 是独立 Surface，drawToBitmap 拿不到其内容，截图上
 *    对应区域为空洞。正常场景 GL 是 1dp 隐藏不可见；校准全屏时主题 Switch 不在屏幕上、
 *    无点击路径，故该限制无实际影响。
 *  - **系统深浅切换无过渡动画**：themeFollowSystem=ON 时系统切换走 onConfigurationChanged，
 *    拿不到翻转前的同步钩子、截不到旧主题帧；仅用户点开关有过渡(预期行为)。
 *
 * @param screenshot 旧主题帧(全屏)，AppRoot 在主题翻转前同步截取；null = 无过渡
 * @param revealOrigin 圆心(root 坐标；覆盖层全屏铺满时与本地坐标重合)；null = 无过渡
 * @param transitionSignal 主题切换信号，每次切换 +1 单调递增；0 = 首次启动不动画
 * @param onTransitionFinished 动画正常结束回调(协程被连点重启取消时不回调，由新一轮动画
 *   最终回调一次)
 */
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

/**
 * 覆盖层：旧主题截图 + 圆形裁剪收缩。全屏铺在 [ThemeTransition] 的 content 之上；
 * 内部自持仅 progress 一个 state，screenshot / origin 均为外部传入(无 settledIsDark、
 * 无 prevIsDark、无双层树)。
 */
@Composable
private fun TransitionOverlay(
    screenshot: ImageBitmap,
    origin: Offset,
    transitionSignal: Int,
    onTransitionFinished: () -> Unit,
) {
    // 兜底回收：先存局部变量再交给 DisposableEffect——onDispose 捕获的必须是"进入组合时的
    // 这张图"；在 onDispose 里读外层参数会读到重组后的新值(漏回收/误回收对象)
    val current = screenshot
    DisposableEffect(current) {
        onDispose {
            val bmp = current.asAndroidBitmap()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    // signal 变化的同一次重组中重建为 0f：覆盖层首帧(本次重组的 draw 阶段)必然满屏旧截图。
    // 初始化在组合阶段完成，不经 LaunchedEffect(其协程调度可能晚于首帧 draw)
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
            // 动画期全屏吞触摸(含圆外)：防半透明过渡中误点新主题 UI；快速连点主防线
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            // 极性：Path = 全屏矩形 − 圆(EvenOdd) = 圆外区域，clipPath 内 drawImage = 圆外画
            // 旧截图、圆内不画(露底层新主题)；半径随 progress 扩大 → 新主题从圆心向外扩散。
            // 注意：Modifier.clipPath 在 Compose ui 1.12 已移除，必须用 DrawScope.clipPath
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

/** 圆心到屏幕最远角的直线距离：progress=1f 时圆覆盖全屏(EvenOdd 路径为空，覆盖层不画)。 */
private fun maxRevealRadius(origin: Offset, size: Size): Float = hypot(
    maxOf(origin.x, size.width - origin.x),
    maxOf(origin.y, size.height - origin.y),
)
