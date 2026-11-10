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

/** 仪表盘直径 92dp */
private val GaugeSize = 92.dp

/** 折线区高度 84dp */
private val ChartHeight = 84.dp

/** 卡片圆角 16dp，与设置卡一致 */
private val PushCardCorner = 16.dp

/** 弧从 135° 起扫 270°，左下开口 */
private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f

/** 推流状态卡：瞬时值仪表盘 + 趋势折线 + 两枚状态 chip */
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

            // chip 只有二值，警示行补具体原因，紧贴 chip 之下
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

/** 圆形仪表盘：底弧 + 进度弧 + 指针 + 中心数值，单 Canvas 按 z 序画完 */
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
                    drawArc(
                        color = trackColor.copy(alpha = 0.55f),
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP_ANGLE,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
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
                    drawCircle(accent, hubRadius, center)
                }
            },
        )
        // 数值下移 18dp 避开上方指针
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

/** 实时折线：30 点，y 上界 max(参考×1.25, 实际×1.1, 1) */
@Composable
private fun SparkLine(
    samples: FloatArray,
    referenceHz: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier.drawWithCache {
            // Path 在 build 块创建一次，draw 相位只 rewind 重填
            val linePath = Path()
            val fillPath = Path()
            val strokeWidth = 2.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            onDrawBehind {
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
                fillPath.lineTo(size.width, size.height)
                fillPath.lineTo(0f, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0f)),
                    ),
                )

                // 参考虚线取配置频率
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

                // 领先点：5dp 光晕 + 2.5dp 实心
                val lastY = size.height *
                    (1f - (samples.last() / upper).coerceIn(0f, 1f))
                val lastPoint = Offset(size.width, lastY)
                drawCircle(accent.copy(alpha = 0.22f), 5.dp.toPx(), lastPoint)
                drawCircle(accent, 2.5.dp.toPx(), lastPoint)
            }
        },
    )
}

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
