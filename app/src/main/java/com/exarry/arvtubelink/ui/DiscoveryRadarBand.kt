package com.exarry.arvtubelink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ui.theme.accentColor

/** 雷达直径 88dp */
private val RadarSize = 88.dp

/** 雷达带圆角 14dp */
private val RadarBandCorner = 14.dp

/** Searching 态扫描一圈 2000ms，与 UDP 每 2 秒一包同节奏 */
private const val SWEEP_PERIOD_SEARCHING_MS = 2000

/** Scanning 态扫描一圈 3600ms，为 Searching 的 1.8 倍 */
private const val SWEEP_PERIOD_SCANNING_MS = 3600

/** 脉冲波纹周期 1800ms */
private const val PULSE_PERIOD_MS = 1800

/** 一键发现雷达带：Searching 实线环 2000ms/圈，Scanning 虚线环 3600ms/圈 */
@Composable
fun DiscoveryRadarBand(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
) {
    val busy = state == DiscoveryUiState.Searching || state == DiscoveryUiState.Scanning
    AnimatedVisibility(
        visible = busy,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val scanning = state == DiscoveryUiState.Scanning
        val accent = accentColor()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(RadarBandCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(RadarBandCorner),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiscoveryRadar(
                scanning = scanning,
                accent = accent,
                modifier = Modifier.size(RadarSize),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (scanning) R.string.discovery_status_scanning
                        else R.string.discovery_status_searching
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (scanning) R.string.discovery_meta_scan
                        else R.string.discovery_meta_udp
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                IndeterminateBar(color = accent)
            }
        }
    }
}

/** 雷达本体：单个 rememberInfiniteTransition 主时钟驱动扫描角与脉冲相位 */
@Composable
private fun DiscoveryRadar(
    scanning: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // 转周期用 animateIntAsState 过渡：直切 infiniteRepeatable 的 spec 会重启动画跳帧
    val periodMs by animateIntAsState(
        targetValue = if (scanning) SWEEP_PERIOD_SCANNING_MS else SWEEP_PERIOD_SEARCHING_MS,
        animationSpec = tween(600),
        label = "radarPeriod",
    )

    val transition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "sweepAngle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MS, easing = LinearEasing)),
        label = "pulse",
    )

    Box(
        modifier = modifier.drawWithCache {
            // 绘制常量在 build 块分配一次，draw 相位零分配
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 5.dp.toPx()
            val sweepBrush = Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.72f to accent.copy(alpha = 0.03f),
                1.00f to accent.copy(alpha = 0.40f),
                center = center,
            )
            val gridStyle = Stroke(width = 1.dp.toPx())
            val pulseStyle = Stroke(width = 1.5.dp.toPx())
            val dashStyle = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(5.dp.toPx(), 6.dp.toPx())
                ),
            )
            val headWidth = 1.5.dp.toPx()
            val tickLen = 3.dp.toPx()

            onDrawBehind {
                drawCircle(accent.copy(alpha = 0.10f), radius, center, style = gridStyle)
                drawCircle(accent.copy(alpha = 0.10f), radius * 0.62f, center, style = gridStyle)
                drawLine(
                    color = accent.copy(alpha = 0.08f),
                    start = Offset(center.x - radius, center.y),
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = accent.copy(alpha = 0.08f),
                    start = Offset(center.x, center.y - radius),
                    end = Offset(center.x, center.y + radius),
                    strokeWidth = 1.dp.toPx(),
                )

                // rotate 包住绘制：sweepBrush 随坐标系旋转而不重建
                rotate(sweepAngle, center) {
                    drawCircle(brush = sweepBrush, radius = radius, center = center)
                    drawLine(
                        color = accent.copy(alpha = 0.85f),
                        start = center,
                        end = Offset(center.x + radius, center.y),
                        strokeWidth = headWidth,
                        cap = StrokeCap.Round,
                    )
                }

                // 脉冲：3 圈（scanning 时 2 圈）以 1/3 相位差向外扩散
                val ringCount = if (scanning) 2 else 3
                repeat(ringCount) { i ->
                    val p = (pulse + i / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.45f),
                        radius = radius * (0.30f + 0.70f * p),
                        center = center,
                        style = if (scanning) dashStyle else pulseStyle,
                    )
                }

                if (scanning) {
                    repeat(8) { i ->
                        val a = (i * 45f) * (Math.PI / 180f)
                        val x = center.x + (radius + tickLen) * kotlin.math.cos(a).toFloat()
                        val y = center.y + (radius + tickLen) * kotlin.math.sin(a).toFloat()
                        drawCircle(accent.copy(alpha = 0.55f), 1.2.dp.toPx(), Offset(x, y))
                    }
                }

                drawCircle(accent, 3.dp.toPx(), center)
            }
        },
    )
}

/** 不确定进度条 2dp：光条占轨道宽 36%，1400ms 从左外滑到右外 */
@Composable
private fun IndeterminateBar(color: Color) {
    val transition = rememberInfiniteTransition(label = "bar")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "barProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawWithCache {
                val trackRadius = CornerRadius(size.height / 2f)
                onDrawBehind {
                    drawRoundRect(
                        color = color.copy(alpha = 0.16f),
                        cornerRadius = trackRadius,
                    )
                    // 手算裁剪：只画与轨道相交的区间，Canvas 不裁剪子绘制
                    val barWidth = size.width * 0.36f
                    val x = (size.width + barWidth) * t - barWidth
                    val left = x.coerceIn(0f, size.width)
                    val right = (x + barWidth).coerceIn(0f, size.width)
                    if (right > left) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(left, 0f),
                            size = Size(right - left, size.height),
                            cornerRadius = trackRadius,
                        )
                    }
                }
            },
    )
}

/** 发现状态提示行：左侧 6dp 状态色点 + 文字 */
@Composable
internal fun DiscoveryStatusText(text: String, isFailure: Boolean = false) {
    val dotColor = if (isFailure) MaterialTheme.colorScheme.error else accentColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
