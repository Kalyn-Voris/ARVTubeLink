package com.exarry.arvtubelink.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ui.theme.accentColor
import kotlin.math.cos
import kotlin.math.sin

/** 仪表盘直径。 */
private val GaugeSize = 92.dp

/** 折线图高度。 */
private val ChartHeight = 84.dp

/** 卡片圆角（与设置卡统一 16dp）。 */
private val PushCardCorner = 16.dp

/** 仪表盘弧的起始角与跨度（135° 起，270° 扫 = 经典左下开口）。 */
private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f

/**
 * 推流状态卡（v1.2.6）：圆形仪表盘（瞬时值）+ 实时折线（趋势）+ 两枚状态 chip。
 *
 * ## 为什么仪表盘和折线都做
 *
 * 仪表盘回答"现在多少"，折线回答"刚才稳不稳"——掉帧是瞬时事件，只靠数字很容易错过。
 * 两者共用一张卡、一次布局，比拆成两张卡省约 60dp 竖向空间（首页是纵向滚动，
 * 空间紧张）。
 *
 * ## 数据诚实性
 *
 * 仪表盘与折线读的都是 [FaceRenderer.readAndResetSentFrames] 的真实送达帧数
 * （见 [rememberTelemetry]），不是配置值、不是随机数。VTS 未连接时真实值为 0，
 * 仪表盘会明确显示 0 而不是假装在推流。
 *
 * @param telemetry 采样窗口快照
 * @param tracking 是否追踪中（false 时显示"未推流"而非 0Hz）
 * @param configuredHz 用户设定频率（画参考虚线 + 说明文字用）
 * @param vtsConnected VTS 是否已认证连接（底部 chip）
 * @param vtsWarning VTS 异常/等待态文案，null = 不显示；显示条件由调用方判定
 * @param vtsWarningIsError 错误级（认证失败/异常）取 colorScheme.error，过程态取次级文字色
 */
@Composable
internal fun PushStatusCard(
    telemetry: TelemetryState,
    tracking: Boolean,
    configuredHz: Int,
    vtsConnected: Boolean,
    modifier: Modifier = Modifier,
    vtsWarning: String? = null,
    vtsWarningIsError: Boolean = false,
) {
    val accent = accentColor()
    val shape = RoundedCornerShape(PushCardCorner)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.05f))
                ),
                shape = shape,
            )
            .padding(16.dp),
    ) {
        Column {
            // ---- 标题行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.push_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (tracking) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.push_card_live),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 仪表盘 + 折线 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                HzGauge(
                    value = telemetry.latest ?: 0f,
                    maxValue = configuredHz.toFloat().coerceAtLeast(10f) * 1.25f,
                    accent = accent,
                    tracking = tracking,
                    modifier = Modifier.size(GaugeSize),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.push_chart_caption, TELEMETRY_WINDOW),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SparkLine(
                        samples = telemetry.samples,
                        referenceHz = configuredHz.toFloat(),
                        accent = accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ChartHeight),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (telemetry.latest != null) {
                    stringResource(
                        R.string.push_card_summary,
                        configuredHz,
                        telemetry.latest,
                    )
                } else {
                    stringResource(R.string.push_card_waiting, configuredHz)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            // ---- 状态 chip ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusChip(
                    label = stringResource(R.string.push_chip_ar),
                    value = stringResource(
                        if (tracking) R.string.push_chip_ar_on else R.string.push_chip_ar_off
                    ),
                    dotColor = if (tracking) accent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    label = stringResource(R.string.push_chip_vts),
                    value = stringResource(
                        if (vtsConnected) R.string.push_chip_vts_on else R.string.push_chip_vts_off
                    ),
                    dotColor = if (vtsConnected) accent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
            }

            // 警示是 chip 的注解（chip 只有二值，这里给具体原因），紧贴其下
            vtsWarning?.let { warning ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vtsWarningIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 圆形仪表盘：底弧 + 进度弧 + 指针 + 中心数值。
 *
 * 单 Canvas 按 z 序画完所有层（不叠多个 Canvas/Box）——这是这类组件最省的画法。
 * 指针用一条 Round-cap [androidx.compose.ui.graphics.drawscope.DrawScope.drawLine]
 * 表达，不需要 Path。
 */
@Composable
private fun HzGauge(
    value: Float,
    maxValue: Float,
    accent: Color,
    tracking: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fraction = (value / maxValue).coerceIn(0f, 1f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 绘制全部在 modifier 的 drawWithCache 里（尺寸变化时才重建）
        Box(
            modifier = Modifier.size(GaugeSize).drawWithCache {
                val strokeWidth = size.minDimension / 15f
                val inset = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(inset, inset)
                val center = Offset(size.width / 2f, size.height / 2f)
                val needleLen = (size.minDimension / 2f - strokeWidth * 1.6f)
                val needleWidth = 2.dp.toPx()
                val hubRadius = 3.dp.toPx()

                onDrawBehind {
                    // 底弧（全轨道）
                    drawArc(
                        color = trackColor.copy(alpha = 0.55f),
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP_ANGLE,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                    // 进度弧（实测值）
                    if (fraction > 0f) {
                        drawArc(
                            brush = Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.65f), accent),
                            ),
                            startAngle = GAUGE_START_ANGLE,
                            sweepAngle = GAUGE_SWEEP_ANGLE * fraction,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                    // 指针
                    val angleRad = Math.toRadians(
                        (GAUGE_START_ANGLE + GAUGE_SWEEP_ANGLE * fraction).toDouble()
                    )
                    drawLine(
                        color = accent,
                        start = center,
                        end = Offset(
                            center.x + cos(angleRad).toFloat() * needleLen,
                            center.y + sin(angleRad).toFloat() * needleLen,
                        ),
                        strokeWidth = needleWidth,
                        cap = StrokeCap.Round,
                    )
                    // 轴心
                    drawCircle(accent, hubRadius, center)
                }
            },
        )
        // 数值叠在 Canvas 中央偏下（指针在上方，避免遮挡）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (tracking) {
                    String.format(java.util.Locale.US, "%.1f", value)
                } else {
                    "--"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.push_gauge_unit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 极简实时折线：30 点，Path 复用 + 渐变面积 + 领先点。
 *
 * ## Path 复用（防 GC 抖动）
 *
 * `Path` 与填充 `Path` 都在 `drawWithCache` 的 build 块里创建一次，draw 相位只
 * `rewind()` 再重填——每秒重绘 60 次时避免每帧两个新 Path 对象。
 *
 * ## y 轴归一化
 *
 * 上界取 max(参考频率 × 1.25, 实测最大 × 1.1)，下界取 0：这样曲线始终有参考虚线
 * 可对照，且不会因为实测值偶尔超配而顶出画布。
 */
@Composable
private fun SparkLine(
    samples: FloatArray,
    referenceHz: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    // 绘制全部在 modifier 的 drawWithCache 里（尺寸变化时才重建），Box 本身无内容
    Box(
        modifier = modifier.drawWithCache {
            val linePath = Path()
            val fillPath = Path()
            val strokeWidth = 2.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            onDrawBehind {
                // 无数据：画一条底轨，提示"还没开始"
                if (samples.size < 2) {
                    drawLine(
                        color = gridColor.copy(alpha = 0.4f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                    return@onDrawBehind
                }

                val maxObserved = samples.max()
                val upper = maxOf(referenceHz * 1.25f, maxObserved * 1.1f, 1f)
                val stepX = size.width / (samples.size - 1).toFloat()

                // ---- 折线 ----
                linePath.rewind()
                fillPath.rewind()
                samples.forEachIndexed { i, v ->
                    val x = stepX * i
                    val y = size.height * (1f - (v / upper).coerceIn(0f, 1f))
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                // 面积收口到底边
                fillPath.lineTo(size.width, size.height)
                fillPath.lineTo(0f, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0f)),
                    ),
                )

                // ---- 参考虚线（配置频率）----
                val refY = size.height * (1f - (referenceHz / upper).coerceIn(0f, 1f))
                drawLine(
                    color = gridColor.copy(alpha = 0.7f),
                    start = Offset(0f, refY),
                    end = Offset(size.width, refY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )

                drawPath(
                    path = linePath,
                    color = accent,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                // ---- 领先点：外圈光晕 + 内实心 ----
                val lastY = size.height *
                    (1f - (samples.last() / upper).coerceIn(0f, 1f))
                val lastPoint = Offset(size.width, lastY)
                drawCircle(accent.copy(alpha = 0.22f), 5.dp.toPx(), lastPoint)
                drawCircle(accent, 2.5.dp.toPx(), lastPoint)
            }
        },
    )
}

/** 状态 chip：小圆点 + 标签 + 值。 */
@Composable
private fun StatusChip(
    label: String,
    value: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(dotColor),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
