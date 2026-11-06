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

// ---------- 首页间距常量：真机微调集中改这里，别散成魔法数字 ----------

private val PagePadding = 16.dp

/** 卡片间/卡内大块间距：8dp 网格上浮一档到 12dp，视觉更透气。 */
private val SectionSpacing = 12.dp

private val CardContentSpacing = 12.dp

private val CardPadding = 16.dp

/** 状态图标 18dp：比 M3 列表项默认的 24dp 小，匹配 14sp 文字的视觉重量。 */
private val StatusIconSize = 18.dp

/** 主操作按钮 48dp：M3 标准 40dp 上浮，作为唯一主操作的体量。 */
private val PrimaryActionHeight = 48.dp

private val CardCornerRadius = 12.dp

/** 圆点 7dp，比推流卡 chip 的 6dp 大一档：这里裸放没有底板衬托。 */
private val StatusDotSize = 7.dp

/**
 * 首页卡片增删的过渡时长（ms）。与 OnboardingGuide 的 ONBOARDING_ANIM_MS 同值，
 * 但两处各自 private 定义——它们分属不同文件的不同视觉语境，将来可独立调整。
 */
private const val ONBOARDING_ANIM_MS = 300

/** 与 OutlinedTextField 最小高 56dp 等高，Row 居中对齐才不歪。 */
private val AutoDiscoverButtonSize = 56.dp

/**
 * 自动发现状态机（网络实现在 network/VtsDiscoveryClient.kt）。
 *
 * 转移：Idle/Found/Failed --点按钮--> Searching（UI 守卫防重入）--满 3s 无 UDP 结果-->
 * Scanning（延迟值引用 VtsDiscoveryClient.TCP_FALLBACK_DELAY_MS，防两处漂移）
 * --命中--> Found；--双路无果--> Failed。Found/Failed 为暂态，AppRoot 的
 * LaunchedEffect 数秒后清回 Idle。
 *
 * 「已发现并连接」Toast 不属于本状态机：发现成功不等于连接成功（授权弹窗/端口错/API
 * 关闭都会让连接失败），成败由 VtsState 表达，MainActivity 在 Authenticated 时消费
 * pending 标记再弹，避免误报。
 */
sealed interface DiscoveryUiState {
    /** 常态（自动发现状态行不显示）。 */
    data object Idle : DiscoveryUiState

    /** 搜索前 3s：UDP 广播监听中。 */
    data object Searching : DiscoveryUiState

    /** 3s 后：UDP 无响应，TCP 兜底端口扫描中（典型为路由器 AP 隔离/OEM 驱动过滤广播）。 */
    data object Scanning : DiscoveryUiState

    /** 命中：IP/端口已填入；autoConnecting=false = 对端 API 未开启，只填入并提示。 */
    data class Found(val ip: String, val port: Int, val autoConnecting: Boolean) : DiscoveryUiState

    /** 失败终局：NO_NETWORK 无可用局域网 / TIMEOUT 超时未发现。 */
    data class Failed(val reason: VtsDiscoveryClient.DiscoveryResult.Reason) : DiscoveryUiState
}

/**
 * 首页 Tab 内容。
 *
 * 版式自上而下：校准引导卡（条件显示）→ 控制区 → 连接区 → 推流卡（条件显示）→ 自适应进度行。
 *
 * 红线：本文件不得持有 GLSurfaceView/AndroidView。GL 表面常驻 AppRoot 根层，
 * 挪进 Tab 内容会在切 Tab 时销毁 SurfaceView，EGL context 丢失。
 *
 * 已知行为：Tab 互切丢失滚动位置（Compose 默认）；ip/port 由 AppRoot 持有，跨 Tab 保留。
 */
@Composable
fun HomeContent(
    arStatus: String,
    /** AR Engine 是否已确认可用（MainActivity 从 checkAvailability() 结果投影的布尔）。 */
    arReady: Boolean,
    /** 参数自适应进度行（null = 不显示）；显示/文案拼接见 MainActivity.onAdaptationState。 */
    adaptationStatus: String?,
    tracking: Boolean,
    /** 是否显示首次校准引导卡（MainActivity 用 calibrationDone + 忽略标记共同求值）。 */
    showCalibrationGuide: Boolean,
    ipState: MutableState<String>,
    portState: MutableState<String>,
    /** 自动发现状态与触发（AppRoot 持有状态机并上收副作用，见 DiscoveryUiState KDoc）。 */
    discoveryState: DiscoveryUiState,
    onAutoDiscover: () -> Unit,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConnectVts: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
    /** 引导卡「开始校准」：未追踪时由 MainActivity 自动开追踪再拉起对准视图。 */
    onStartCalibration: () -> Unit,
    /** 引导卡「稍后」：写 appPrefs，本次冷启动周期内不再显示。 */
    onDismissCalibrationGuide: () -> Unit,
    /** 推流遥测快照（ui/StreamTelemetry；AppRoot 每秒采样一次）。 */
    telemetry: TelemetryState,
    /** 用户配置的推流频率（仪表盘量程与参考虚线用）。 */
    configuredHz: Int,
    /** VTS 是否已认证连接（推流卡 chip 用）。 */
    vtsConnected: Boolean,
    /**
     * VTS 是否正处于握手过程（Connecting / WaitingForTokenApproval / Authenticating /
     * Reconnecting 之一）。用于把新手引导钉在步骤 3，避免瞬时状态抖动引发卡片抽搐，
     * 详见 [currentOnboardingStep] 的 vtsInFlight 参数说明。
     */
    vtsInFlight: Boolean = false,
    /** VTS 异常/等待态文案（null = 不显示；由 AppRoot 按 VtsState 条件求值后传入）。 */
    vtsWarning: String? = null,
    /** 该警示是否属于错误级（决定推流卡用 error 色还是次级文字色）。 */
    vtsWarningIsError: Boolean = false,
    /** VTS 是否正等待用户授权（v1.2.6：决定是否弹出醒目授权高亮卡）。 */
    vtsAwaitingApproval: Boolean = false,
    /** 是否已完成零点校准（新手引导步骤 2 的完成标记）。 */
    calibrated: Boolean = false,
) {
    // 新手引导当前步骤：三步全部完成即 DONE，卡片自动收起。
    // vtsInFlight：握手期一律钉在步骤 3（修复「点连接时卡片抽搐」，见 currentOnboardingStep）
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
        // 等待授权放在最顶部：这是首次连接最高频的卡点，必须第一眼看到。
        //
        // ★ v1.2.6 抽搐修复（第二处根因）：这里原来是裸 `if`。`WaitingForTokenApproval`
        //   在点击「连接 VTS」后 1 次网络往返内就到，于是这张卡会在**一帧之内**凭空
        //   插到步骤卡上方，把下面整列内容（含新手引导卡）向下顶掉一整张卡的高度。
        //   紧跟着 token 看门狗重发、或用户点了允许让状态跳走，卡片又被瞬间抽走、
        //   内容弹回原位——这一顶一弹正是用户描述的「弹出又缩回去抽搐一下」。
        //   改用 AnimatedVisibility 展开/收起，高度变化在 300ms 内连续插值，
        //   下面所有卡片的位移都变成平滑滑动而非瞬移。
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

        // 首次校准引导卡与新手步骤卡在「步骤 2（校准）」时内容重叠，同时出现会变成
        // 两张讲同一件事的卡。规则：步骤卡正停在 CALIBRATION 时由它独占（它还带
        // 全局进度感），此时隐藏旧引导卡；其余步骤下旧卡照旧（它多做一件事：
        // 支持「稍后」延后到下次启动，步骤卡无此语义）。
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

        // 未开始推流时展示一张全 0 的图表没有信息量
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

        // 参数自适应进度行：探测期间显示，结束/取消清 null 隐藏
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

/**
 * 控制区卡片：状态指示行 + 检查 AR Engine（[OutlinedButton]）+ 开始/停止追踪（[Button]）。
 *
 * 状态行只在未追踪时出现——追踪后由推流卡接管，避免同一信息说两遍。
 * 开始/停止不并排：两者互斥，并排会出现「一个可点一个灰」的割裂感。
 */
@Composable
private fun ControlCard(
    arStatus: String,
    /** AR 是否已确认可用（决定圆点颜色，见 [StatusIndicatorRow]）。 */
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
                        // material-icons-core 不含 Stop（已 javap 实证），用 Clear 表达"结束"
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

/**
 * 控制卡顶部状态行：`● AR：<状态>`。
 *
 * 只显示 AR：VTS 状态串自带「VTS：」前缀，再套标签会重复；且 VTS 属网络连接，
 * 由连接区和推流卡承担。AR 引擎是否可用直接决定「开始追踪」点了有没有反应，
 * 放在按钮上方是就近解释。
 *
 * [arReady] 与 [tracking] 分开传：本行只在 `!tracking` 时渲染，用 tracking 当判据
 * 会恒为灰点。arReady 表达"检查过且可用"，未检查时保持中性灰。
 */
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

/**
 * 单条状态行：`● 标签：值`。
 * Alignment.Top + 圆点顶偏移是为了对齐文字首行——CenterVertically 在长句折行时
 * 会把圆点甩到整块中间。
 */
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

/**
 * VTS 连接区卡片：IP/端口输入 + 行尾自动发现 + 连接 VTS + 清除 Token + 发现状态。
 *
 * 校验与 Toast 在 MainActivity.connectVts，自动发现逻辑在 AppRoot.runAutoDiscover，
 * 本组件纯展示 + 转发。
 */
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

            // 发现结果行：忙碌态文案已由雷达带承担，这里只管 Found/Failed
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

/**
 * 局域网发现按钮：垂直排布，上方搜索图标、下方 "LAN"。
 *
 * 图标用 Search 而非 Refresh（v1.2.6）：语义是"在局域网里找 VTS"，不是"重新加载"；
 * 文字同步由 "AUTO" 改为 "LAN"，与图标一起把"发现方式 = 局域网扫描"讲清楚。
 *
 * 56dp 高与左侧 [OutlinedTextField] 对齐。用 [Modifier.clickable] 而非 IconButton：
 * 后者自带圆形水波纹与固定图标槽位，装不下两行内容。
 *
 * 忙碌态用 18dp 转圈替换图标（同尺寸，文字不跳动），主反馈仍由 [DiscoveryRadarBand] 承担。
 *
 * "LAN" 是缩写（局域网发现），故给整个 Column 挂 contentDescription 为完整功能名，供读屏使用。
 */
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
        // 固定 18dp 图标位：转圈与图标同尺寸，切换时文字不跳动
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

/**
 * 首次校准引导卡：新用户不校准就推流，第一印象是「追踪不准」，故置于首页顶部强提示。
 *
 * 全页唯一的强调卡：primaryContainer 实底 + 1dp primary 描边（深色下容器色接近背景时
 * 靠描边咬住边界），与下方 ElevatedCard 功能卡区分。前景色统一取 onPrimaryContainer 系，
 * 与容器成对保证 M3 对比度；正文/次要文字降透明度做层次。
 * 别把它改回 ElevatedCard（丢强调），也别把功能卡改成 primaryContainer（全部高亮等于没有高亮）。
 *
 * 纯展示组件：可见性、忽略落盘、校准拉起都在 MainActivity。
 * 「稍后」只隐藏本次会话（冷启动重现）。
 */
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
                    contentDescription = null, // 装饰性，语义由标题承担
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

/** 按钮内「图标 + 文字」：M3 Button 的 content 无自动 spacedBy，间距要手动加。 */
@Composable
private fun ButtonIconText(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null, // 装饰性，语义由文字承担
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * 清除 Token 确认框。继承外层 ARVTubeLinkTheme 色板，无需显式传 colors。
 * 确认按钮用 error 色表达危险操作；真机若刺眼可换 primary。
 */
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
