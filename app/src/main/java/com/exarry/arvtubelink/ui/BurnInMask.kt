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

/** OLED 防烧屏全屏纯黑遮罩：文字亮 55s 灭 25s 循环，亮期每 5s 随机垂直偏移 ±12px */
@Composable
fun BurnInMask(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return

    // pointerInput(Unit) 不随重组重启，经 rememberUpdatedState 取最新 onDismiss
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    var textVisible by remember { mutableStateOf(true) }
    var textOffsetY by remember { mutableIntStateOf(0) }

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
                // 手写 tap 状态机：detectTapGestures 与 consume-all 共用消费状态会让 UP 变 CANCEL
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var isTap = true
                    var upChange: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        // 状态栏边缘下拉被系统抢流后，CANCEL 事件自带 consumed 标记
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
                            isTap = false
                        }
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
                fontSize = 64.sp,
                color = MASK_TEXT_COLOR,
                modifier = Modifier.offset { IntOffset(0, textOffsetY) },
            )
        }
    }
}

private const val TEXT_LIT_SECONDS = 55L

private const val TEXT_DIM_SECONDS = 25L

private const val TEXT_SHIFT_INTERVAL_SECONDS = 5L

private const val TEXT_OFFSET_PX = 12

private val MASK_TEXT_COLOR = Color(0xFF1A1A1A)
