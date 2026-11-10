package com.exarry.arvtubelink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.network.VtsDiscoveryClient
import com.exarry.arvtubelink.ui.theme.accentColor

private val PagePadding = 16.dp

private val SectionSpacing = 12.dp

private val CardContentSpacing = 12.dp

private val CardPadding = 16.dp

private val StatusIconSize = 18.dp

private val PrimaryActionHeight = 48.dp

private val CardCornerRadius = 12.dp

private val StatusDotSize = 7.dp

private const val ONBOARDING_ANIM_MS = 300

/** 与 OutlinedTextField 最小高 56dp 对齐 */
private val AutoDiscoverButtonSize = 56.dp

sealed interface DiscoveryUiState {
    data object Idle : DiscoveryUiState

    /** 前 3s：UDP 广播监听 */
    data object Searching : DiscoveryUiState

    /** UDP 无响应后转 TCP 端口扫描 */
    data object Scanning : DiscoveryUiState

    /** autoConnecting=false 表示对端 API 未开启 */
    data class Found(val ip: String, val port: Int, val autoConnecting: Boolean) : DiscoveryUiState

    data class Failed(val reason: VtsDiscoveryClient.DiscoveryResult.Reason) : DiscoveryUiState
}

/** GL 表面常驻 AppRoot，挪进来切 Tab 会销毁 EGL context */
@Composable
fun HomeContent(
    arStatus: String,
    arReady: Boolean,
    adaptationStatus: String?,
    tracking: Boolean,
    showCalibrationGuide: Boolean,
    ipState: MutableState<String>,
    portState: MutableState<String>,
    discoveryState: DiscoveryUiState,
    onAutoDiscover: () -> Unit,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConnectVts: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
    onStartCalibration: () -> Unit,
    onDismissCalibrationGuide: () -> Unit,
    /** AppRoot 每秒采样一次 */
    telemetry: TelemetryState,
    configuredHz: Int,
    vtsConnected: Boolean,
    vtsInFlight: Boolean = false,
    vtsWarning: String? = null,
    vtsWarningIsError: Boolean = false,
    vtsAwaitingApproval: Boolean = false,
    calibrated: Boolean = false,
) {
    val onboardingStep = currentOnboardingStep(
        arReady = arReady,
        calibrated = calibrated,
        vtsConnected = vtsConnected,
        vtsInFlight = vtsInFlight,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        // 300ms 展开/收起插值
        AnimatedVisibility(
            visible = vtsAwaitingApproval,
            enter = expandVertically(animationSpec = tween(ONBOARDING_ANIM_MS)) +
                fadeIn(animationSpec = tween(ONBOARDING_ANIM_MS)),
            exit = shrinkVertically(animationSpec = tween(ONBOARDING_ANIM_MS)) +
                fadeOut(animationSpec = tween(ONBOARDING_ANIM_MS)),
        ) {
            VtsApprovalCard()
        }

        OnboardingStepCard(
            step = onboardingStep,
            onCheckAr = onCheckAr,
            onStartCalibration = onStartCalibration,
            onAutoDiscover = onAutoDiscover,
        )

        // 步骤卡停在 CALIBRATION 时独占，隐藏旧引导卡
        if (showCalibrationGuide && onboardingStep != OnboardingStep.CALIBRATION) {
            CalibrationGuideCard(
                onStartCalibration = onStartCalibration,
                onDismiss = onDismissCalibrationGuide,
            )
        }

        ControlCard(
            arStatus = arStatus,
            arReady = arReady,
            tracking = tracking,
            onCheckAr = onCheckAr,
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
        )

        VtsConnectCard(
            ipState = ipState,
            portState = portState,
            discoveryState = discoveryState,
            onAutoDiscover = onAutoDiscover,
            onConnect = onConnectVts,
            onClearToken = onClearToken,
        )

        if (tracking || vtsConnected) {
            PushStatusCard(
                telemetry = telemetry,
                tracking = tracking,
                configuredHz = configuredHz,
                vtsConnected = vtsConnected,
                vtsWarning = vtsWarning,
                vtsWarningIsError = vtsWarningIsError,
            )
        }

        adaptationStatus?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SectionSpacing),
            )
        }
    }
}

@Composable
private fun ControlCard(
    arStatus: String,
    arReady: Boolean,
    tracking: Boolean,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(CardContentSpacing),
        ) {
            if (!tracking) {
                StatusIndicatorRow(
                    arStatus = arStatus,
                    arReady = arReady,
                )
            }

            OutlinedButton(
                onClick = onCheckAr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonIconText(
                    icon = Icons.Filled.Search,
                    text = stringResource(R.string.action_check_ar),
                )
            }
            if (tracking) {
                Button(
                    onClick = onStopTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryActionHeight),
                ) {
                    ButtonIconText(
                        // material-icons-core 无 Stop，用 Clear 替代
                        icon = Icons.Filled.Clear,
                        text = stringResource(R.string.action_stop_tracking),
                    )
                }
            } else {
                Button(
                    onClick = onStartTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryActionHeight),
                ) {
                    ButtonIconText(
                        icon = Icons.Filled.PlayArrow,
                        text = stringResource(R.string.action_start_tracking),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicatorRow(
    arStatus: String,
    arReady: Boolean,
) {
    StatusIndicatorLine(
        label = stringResource(R.string.push_chip_ar),
        value = arStatus,
        dotColor = if (arReady) accentColor() else MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun StatusIndicatorLine(
    label: String,
    value: String,
    dotColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(StatusDotSize)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$label：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VtsConnectCard(
    ipState: MutableState<String>,
    portState: MutableState<String>,
    discoveryState: DiscoveryUiState,
    onAutoDiscover: () -> Unit,
    onConnect: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(CardContentSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = ipState.value,
                    onValueChange = { ipState.value = it },
                    label = { Text(stringResource(R.string.hint_vts_ip)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = portState.value,
                    onValueChange = { portState.value = it },
                    label = { Text(stringResource(R.string.hint_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
                AutoDiscoverButton(
                    state = discoveryState,
                    onClick = onAutoDiscover,
                )
            }

            DiscoveryRadarBand(state = discoveryState)

            // 忙碌态文案由雷达带承担，这里只处理 Found/Failed
            when (val state = discoveryState) {
                is DiscoveryUiState.Found ->
                    if (state.autoConnecting) {
                        DiscoveryStatusText(
                            stringResource(R.string.discovery_status_found_connecting, state.ip)
                        )
                    } else {
                        DiscoveryStatusText(
                            stringResource(R.string.discovery_status_api_off, state.ip)
                        )
                    }
                is DiscoveryUiState.Failed ->
                    if (state.reason == VtsDiscoveryClient.DiscoveryResult.Reason.NO_NETWORK) {
                        DiscoveryStatusText(
                            text = stringResource(R.string.discovery_status_no_network),
                            isFailure = true,
                        )
                    } else {
                        DiscoveryStatusText(
                            text = stringResource(R.string.discovery_status_timeout),
                            isFailure = true,
                        )
                    }
                is DiscoveryUiState.Searching, is DiscoveryUiState.Scanning, DiscoveryUiState.Idle -> {}
            }

            Button(
                onClick = { onConnect(ipState.value, portState.value) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonIconText(
                    icon = Icons.AutoMirrored.Filled.Send,
                    text = stringResource(R.string.action_connect_vts),
                )
            }

            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonIconText(
                    icon = Icons.Filled.Warning,
                    text = stringResource(R.string.action_clear_token),
                )
            }
        }
    }

    if (showClearConfirm) {
        ClearTokenConfirmDialog(
            onConfirm = {
                showClearConfirm = false
                onClearToken()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** clickable 而非 IconButton，后者图标槽装不下两行 */
@Composable
private fun AutoDiscoverButton(
    state: DiscoveryUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state == DiscoveryUiState.Searching || state == DiscoveryUiState.Scanning
    val accent = accentColor()
    val autoDiscoverLabel = stringResource(R.string.action_auto_discover)

    Column(
        modifier = modifier
            .size(AutoDiscoverButtonSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = autoDiscoverLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(StatusIconSize), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(StatusIconSize),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(StatusIconSize),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.action_auto_discover_short),
            fontSize = 10.sp,
            color = if (busy) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (busy) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun CalibrationGuideCard(
    onStartCalibration: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.calib_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(CardContentSpacing))
            Text(
                text = stringResource(R.string.calib_guide_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(CardContentSpacing))
            Button(
                onClick = onStartCalibration,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PrimaryActionHeight),
            ) {
                Text(stringResource(R.string.calib_guide_action_start))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.calib_guide_action_later),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** M3 Button content 无自动 spacedBy */
@Composable
private fun ButtonIconText(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun ClearTokenConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.action_clear_token),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.token_clear_confirm_message),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_clear_token),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
