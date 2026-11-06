package com.exarry.arvtubelink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exarry.arvtubelink.R
import kotlinx.coroutines.delay

// HomeScreen / SettingsCard 的同名常量是 private，跨文件不可见，故此处保留副本
private val CardCornerRadius = 12.dp
private val CardPadding = 16.dp
private val CardContentSpacing = 12.dp
private val PrimaryActionHeight = 48.dp

/** 必须显著大于网络抖动周期，否则一收一放仍可见 */
private const val ONBOARDING_ANIM_MS = 300

/** 收起侧去抖窗口，约等于动画时长 */
private const val ONBOARDING_EXIT_DEBOUNCE_MS = 300L

/** 当前步是第一个未完成项，之前的按顺序算完成 */
enum class OnboardingStep {
    AR_ENGINE,

    CALIBRATION,

    VTS,

    /** 三步就绪，引导卡收起 */
    DONE,
}

/** 由三个完成标记推导当前步骤；vtsInFlight 为 true 时钉在 VTS，避免瞬时抖动推到 DONE */
fun currentOnboardingStep(
    arReady: Boolean,
    calibrated: Boolean,
    vtsConnected: Boolean,
    vtsInFlight: Boolean = false,
): OnboardingStep = when {
    !arReady -> OnboardingStep.AR_ENGINE
    !calibrated -> OnboardingStep.CALIBRATION
    // 握手期不允许 vtsConnected 瞬时抖动把卡片推到 DONE
    vtsInFlight -> OnboardingStep.VTS
    !vtsConnected -> OnboardingStep.VTS
    else -> OnboardingStep.DONE
}

/** 单步展示的引导卡，三步全完成后整卡收起 */
@Composable
fun OnboardingStepCard(
    step: OnboardingStep,
    onCheckAr: () -> Unit,
    onStartCalibration: () -> Unit,
    onAutoDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 去抖只作用于收起：持续 DONE 超过窗口才收起，展开永远即时
    var visible by remember { mutableStateOf(step != OnboardingStep.DONE) }
    LaunchedEffect(step) {
        if (step == OnboardingStep.DONE) {
            delay(ONBOARDING_EXIT_DEBOUNCE_MS)
            visible = false
        } else {
            // 先写 true 再进动画，AnimatedVisibility 从当前进度续上不跳帧
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        // 300ms + FastOutSlowInEasing，比默认 200ms 慢一档，抹平 1~2 帧的状态翻转
        enter = expandVertically(animationSpec = tween(ONBOARDING_ANIM_MS)) +
            fadeIn(animationSpec = tween(ONBOARDING_ANIM_MS)),
        exit = shrinkVertically(animationSpec = tween(ONBOARDING_ANIM_MS)) +
            fadeOut(animationSpec = tween(ONBOARDING_ANIM_MS)),
        modifier = modifier,
    ) {
        val (titleRes, bodyRes, actionRes, onAction) = when (step) {
            OnboardingStep.AR_ENGINE -> OnboardingAction(
                R.string.onboard_step_ar_title,
                R.string.onboard_step_ar_body,
                R.string.onboard_action_check_ar,
                onCheckAr,
            )
            OnboardingStep.CALIBRATION -> OnboardingAction(
                R.string.onboard_step_calib_title,
                R.string.onboard_step_calib_body,
                R.string.onboard_action_calibrate,
                onStartCalibration,
            )
            OnboardingStep.VTS -> OnboardingAction(
                R.string.onboard_step_vts_title,
                R.string.onboard_step_vts_body,
                R.string.onboard_action_discover,
                onAutoDiscover,
            )
            // DONE 不可达，when 需穷尽
            OnboardingStep.DONE -> OnboardingAction(
                R.string.onboard_step_vts_title,
                R.string.onboard_step_vts_body,
                R.string.onboard_action_discover,
                onAutoDiscover,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CardCornerRadius),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.40f),
            ),
        ) {
            Column(modifier = Modifier.padding(CardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboard_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.70f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                StepProgressDots(current = step)
                Spacer(Modifier.height(CardContentSpacing))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(CardContentSpacing))
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryActionHeight),
                ) {
                    Text(stringResource(actionRes))
                }
            }
        }
    }
}

/** 当前步为 22dp 长条，其余 8dp 圆点 */
@Composable
private fun StepProgressDots(current: OnboardingStep) {
    val currentIndex = when (current) {
        OnboardingStep.AR_ENGINE -> 0
        OnboardingStep.CALIBRATION -> 1
        OnboardingStep.VTS -> 2
        OnboardingStep.DONE -> 3
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val active = index <= currentIndex
            val isCurrent = index == currentIndex
            Surface(
                shape = RoundedCornerShape(50),
                color = if (active) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
                },
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(width = if (isCurrent) 22.dp else 8.dp, height = 8.dp),
            ) {}
        }
    }
}

private data class OnboardingAction(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val onAction: () -> Unit,
)

/** 等待授权高亮卡，不受 tracking/vtsConnected 门控 */
@Composable
fun VtsApprovalCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.60f),
        ),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vts_approval_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(CardContentSpacing))
            Text(
                text = stringResource(R.string.vts_approval_body),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.60f),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.vts_approval_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.80f),
                )
            }
        }
    }
}
