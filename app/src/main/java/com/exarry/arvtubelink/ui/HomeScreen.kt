package com.exarry.arvtubelink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R

/**
 * 「图标+文字」按钮内容的视觉补偿：图标在左时文字视觉重心偏右，整体左移微调。
 * TODO(真机微调)：仍偏右改 -3/-4，过偏左改 -1/0。只动绘制坐标，不影响布局/触摸区域。
 */
private val ButtonContentOffset = (-2).dp

/**
 * 首页间距/尺寸常量（真机微调在此改，勿散落魔法数字）。
 */
/** AR 状态文字→「检查 AR Engine」按钮。 */
private val StatusToButtonSpacing = 16.dp
/** AR 组三按钮之间。 */
private val ArButtonSpacing = 18.dp
/** VTS 组两按钮之间（次操作区隔）。 */
private val VtsButtonSpacing = 18.dp
/** AR 组按钮高（主操作，48dp 触控目标达标）。 */
private val PrimaryButtonHeight = 48.dp
/** VTS 组按钮高（矮一档防误操作；m3 minimumInteractiveComponentSize 仍扩触摸区到 48dp）。 */
private val SecondaryButtonHeight = 40.dp
/** 分割线→输入框额外间距（分割线自身 vertical 8dp + 本值 = 总距 16dp）。 */
private val DividerToInputSpacing = 8.dp
/** 输入框行→「连接 VTS」按钮。 */
private val InputToButtonSpacing = 16.dp

/** 引导卡圆角（比设置卡 12dp 略大，作为首页最高优先级的独立卡面）。 */
private val GuideCardCornerRadius = 14.dp
/** 引导卡标题行→正文。 */
private val GuideCardTitleSpacing = 10.dp
/** 引导卡正文→主按钮。 */
private val GuideCardBodySpacing = 14.dp
/** 引导卡主按钮→「稍后」文本按钮。 */
private val GuideCardLaterSpacing = 4.dp

/**
 * 首页 Tab 内容：AR 状态 + 检查/开始/停止 + IP/端口输入 + 连接 VTS + 清除 Token + VTS 状态。
 *
 * 红线：本文件不得持有 GLSurfaceView/AndroidView——GL 表面常驻 AppRoot 根层
 * （见 AppRoot.kt KDoc），挪进 Tab 内容会在切 Tab 时销毁 SurfaceView（EGL context 丢失）。
 *
 * 已知行为：Tab 切走再切回，本页滚动位置丢失（Compose 默认行为，已接受）；
 * ip/port 文本由 AppRoot 级 remember 持有（经 [ipState]/[portState] 传入），跨 Tab 保留。
 *
 * 按钮图标（Search/Send/Warning，见 [IconTextContent]）+ AR/VTS 组间分割线 + 清除 Token
 * 确认框（[ClearTokenConfirmDialog]）。开始/停止追踪不加图标：Stop 不在 material-icons-core
 * 集（javap 实证），单边加 PlayArrow 不对称，不引入 icons-extended。
 */
@Composable
fun HomeContent(
    arStatus: String,
    vtsStatus: String,
    /** 参数自适应进度行（null = 不显示）；显示/文案拼接见 MainActivity.onAdaptationState。 */
    adaptationStatus: String?,
    tracking: Boolean,
    /** 是否显示首次校准引导卡（MainActivity 用 calibrationDone + 忽略标记共同求值）。 */
    showCalibrationGuide: Boolean,
    ipState: MutableState<String>,
    portState: MutableState<String>,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConnectVts: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
    /** 引导卡「开始校准」：未追踪时由 MainActivity 自动开追踪再拉起对准视图。 */
    onStartCalibration: () -> Unit,
    /** 引导卡「稍后」：写 appPrefs，本次冷启动周期内不再显示。 */
    onDismissCalibrationGuide: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 首次校准引导卡：置于 AR/推流控制区之上，即推流卡片上方。
        // 可见性由 MainActivity 求值后经 showCalibrationGuide 传入，本组件不做判断，
        // 保持纯展示（与 SettingsContent 的状态上收约定一致）。
        if (showCalibrationGuide) {
            CalibrationGuideCard(
                onStartCalibration = onStartCalibration,
                onDismiss = onDismissCalibrationGuide,
            )
        }
        ArControlPanel(
            status = arStatus,
            tracking = tracking,
            onCheckAr = onCheckAr,
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
        )
        // AR 按钮组与 VTS 按钮组之间的分割线（色走主题 outlineVariant = colors.xml colorOutline）
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        VtsControlPanel(
            status = vtsStatus,
            ipState = ipState,
            portState = portState,
            onConnect = onConnectVts,
            onClearToken = onClearToken,
        )
        // 参数自适应进度行：探测期间显示（样式与 VTS 状态行同款），结束/取消清 null 隐藏
        adaptationStatus?.let { text ->
            Text(
                text = text,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * 首次校准引导卡（首页顶部，推流/AR 控制区之上）。
 *
 * 为什么需要它：新用户装好后首页没有任何提示，必须自己摸到设置 Tab 才会发现「校准」，
 * 于是带着未校准的偏移直接推流，第一印象就是「追踪不准」。
 *
 * 版式：高亮容器色（primaryContainer）+ 标题 + 正文 + 主按钮（开始校准）+ 文本按钮（稍后）。
 * 纯展示组件：可见性、忽略落盘、校准拉起全部在 MainActivity，本组件只负责画和转发点击。
 * 「稍后」只隐藏本次会话（冷启动会重现）——用文本按钮而非叉号，降低误触丢失引导的概率，
 * 同时比叉号更明确表达「不是永久关闭」。
 *
 * 间距常量见文件顶部 [GuideCardInnerSpacing] 等，真机微调在此改。
 */
@Composable
private fun CalibrationGuideCard(
    onStartCalibration: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(GuideCardCornerRadius),
        color = MaterialTheme.colorScheme.primaryContainer,
        // 高亮卡需要与背景拉开层级：容器色已够，加一层语义性描边便于深色下辨识边界
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null, // 装饰性：语义由标题承担
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
            Spacer(Modifier.height(GuideCardTitleSpacing))
            Text(
                text = stringResource(R.string.calib_guide_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(GuideCardBodySpacing))
            Button(
                onClick = onStartCalibration,
                modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
            ) {
                Text(stringResource(R.string.calib_guide_action_start))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = GuideCardLaterSpacing),
            ) {
                Text(
                    text = stringResource(R.string.calib_guide_action_later),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * AR 区：状态文字 + 检查/开始/停止。开始/停止的可用性由 tracking 派生。
 * 间距/尺寸见上方常量。
 */
@Composable
private fun ArControlPanel(
    status: String,
    tracking: Boolean,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = status,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = StatusToButtonSpacing),
        )
        Column(verticalArrangement = Arrangement.spacedBy(ArButtonSpacing)) {
            Button(
                onClick = onCheckAr,
                modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
            ) {
                IconTextContent(icon = Icons.Filled.Search, text = stringResource(R.string.action_check_ar))
            }
            Button(
                onClick = onStartTracking,
                enabled = !tracking,
                modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
            ) {
                Text(stringResource(R.string.action_start_tracking))
            }
            Button(
                onClick = onStopTracking,
                enabled = tracking,
                modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
            ) {
                Text(stringResource(R.string.action_stop_tracking))
            }
        }
    }
}

/**
 * VTS 区：IP/端口一行 + 连接/清除 Token + 状态文字。
 * ip/port 文本由 AppRoot 级 remember 持有：Tab 互切保留，Activity 重建回默认值。
 * 端口/IP 的校验与 Toast 在 MainActivity.connectVts。
 * 清除 Token 先弹 [ClearTokenConfirmDialog] 确认再回调。
 * 间距/尺寸：分割线→输入框 +[DividerToInputSpacing]（分割线自带 8dp，本值 8dp，合计 16dp）、
 * 输入框→按钮 [InputToButtonSpacing]、两按钮间 [VtsButtonSpacing]、按钮高 [SecondaryButtonHeight]
 * （比 AR 组矮一档防误操作）；状态文字色 onSurfaceVariant（与按钮区分）。
 */
@Composable
private fun VtsControlPanel(
    status: String,
    ipState: MutableState<String>,
    portState: MutableState<String>,
    onConnect: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = DividerToInputSpacing),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ipState.value,
                onValueChange = { ipState.value = it },
                label = { Text(stringResource(R.string.hint_vts_ip)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = portState.value,
                onValueChange = { portState.value = it },
                label = { Text(stringResource(R.string.hint_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(110.dp),
            )
        }
        Column(
            modifier = Modifier.padding(top = InputToButtonSpacing),
            verticalArrangement = Arrangement.spacedBy(VtsButtonSpacing),
        ) {
            Button(
                onClick = { onConnect(ipState.value, portState.value) },
                modifier = Modifier.fillMaxWidth().height(SecondaryButtonHeight),
            ) {
                IconTextContent(icon = Icons.AutoMirrored.Filled.Send, text = stringResource(R.string.action_connect_vts))
            }
            Button(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth().height(SecondaryButtonHeight),
            ) {
                IconTextContent(icon = Icons.Filled.Warning, text = stringResource(R.string.action_clear_token))
            }
        }
        Text(
            text = status,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            // 与按钮色区分；走 colorScheme 槽随主题切换
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = VtsButtonSpacing, bottom = 16.dp),
        )
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
 * 按钮内「图标 + 间距 + 文字」内容：三按钮共用，整体带 [ButtonContentOffset]
 * 视觉补偿。间距自管——m3 Button 的 content 是普通 RowScope，无自动 spacedBy。
 *
 * 居中方案取舍：
 *  - 内容偏右不是布局没居中（默认已水平居中），是「图标 + 间距」把文字视觉重心推右，
 *    加 Arrangement.Center 无效；
 *  - 右侧补 Spacer 对称补偿会让按钮右侧明显空洞，故用 offset 只动绘制坐标，
 *    不动测量/布局/触摸区域，可按 [ButtonContentOffset] 处注释真机 1dp 步进再调。
 */
@Composable
private fun IconTextContent(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.offset(x = ButtonContentOffset),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // 装饰性：语义由文字承担（无障碍读文字即可）
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * 清除 Token 确认框：m3 AlertDialog，位于 AppRoot 的 ARVTubeLinkTheme 内，
 * 自动继承深色色板。
 * 布局：icon 居中（m3 icon 槽天然居中）+ 标题/正文居中（TextAlign.Center + fillMaxWidth，
 * m3 默认左对齐）+ 按钮右下（m3 默认）。
 * 确认按钮 colorError 表达危险操作（colors.xml 单一来源）；TODO(真机)：刺眼/看不清 → 换 colorPrimary。
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
