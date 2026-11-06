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

// 版式常量：与 HomeScreen / SettingsCard 的私有值保持一致，但这里必须是本文件私有副本——
// 那两处的同名常量是 private，跨文件不可见。三处同值属有意冗余（卡片圆角/内边距是
// 视觉规范而非可配置项），若将来要统一，正确做法是提到共享文件并删掉这三份副本。
private val CardCornerRadius = 12.dp
private val CardPadding = 16.dp
private val CardContentSpacing = 12.dp
private val PrimaryActionHeight = 48.dp

/** 引导卡展开/收起动画时长（ms）。见 [OnboardingStepCard] 里对「必须大于抖动周期」的说明。 */
private const val ONBOARDING_ANIM_MS = 300

/**
 * 收起侧去抖窗口（ms）：必须持续为 DONE 这么久才真正收起。
 * 取 300ms ≈ 动画时长，保证「一收一放」至少需要 600ms 的状态稳定偏离才能被用户看见。
 */
private const val ONBOARDING_EXIT_DEBOUNCE_MS = 300L

/**
 * 新手全流程引导（v1.2.6）。
 *
 * 设计取舍：**不用 ViewModel**。本项目是单 Activity + Compose 状态提升架构，
 * 三步的完成度分别来自三个已有真源（MainActivity 的 arReady / calibrationDone /
 * vtsConnected），全部经 AppRoot 传入 HomeContent。再引一层 ViewModel 只会把
 * 同一份状态复制成第二真源，反而制造失同步风险——所以这里用纯函数从既有布尔
 * 派生 [OnboardingStep]，无内部状态、可预览、可单测。
 *
 * 三步串行：当前步是第一个未完成项，之前的一律算完成（用户可能跳过检查直接校准，
 * 但流程语义上仍按顺序呈现，避免「步骤 2 未完成却显示步骤 3」的错乱感）。
 */
enum class OnboardingStep {
    /** 步骤 1：AR Engine 尚未确认可用。 */
    AR_ENGINE,

    /** 步骤 2：尚未完成零点校准。 */
    CALIBRATION,

    /** 步骤 3：尚未连接 VTS。 */
    VTS,

    /** 三步都已就绪：引导卡整体收起，露出正常推流界面。 */
    DONE,
}

/**
 * 从三个已完成标记推导当前应展示的步骤。
 *
 * **为什么要额外传 [vtsInFlight]（v1.2.6 抽搐修复的核心）**：
 * 原先只用 `vtsConnected`（= `state is Authenticated`）推导，而点「连接 VTS」后状态机走
 * `Disconnected -> Connecting -> WaitingForTokenApproval -> Authenticating -> Authenticated`，
 * 全程只有最后一跳才为 true。于是在 `Connecting` 期间 `!vtsConnected` 仍成立、卡片看似
 * 不变——**但真正的抽搐来自反向路径**：任何一次 `onFailure/onClosed` 把状态复位成
 * `Disconnected`、或 `AuthenticationFailed` 后的 `scheduleReconnect`，都会让
 * `Authenticated -> Disconnected` 翻转一次；而 `DONE` 只在这一个布尔上成立，
 * 于是一旦连接曾短暂成功又断开，卡片就是「收起 → 又弹出」。
 *
 * 更致命的是它把「正在握手」和「根本没开始」当成同一件事：用户点连接后，卡片按
 * `!vtsConnected` 判定仍停在 VTS 步，这本身没错；但配合 [AnimatedVisibility] 的
 * `visible = step != DONE`，只要中间任何一次状态抖动让 `vtsConnected` 短暂为 true，
 * 卡片就会 `shrinkVertically + fadeOut` 收起，紧接着状态回退又重新展开——一收一放
 * 就是用户看到的「弹出又缩回去抽搐一下」。
 *
 * 修复：把「握手进行中」与「已认证」显式区分开。只要状态机处于
 * `Connecting / WaitingForTokenApproval / Authenticating / Reconnecting`（即 [vtsInFlight]），
 * 就**一律视为步骤 3 仍在进行**，绝不让它跨到 [OnboardingStep.DONE]。这样：
 *  - 连接中：卡片稳定停在 VTS 步，不收起；
 *  - 认证成功：`vtsConnected=true` 且 `vtsInFlight=false` → 才进 DONE 收起；
 *  - 认证失败/重连中：`vtsInFlight=true` → 卡片保留（正是用户需要看到重试入口的时候）。
 *
 * @param arReady AR Engine 是否已确认可用
 * @param calibrated 是否已完成零点校准
 * @param vtsConnected VTS 是否**已认证**（严格 = `state is Authenticated`）
 * @param vtsInFlight VTS 是否正处于握手过程（Connecting/WaitingForTokenApproval/
 *   Authenticating/Reconnecting 之一）。为 true 时无视 [vtsConnected] 的瞬时抖动，
 *   强制把步骤钉在 [OnboardingStep.VTS]。
 */
fun currentOnboardingStep(
    arReady: Boolean,
    calibrated: Boolean,
    vtsConnected: Boolean,
    vtsInFlight: Boolean = false,
): OnboardingStep = when {
    !arReady -> OnboardingStep.AR_ENGINE
    !calibrated -> OnboardingStep.CALIBRATION
    // 握手进行中一律钉在步骤 3：不允许任何瞬时 vtsConnected 抖动把卡片推到 DONE
    vtsInFlight -> OnboardingStep.VTS
    !vtsConnected -> OnboardingStep.VTS
    else -> OnboardingStep.DONE
}

/**
 * 首页新手步骤卡：一次只展示「当前该做的那一步」，完成即自动推进到下一步，
 * 三步全完成后整卡收起（[AnimatedVisibility] 平滑让位）。
 *
 * 为什么单步展示而非三步并排：三步各自的说明与主操作都不同，并排会让首页
 * 在一屏内塞进三张卡，把真正的推流界面挤到折叠线以下——这与「引导是过渡态，
 * 不是常驻内容」的产品意图冲突。用一条进度点表达「还剩几步」足够。
 */
@Composable
fun OnboardingStepCard(
    step: OnboardingStep,
    onCheckAr: () -> Unit,
    onStartCalibration: () -> Unit,
    onAutoDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 收起侧去抖（v1.2.6 抽搐修复第二道防线）：
    // step 已经由 currentOnboardingStep 的 vtsInFlight 参数吸收掉「握手期抖动」，
    // 这里再挡一类情况——steps 全部完成的瞬间若正好赶上一次网络打嗝（状态短暂
    // 回到非 DONE），仍可能一收一放。用 [ONBOARDING_EXIT_DEBOUNCE_MS] 的保持窗口：
    // 只有「持续为 DONE」超过窗口才真正收起；窗口内回到非 DONE 就当作没发生过。
    // 关键：**去抖只作用于收起方向**，展开永远即时——引导内容晚出现是体验损失，
    // 而「该出现的内容被延迟」绝不会造成抽搐。
    var visible by remember { mutableStateOf(step != OnboardingStep.DONE) }
    LaunchedEffect(step) {
        if (step == OnboardingStep.DONE) {
            delay(ONBOARDING_EXIT_DEBOUNCE_MS)
            visible = false
        } else {
            // 非 DONE：立刻展开（先写 true 再进动画，AnimatedVisibility 会从当前
            // 动画进度平滑续上，不会跳帧）
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        // 300ms + FastOutSlowInEasing：比默认 expand/shrink 的 200ms 更慢一档。
        // 目的不是好看，而是把「状态在 1~2 帧内来回翻转」这类网络抖动彻底抹平——
        // 动画时长必须显著大于抖动周期，否则用户仍能看到一收一放的两个半程。
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
            // DONE 分支不可达（AnimatedVisibility 已拦住），但 when 必须穷尽
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
                        contentDescription = null, // 装饰性，语义由标题承担
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

/** 三步进度点：已过 = 实心，当前 = 拉长高亮，未到 = 空心。 */
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

/** [OnboardingStepCard] 的 when 分支产物，避免六个平行变量污染作用域。 */
private data class OnboardingAction(
    val titleRes: Int,
    val bodyRes: Int,
    val actionRes: Int,
    val onAction: () -> Unit,
)

/**
 * VTS 等待授权高亮卡（v1.2.6）。
 *
 * 为什么单独做一张卡而不是复用推流卡的警示行：`WaitingForTokenApproval` 是首次连接
 * **最高频的卡点**——用户以为「点了连接就该通」，实际必须去电脑屏幕上点 Allow。
 * 原始实现只在推流卡底部渲染一行 bodySmall 次级色文字，且该卡在未开始推流时甚至
 * 不显示（HomeScreen 里 `if (tracking || vtsConnected)`）——导致等待授权时用户
 * 最多看到状态栏一句「等待授权」，极易误判为卡死。
 *
 * 这里用 tertiary 容器色 + Warning 图标 + 正文说明，无条件（不受 tracking/vtsConnected
 * 门控）插在首页顶部区域，确保「等待授权」这个状态永远有醒目出口。
 */
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
                    contentDescription = null, // 装饰性，语义由标题承担
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
                    contentDescription = null, // 装饰性
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
