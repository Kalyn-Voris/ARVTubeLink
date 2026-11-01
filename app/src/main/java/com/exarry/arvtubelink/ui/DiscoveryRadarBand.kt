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

/** 雷达直径。 */
private val RadarSize = 88.dp

/** 雷达带圆角（与首页引导卡 14dp 同档）。 */
private val RadarBandCorner = 14.dp

/** Searching 态扫描一圈时长（ms）：正好对应"等待 2 秒"的感知节奏。 */
private const val SWEEP_PERIOD_SEARCHING_MS = 2000

/** Scanning 态扫描一圈时长（ms）：减速 1.8 倍，视觉上"更费力"。 */
private const val SWEEP_PERIOD_SCANNING_MS = 3600

/** 脉冲波纹周期（ms）。 */
private const val PULSE_PERIOD_MS = 1800

/**
 * 「一键发现」雷达带（v1.2.6）。
 *
 * ## 为什么是"带"而不是按钮里的转圈
 *
 * 旧实现在 [AutoDiscoverButton] 里放 20dp [androidx.compose.material3.CircularProgressIndicator]，
 * 空间只有 48dp，且与按钮的其他状态挤在一行。本组件把"忙碌"升级为一条全宽卡片，
 * 插在 IP/端口输入行与「连接 VTS」按钮之间；按钮本身退化为静态雷达图标（不转圈），
 * 避免同一屏两个旋转动画互相抢注意力。
 *
 * ## 与底层时序的呼应（关键）
 *
 * 视觉状态**完全由既有 [DiscoveryUiState] 驱动**，不新增状态机、不改 AppRoot：
 *  - `Searching`（UDP 广播监听，0~[VtsDiscoveryClient.TCP_FALLBACK_DELAY_MS]=3s）
 *      → 实线脉冲环 + 2000ms/圈；
 *  - `Scanning`（3s 后转 TCP 兜底端口扫描，最长到 TOTAL_DEADLINE_MS=8s）
 *      → 虚线分段环 + 3600ms/圈 + 外圈刻度点亮。
 *
 * 所以"3 秒切换"不是设计稿里硬编码的定时器，而是**镜像底层真实的 3s 兜底延迟**；
 * 底层常量改动时视觉自动跟随，不存在两处漂移。
 *
 * `Found`/`Failed` 时本组件整体淡出收起——那两种状态已有各自的文字提示（见
 * HomeScreen 的 DiscoveryStatusText），不需要雷达继续转。
 */
@Composable
fun DiscoveryRadarBand(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
) {
    val busy = state == DiscoveryUiState.Searching || state == DiscoveryUiState.Scanning
    // 仅在忙态显示；收起动画让布局平滑让位，避免按钮突然跳位
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

/**
 * 雷达本体：单 Canvas + drawWithCache，全部相位由**一个** [rememberInfiniteTransition]
 * 主时钟驱动（不是每环一个 Animatable——后者在 3 圈脉冲 + 扫描角下会产生 4 个状态对象
 * 和 4 条独立动画时间线，相位同步反而更难保证）。
 *
 * 绘制顺序（z 序从低到高）：网格环 → 十字 → 扫描扇形 → 头缘亮线 → 脉冲波纹 → 中心点。
 *
 * @param scanning TCP 兜底阶段：虚线环 + 外圈刻度 + 更慢的转速。
 */
@Composable
private fun DiscoveryRadar(
    scanning: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // 转速在两种模式间平滑过渡（直接切 spec 会跳帧，因为 infiniteRepeatable 会重启）
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

    // 全部绘制在 modifier 的 drawWithCache 里完成，Box 本身不承载内容——
    // 绘制命令被缓存到尺寸变化时才重建，而不是每帧重新组合 draw 闭包。
    Box(
        modifier = modifier.drawWithCache {
            // ★ build 块：尺寸确定后只分配一次，draw 相位零分配
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
                // ---- 静态底：两个网格环 + 十字 ----
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

                // ---- 扫描扇形（rotate 包住绘制，Brush 本身不重建）----
                rotate(sweepAngle, center) {
                    drawCircle(brush = sweepBrush, radius = radius, center = center)
                    // 头缘亮线：与扇形同角度，视觉上就是"扫描线"
                    drawLine(
                        color = accent.copy(alpha = 0.85f),
                        start = center,
                        end = Offset(center.x + radius, center.y),
                        strokeWidth = headWidth,
                        cap = StrokeCap.Round,
                    )
                }

                // ---- 脉冲波纹：3 圈错相位向外扩散 ----
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

                // ---- Scanning 专属：外圈 8 个刻度点（暗示逐端口探测）----
                if (scanning) {
                    repeat(8) { i ->
                        val a = (i * 45f) * (Math.PI / 180f)
                        val x = center.x + (radius + tickLen) * kotlin.math.cos(a).toFloat()
                        val y = center.y + (radius + tickLen) * kotlin.math.sin(a).toFloat()
                        drawCircle(accent.copy(alpha = 0.55f), 1.2.dp.toPx(), Offset(x, y))
                    }
                }

                // ---- 中心点 ----
                drawCircle(accent, 3.dp.toPx(), center)
            }
        },
    )
}

/**
 * 不确定进度条（2dp）：光条从左外滑到右外，比转圈更"正在读取数据"。
 *
 * 溢出裁剪是手算的（只画与轨道相交的区间），因为 Canvas 默认不裁剪子绘制，
 * 直接画会超出轨道边界。
 */
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

/**
 * 发现失败/超时时的提示行：保留既有文字样式，但补一个状态色点，让"失败"在视觉上
 * 立刻可辨（旧实现只有一行灰字，用户容易漏看）。
 */
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
