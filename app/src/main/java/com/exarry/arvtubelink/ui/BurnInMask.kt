package com.exarry.arvtubelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * OLED 防烧屏全屏纯黑遮罩（「推流中」待机，替代旧「2s 无操作压亮度」方案）。
 *
 * 驱动链（状态与计时逻辑全在 MainActivity，本组件纯展示 + tap 判定 + 吞事件）：
 *   beginTracking / onResume(追踪中) / closeCalibration(校准结束且追踪仍在)
 *     → 启动 2s 防烧屏倒计时（校准中整体挂起，不计时）
 *   倒计时到点（dimRunnable）→ burnInMaskState = true → 本遮罩显示
 *   tap 本遮罩（UP 时 onDismiss 回调上收）→ burnInMaskState = false → 遮罩移除
 *   stopTracking / onPause（cancelIdleDim 统一收尾）→ 遮罩立即消失
 *
 * 单 pointerInput 手写 tap 状态机。不用 detectTapGestures + consume-all 双 modifier：
 * 两个 pointerInput 共享消费状态，consume-all 消费后 detectTapGestures 的 UP 判定被
 * CANCEL、onTap 永不触发（真机实证）。三个判定：
 *  1. tap：DOWN→UP 位移未超 touch slop、期间未收到外部已消费的事件 → onDismiss 移除；
 *  2. pilfer（状态栏边缘下拉）：系统抢走事件流后应用收到的 CANCEL 事件自带 consumed
 *     标记 → 放弃 tap、遮罩保持（transient 能透出的前提就是流被抢，互为充要）；
 *  3. drag（屏幕中部拖动）：位移超 slop → 放弃 tap，事件仍全程消费到 UP（与旧
 *     「任意触摸即移除」等价）。
 * 全程消费（含 DOWN 与多指的其余指针）：首触仅移除遮罩不落点（防误触「停止追踪」
 * 等下层按钮），再点才操作真实界面；拖动也不会让遮罩下的 clickable/scrollable 响应。
 *
 * 系统栏：遮罩期由 AppRoot 的 LaunchedEffect 调 WindowInsetsControllerCompat 隐藏
 * systemBars（API≤34 非 EtE 窗口借此扩展进状态栏区域实现全覆盖；API 35+ targetSdk 37
 * 被强制 EtE，窗口本就延伸到栏后）。behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：
 * 遮罩期用户下拉 → 状态栏以半透明 overlay 临时透出（overlay 不触发窗口 relayout，
 * 遮罩不闪）→ 数秒后自动隐藏 → 遮罩恢复全覆盖；下拉不退出遮罩（见上手写状态机的
 * pilfer 判定），供推流中瞄一眼时间/电量，无需点遮罩唤醒。
 *
 * 文字防烧屏：中央「推流中」18sp 暗灰 #1A1A1A（观感亮度≈0.1）；亮 55s → 灭 25s 循环
 * （灭期全屏纯黑），亮期每 5s 随机垂直偏移 ±12px（字面像素，真机观感
 * 偏小再议）——防止固定位置的明文字体自身烧屏。
 *
 * 遮罩期间 GL 渲染 / 相机采集 / VTS 推流全部继续（黑屏只是视觉层，「推流中」即此
 * 语义）；FLAG_KEEP_SCREEN_ON（MainActivity.onCreate）保证遮罩期屏幕不熄。
 */
@Composable
fun BurnInMask(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return

    // pointerInput(Unit) 不随重组重启：经 rememberUpdatedState 保证 tap 回调取最新引用
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    var textVisible by remember { mutableStateOf(true) }
    var textOffsetY by remember { mutableIntStateOf(0) }

    // 文字节奏：亮 55s（每 5s 换随机偏移，55/5=11 次）→ 灭 25s → 循环
    LaunchedEffect(Unit) {
        while (true) {
            textVisible = true
            repeat((TEXT_LIT_SECONDS / TEXT_SHIFT_INTERVAL_SECONDS).toInt()) {
                textOffsetY = Random.nextInt(-TEXT_OFFSET_PX, TEXT_OFFSET_PX + 1)
                delay(TEXT_SHIFT_INTERVAL_SECONDS * 1000L)
            }
            textVisible = false
            delay(TEXT_DIM_SECONDS * 1000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // 手写 tap 状态机（三个判定与为何不用双 modifier 见类 KDoc）：
                // 事件全程消费防穿透，出口三选一——UP 未超 slop → tap 移除；
                // 收到外部已消费的事件（系统 pilfer/CANCEL）→ 放弃 tap 保持遮罩；
                // 位移超 slop → 判拖动，继续消费到 UP 但不移除
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var isTap = true
                    var upChange: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        // 系统手势（状态栏边缘下拉）抢走事件流后，到达应用的 CANCEL 事件
                        // 自带 consumed 标记 → 放弃 tap（遮罩保持，transient 正常透出）
                        if (event.changes.any { it.isConsumed }) {
                            isTap = false
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            upChange = change
                            break
                        }
                        if (isTap && (change.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                        ) {
                            isTap = false // 拖动：不再判 tap，但继续消费到 UP（防穿透）
                        }
                        // 全吞本事件所有指针（含多指的额外手指），防穿透
                        event.changes.forEach { it.consume() }
                    }
                    if (isTap && upChange != null) {
                        upChange.consume()
                        currentOnDismiss()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (textVisible) {
            Text(
                text = stringResource(R.string.burn_in_mask_label),
                fontSize = 18.sp,
                color = MASK_TEXT_COLOR,
                modifier = Modifier.offset { IntOffset(0, textOffsetY) },
            )
        }
    }
}

/** 文字亮期时长（秒）。 */
private const val TEXT_LIT_SECONDS = 55L

/** 文字灭期时长（秒），灭期全屏纯黑。 */
private const val TEXT_DIM_SECONDS = 25L

/** 文字随机偏移的换位间隔（秒）。 */
private const val TEXT_SHIFT_INTERVAL_SECONDS = 5L

/** 文字垂直随机偏移幅度（字面像素，真机观感偏小再议）。 */
private const val TEXT_OFFSET_PX = 12

/** 文字暗灰（#1A1A1A，观感亮度≈0.1）：黑底上仅勉强可辨，避免高亮文字自身烧屏。 */
private val MASK_TEXT_COLOR = Color(0xFF1A1A1A)
