package com.exarry.arvtubelink.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.mapping.BlendShapeMapper
import com.exarry.arvtubelink.mapping.CalibrationData
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.mapping.CalibrationStorage
import com.exarry.arvtubelink.mapping.SensitivityConfig
import com.exarry.arvtubelink.mapping.Smoother
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** 应用级设置 prefs 的 Key（MainActivity 启动同步也引用）。 */
internal const val KEY_ENABLE_DEBUG_LOGS = "enable_debug_logs"

/** 1€ 滤波器开关的 SharedPreferences Key（默认开启）。 */
internal const val KEY_ENABLE_ONE_EURO_FILTER = "enable_one_euro_filter"

/**
 * 参数自适应开关的 SharedPreferences Key（默认开启）。
 */
internal const val KEY_ENABLE_ADAPTATION = "enable_adaptation"

// ---------- 手动映射范围（app_settings 扩展键） ----------
// 手动值独立于 adaptation_prefs（探测结果专属，互不覆盖）；应用规则互斥：
// probeOverridesActive（探测值生效中）时手动区禁用，OFF/Failed 时手动值应用。
// 未设过的键不参与覆盖（MainActivity 侧 contains 判空 → MapperOverrides 对应字段 null）。

/** MouthSmile 中性点（Float 0~1，默认 0.5；直接对应 MapperOverrides.mouthSmileNeutralBase）。 */
internal const val KEY_MANUAL_MOUTHSMILE_NEUTRAL = "manual_mouthsmile_neutral"

/** 眉毛灵敏度倍率（Float 0.5~2.0，默认 1.0；应用时换算 browDeviceMax = 1.0f / 倍率）。 */
internal const val KEY_MANUAL_BROW_SENSITIVITY = "manual_brow_sensitivity"

/** 说话灵敏度倍率（Float 0.5~2.0，默认 1.0；应用时换算 mouthOpenDeviceMax = 0.30f / 倍率）。 */
internal const val KEY_MANUAL_MOUTHOPEN_SENSITIVITY = "manual_mouthopen_sensitivity"

/**
 * 脸部镜像开关的 SharedPreferences Key（Boolean，默认 false；存 app_settings，
 * 与 [KEY_ENABLE_ADAPTATION] 同级）。开启后 mapper 对所有出网参数做左右镜像
 * （见 BlendShapeMapper.applyFaceMirror），全局语义、不属于手动映射范围。
 *
 * 注：原「MouthX 方向翻转」的 `manual_mouthx_invert` 已随 UI 移除
 * （MapperOverrides.mouthXInvert 字段保留作底层兜底，当前无写入点）。
 */
internal const val KEY_FACE_MIRROR = "face_mirror"

/** 主题开关 Key：ON = 跟随系统（默认）/ OFF = 强制深色。 */
internal const val KEY_THEME_FOLLOW_SYSTEM = "theme_follow_system"

/** AMOLED 纯黑背景 Key（默认关闭；仅深色模式下生效，浅色下开启无视觉效果）。 */
internal const val KEY_THEME_AMOLED_BLACK = "theme_amoled_black"

/** VTS 地址记忆 Key：上次输入的 IP / 端口（MainActivity 启动恢复、AppRoot 输入回写）。 */
internal const val KEY_VTS_IP = "vts_ip"
internal const val KEY_VTS_PORT = "vts_port"

/** 推流频率 Key：发给 VTS 的节流频率档位（Int，10/20/30/40/50/60 Hz）。 */
internal const val KEY_SEND_FREQUENCY_HZ = "send_frequency_hz"

/** 推流频率默认档（与 FaceRenderer 初始节流间隔 1000/30ms 对齐）。 */
internal const val DEFAULT_SEND_FREQUENCY_HZ = 30

/** 关于卡在 LazyColumn 中的 item 索引（第 5 张卡；新增卡片需同步更新）。 */
private const val AboutCardIndex = 4

/** 设置页依赖集（纯数据，5 项，由 MainActivity 构造并经 AppRoot 传入；原设置弹窗的 deps）。
 *  appPrefs 亦被 AppRoot 用于首页 VTS 地址记忆的输入回写（KEY_VTS_IP/KEY_VTS_PORT）。 */
data class SettingsDeps(
    val mapper: BlendShapeMapper,
    val smoother: Smoother,
    val calibrationStorage: CalibrationStorage,
    val appPrefs: SharedPreferences,
    /** 手动映射值独立 prefs（决策 2：adaptation_manual_prefs，与自适应 overrides 分离互不覆盖）。 */
    val adaptationManualPrefs: SharedPreferences,
    val faceRenderer: FaceRenderer
)

/**
 * 设置页 Tab 内容：五张 [SettingsCard]——校准与灵敏度 / 推流 / 主题 / 开发者 / 关于。
 * 新增卡片 = 再写一个 SettingsCard 调用（item 顺序变化时同步 [AboutCardIndex]）。
 *
 * 状态策略：mapper 的系数/校准状态是 @Volatile 跨线程值，Compose
 * 快照系统无法感知其变化——Tab 每次进入组合用 remember 读一次 mapper/prefs 最新值进本地
 * state；UI 交互只改本地 state 并同时写透 mapper（GL 侧读到的系数恒等于 UI 显示值）；
 * Tab 切走即组合销毁，下次进入重读最新值。
 *
 * 主题开关：本地 remember 读 prefs，切换经
 * [onThemeFollowSystemChange] 上收 MainActivity（写 state + prefs 落盘）；语义 ON=跟随系统 /
 * OFF=强制深色；点击坐标（Switch 中心 root 坐标）先行经 [onThemeRevealOrigin] 上收 AppRoot
 * （AppRoot 本地 state 自持，MainActivity 零改动）。
 *
 * 滚到关于：顶栏 info（首页）跳设置页时 [aboutScrollSignal] 递增，LaunchedEffect
 * 检测后滚到 [AboutCardIndex] 并经 [onAboutScrollConsumed] 清零——防止之后手动切回误滚。
 *
 * 滚动位置：LazyListState 用 rememberSaveable + LazyListState.Saver——Tab 互切
 * 销毁组合时滚动位置保存/恢复，不再回顶。
 *
 * 持久化：灵敏度 onValueChangeFinished 落盘（Dispatchers.IO，不占主线程）；开关切换即时落盘。
 * Theme：色板由 AppRoot 外层 ARVTubeLinkTheme 统一提供（colors.xml 单一来源，深浅两套限定符）。
 * Toast 的 Context 用 [LocalContext]，不为 Toast 给 SettingsDeps 加 activity 字段。
 * 用户可见文字一律走 stringResource，不硬编码中文。
 */
@Composable
fun SettingsContent(
    deps: SettingsDeps,
    aboutScrollSignal: Int,
    onAboutScrollConsumed: () -> Unit,
    onThemeFollowSystemChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onThemeRevealOrigin: (Offset) -> Unit,
    onCalibrateRequested: () -> Unit,
    /** 重置校准后通知 Activity：首页引导卡应重新出现（校准回到未完成态）。 */
    onCalibrationReset: () -> Unit,
    /** 探测值生效中（开关 ON 且最近 Done/缓存命中）：手动映射区禁用，见 MainActivity 真源。 */
    adaptationProbeActive: Boolean,
    /** 自适应开关切换（副作用在 MainActivity：ON 复用触发链 / OFF 取消+应用手动值）。 */
    onAdaptationToggle: (Boolean) -> Unit,
    /** 手动值写透（拖动中即时调用，成品 MapperOverrides；落盘在本页 onValueChangeFinished）。 */
    onManualOverridesChange: (MapperOverrides) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // LazyListState 经 rememberSaveable + Saver 持久化：Tab 互切销毁组合时滚动位置保存/恢复，
    // 不再回顶；主题切换在截图覆盖模式下组合本就保持，此处为双保险
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // 进入 Tab（组合）时读一次 mapper/prefs 最新值进本地 state（写透见各回调）
    var calibrated by remember { mutableStateOf(deps.mapper.calibrationData.isCalibrated) }
    var angleSensitivity by remember { mutableStateOf(deps.mapper.angleSensitivity) }
    var expressionSensitivity by remember { mutableStateOf(deps.mapper.expressionSensitivity) }
    var positionSensitivity by remember { mutableStateOf(deps.mapper.positionSensitivity) }
    var debugLogsEnabled by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_ENABLE_DEBUG_LOGS, false))
    }
    var oneEuroEnabled by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_ENABLE_ONE_EURO_FILTER, true))
    }
    var themeFollowSystem by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_THEME_FOLLOW_SYSTEM, true))
    }
    var amoledBlack by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_THEME_AMOLED_BLACK, false))
    }
    var sendFrequencyHz by remember {
        mutableStateOf(deps.appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ))
    }
    // 参数自适应开关（appPrefs 真源）+ 手动映射范围 4 项本地值（进入 Tab 读一次独立 prefs，
    // 拖动写透 onManualOverridesChange、松手 onValueChangeFinished 落盘——与灵敏度滑块同款）
    var adaptationEnabled by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_ENABLE_ADAPTATION, true))
    }
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    var manualMouthSmileNeutral by remember {
        mutableStateOf(deps.adaptationManualPrefs.getFloat(KEY_MANUAL_MOUTHSMILE_NEUTRAL, MANUAL_MOUTHSMILE_NEUTRAL_DEFAULT))
    }
    var manualBrowSensitivity by remember {
        mutableStateOf(deps.adaptationManualPrefs.getFloat(KEY_MANUAL_BROW_SENSITIVITY, MANUAL_SENSITIVITY_DEFAULT))
    }
    var manualMouthOpenSensitivity by remember {
        mutableStateOf(deps.adaptationManualPrefs.getFloat(KEY_MANUAL_MOUTHOPEN_SENSITIVITY, MANUAL_SENSITIVITY_DEFAULT))
    }
    // 脸部镜像（appPrefs 真源）：全局开关，不属手动映射范围、不受 probeActive 门控
    var faceMirrorEnabled by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_FACE_MIRROR, false))
    }

    // 手动值 → 成品 MapperOverrides（UI 侧换算：倍率 ÷ 进归一化分母，倍率越大越灵敏）。
    // 换算基线与 BlendShapeMapper 私有常量人工对齐（BROW_DEVICE_MAX=1.0 /
    // MouthOpen deviceMax=0.30），mapper 不放权故不引其常量——改动 mapper 默认值时此处同步。
    fun manualOverrides() = MapperOverrides(
        mouthSmileNeutralBase = manualMouthSmileNeutral,
        browDeviceMax = MANUAL_BROW_DEVICE_BASELINE / manualBrowSensitivity,
        mouthOpenDeviceMax = MANUAL_MOUTHOPEN_DEVICE_BASELINE / manualMouthOpenSensitivity
    )

    fun persistManual() {
        deps.adaptationManualPrefs.edit()
            .putFloat(KEY_MANUAL_MOUTHSMILE_NEUTRAL, manualMouthSmileNeutral)
            .putFloat(KEY_MANUAL_BROW_SENSITIVITY, manualBrowSensitivity)
            .putFloat(KEY_MANUAL_MOUTHOPEN_SENSITIVITY, manualMouthOpenSensitivity)
            .apply()
    }

    fun persistSensitivities() {
        scope.launch(Dispatchers.IO) {
            deps.calibrationStorage.saveSensitivities(
                SensitivityConfig(
                    angle = deps.mapper.angleSensitivity,
                    position = deps.mapper.positionSensitivity,
                    expression = deps.mapper.expressionSensitivity
                )
            )
        }
    }

    // 滚到关于：信号 >0 才滚（0 = 初始/已消费），滚完立即清零
    LaunchedEffect(aboutScrollSignal) {
        if (aboutScrollSignal > 0) {
            listState.animateScrollToItem(AboutCardIndex)
            onAboutScrollConsumed()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = listState,
    ) {
        item {
            SettingsCard(title = stringResource(R.string.settings_card_calibration)) {
                Text(
                    text = stringResource(R.string.settings_calibration_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (calibrated) {
                        stringResource(R.string.settings_calib_status_calibrated)
                    } else {
                        stringResource(R.string.settings_calib_status_uncalibrated)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCalibrateRequested, // Tab 版无弹窗可关，直接进全屏对准视图
                    modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                ) { Text(stringResource(R.string.action_calibrate)) }
                Spacer(Modifier.height(ResetButtonSpacing))
                Button(
                    onClick = {
                        deps.mapper.updateCalibration(CalibrationData()) // 中性默认：恢复恒等映射
                        deps.smoother.reset()
                        scope.launch { deps.calibrationStorage.reset() }
                        calibrated = deps.mapper.calibrationData.isCalibrated
                        // 通知 Activity：校准已回到未完成态，首页引导卡应重新出现
                        onCalibrationReset()
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_calibration_reset),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(SecondaryButtonHeight),
                ) { Text(stringResource(R.string.action_reset_calibration)) }

                SensitivitySlider(
                    label = stringResource(R.string.sensitivity_head),
                    value = angleSensitivity,
                    onValueChange = {
                        angleSensitivity = it
                        deps.mapper.angleSensitivity = it
                    },
                    onFinished = { persistSensitivities() },
                )
                SensitivitySlider(
                    label = stringResource(R.string.sensitivity_expression),
                    value = expressionSensitivity,
                    onValueChange = {
                        expressionSensitivity = it
                        deps.mapper.expressionSensitivity = it
                    },
                    onFinished = { persistSensitivities() },
                )
                SensitivitySlider(
                    label = stringResource(R.string.sensitivity_position),
                    value = positionSensitivity,
                    onValueChange = {
                        positionSensitivity = it
                        deps.mapper.positionSensitivity = it
                    },
                    onFinished = { persistSensitivities() },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_card_smoothing)) {
                FrequencySlider(
                    label = stringResource(R.string.settings_stream_frequency),
                    value = sendFrequencyHz,
                    onValueChange = { hz ->
                        sendFrequencyHz = hz
                        // 写透渲染器（下一帧立即按新频率节流）；落盘等拖动结束（onFinished）
                        deps.faceRenderer.setSendFrequencyHz(hz)
                    },
                    onFinished = {
                        deps.appPrefs.edit().putInt(KEY_SEND_FREQUENCY_HZ, sendFrequencyHz).apply()
                    },
                )
                // 频率滑块与 1€ 开关之间的分割线（色同首页 outlineVariant）
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.switch_one_euro_filter),
                    checked = oneEuroEnabled,
                    onCheckedChange = { checked ->
                        oneEuroEnabled = checked
                        deps.appPrefs.edit().putBoolean(KEY_ENABLE_ONE_EURO_FILTER, checked).apply()
                        deps.smoother.setOneEuroFilterEnabled(checked)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (checked) R.string.toast_one_euro_on else R.string.toast_one_euro_off
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                )
                // 参数自适应开关与手动映射区的分割线（色同卡内既有分割线）
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.adaptation_switch_label),
                    subtitle = stringResource(R.string.adaptation_switch_desc),
                    checked = adaptationEnabled,
                    onCheckedChange = { checked ->
                        adaptationEnabled = checked
                        onAdaptationToggle(checked)
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                // 脸部镜像：全局输出后处理（左右通道互换 + 水平分量取反），不属于手动映射
                // 范围——不受 probeActive 禁用，与自适应开关同层
                SwitchRow(
                    label = stringResource(R.string.face_mirror_label),
                    subtitle = stringResource(R.string.face_mirror_desc),
                    checked = faceMirrorEnabled,
                    onCheckedChange = { checked ->
                        faceMirrorEnabled = checked
                        deps.appPrefs.edit().putBoolean(KEY_FACE_MIRROR, checked).apply()
                        // 写透 mapper：GL 线程下一帧即生效（不 reset smoother，保留柔性过渡）
                        deps.mapper.faceMirrorEnabled = checked
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ManualMappingSection(
                    expanded = manualExpanded,
                    onToggleExpanded = { manualExpanded = !manualExpanded },
                    probeActive = adaptationProbeActive,
                    mouthSmileNeutral = manualMouthSmileNeutral,
                    onMouthSmileNeutralChange = {
                        manualMouthSmileNeutral = it
                        onManualOverridesChange(manualOverrides())
                    },
                    browSensitivity = manualBrowSensitivity,
                    onBrowSensitivityChange = {
                        manualBrowSensitivity = it
                        onManualOverridesChange(manualOverrides())
                    },
                    mouthOpenSensitivity = manualMouthOpenSensitivity,
                    onMouthOpenSensitivityChange = {
                        manualMouthOpenSensitivity = it
                        onManualOverridesChange(manualOverrides())
                    },
                    onFinished = { persistManual() },
                    onRestoreDefaults = {
                        manualMouthSmileNeutral = MANUAL_MOUTHSMILE_NEUTRAL_DEFAULT
                        manualBrowSensitivity = MANUAL_SENSITIVITY_DEFAULT
                        manualMouthOpenSensitivity = MANUAL_SENSITIVITY_DEFAULT
                        // 决策 3：清手动 prefs + 重置滑块 UI，不动自适应 overrides（探测结果
                        // 生效中按钮本就禁用，本回调只在手动模式可达）；应用全 null 覆盖 = 默认
                        deps.adaptationManualPrefs.edit()
                            .remove(KEY_MANUAL_MOUTHSMILE_NEUTRAL)
                            .remove(KEY_MANUAL_BROW_SENSITIVITY)
                            .remove(KEY_MANUAL_MOUTHOPEN_SENSITIVITY)
                            .apply()
                        onManualOverridesChange(MapperOverrides())
                    },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_card_theme)) {
                SwitchRow(
                    label = stringResource(R.string.theme_follow_system),
                    checked = themeFollowSystem,
                    onCheckedChange = { checked ->
                        themeFollowSystem = checked
                        // state 更新 + prefs 落盘全在 MainActivity（回调上收）
                        onThemeFollowSystemChange(checked)
                    },
                    // Reveal 圆心（Switch 中心 root 坐标）先行上收——SwitchRow 内部保证
                    // origin 先于 onCheckedChange 调用，同一帧快照与 isDark 翻转一起送达
                    onCheckedChangeWithOrigin = { _, origin -> onThemeRevealOrigin(origin) },
                )
                // 分割线（色同推流卡，outlineVariant）
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                // AMOLED 纯黑背景：普通 Switch（非主题深浅切换语义，不走 Reveal 过渡）；
                // 浅色模式下开启无视觉效果，pref 照写，切回深色自动生效
                SwitchRow(
                    label = stringResource(R.string.theme_amoled_black),
                    subtitle = stringResource(R.string.theme_amoled_black_desc),
                    checked = amoledBlack,
                    onCheckedChange = { checked ->
                        amoledBlack = checked
                        onAmoledBlackChange(checked)
                    },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_card_developer)) {
                SwitchRow(
                    label = stringResource(R.string.switch_debug_logs),
                    checked = debugLogsEnabled,
                    onCheckedChange = { checked ->
                        debugLogsEnabled = checked
                        deps.appPrefs.edit().putBoolean(KEY_ENABLE_DEBUG_LOGS, checked).apply()
                        deps.faceRenderer.setDebugLogsEnabled(checked)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (checked) R.string.toast_debug_logs_on else R.string.toast_debug_logs_off
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_card_about)) {
                Text(
                    text = stringResource(R.string.about_app_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_github_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        // 系统浏览器打开（占位 URL 打不开属预期）；无浏览器设备静默忽略（罕见，
                        // 不为此引入新 toast 文案）
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(context.getString(R.string.about_github_url))
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                )
            }
        }
    }
}

/**
 * 一行开关：文案（可选副标题 [subtitle]，小字 onSurfaceVariant）+ Switch（m3）。
 * [onCheckedChangeWithOrigin] 非空时（当前仅主题开关）：额外上收 Switch 中心点的 root 坐标
 * （主题过渡 Reveal 圆心），且 origin 先于 [onCheckedChange] 调用——两者在同一点击
 * 处理器内同步写 state，同帧快照送达 ThemeTransition（先 origin 后翻转，无竞态）。
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onCheckedChangeWithOrigin: ((Boolean, Offset) -> Unit)? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val switchCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newChecked ->
                onCheckedChangeWithOrigin?.let { reportOrigin ->
                    switchCoords.value?.let { coords ->
                        reportOrigin(
                            newChecked,
                            coords.localToRoot(
                                Offset(coords.size.width / 2f, coords.size.height / 2f)
                            ),
                        )
                    }
                }
                onCheckedChange(newChecked)
            },
            modifier = if (onCheckedChangeWithOrigin != null) {
                Modifier.onGloballyPositioned { switchCoords.value = it }
            } else {
                Modifier
            },
        )
    }
}

/**
 * 一行灵敏度滑块：标签 + 数值 + Slider（m3 简单重载）。
 * 值域 [BlendShapeMapper.SENSITIVITY_MIN]..[BlendShapeMapper.SENSITIVITY_MAX]（0.10x ~ 3.00x），
 * 步进 0.01（m3 steps=289：区间内含两端共 291 个离散值）；
 * onValueChange 先做两位小数吸附再写透 mapper（下一帧立即生效），onValueChangeFinished 整体落盘。
 *
 * m3 1.4.0 实测（javap material3-android-1.4.0）：简单重载带 thumb/track 槽，且内部每次重组执行
 * state.value = value 回填——外部 value 即唯一真源，滑块位置恒跟随数值（受控语义）；
 * SliderState 重载在 onValueChange 非空时 state 不自写值，位置会冻结，故弃用。
 *
 * 样式（华为 InSetSlider 规格）：轨道 4dp 等高（active/inactive 无中缝）、两端完整
 * 圆角、无 stop indicator、无刻度点；inactive = outlineVariant（不用 surfaceVariant——
 * 浅色主题下与卡片 ContainerHigh 背景同色会隐形）；thumb 16dp 纯色圆无阴影。
 * fraction 手算自受控 value（0.01 吸附后的精确真源），与 thumb 位置同源派生、严格对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitivitySlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%.2fx", value),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // fraction 手算：受控 value 是 0.01 吸附后的精确值（唯一真源），与 thumb 位置同源
        // 派生、严格对齐，不依赖 m3 SliderState 内部属性
        val fraction = ((value - BlendShapeMapper.SENSITIVITY_MIN) /
            (BlendShapeMapper.SENSITIVITY_MAX - BlendShapeMapper.SENSITIVITY_MIN))
            .coerceIn(0f, 1f)
        Slider(
            value = value,
            onValueChange = { raw ->
                // 两位小数吸附：tick 值带 ~1e-7 浮点尾差（如 0.9999999f），吸附后才是
                // 精确 0.01 网格值（1.00f），显示/写透/落盘三方一致；coerceIn 兜底
                onValueChange(
                    ((raw * 100).roundToInt() / 100f).coerceIn(
                        BlendShapeMapper.SENSITIVITY_MIN,
                        BlendShapeMapper.SENSITIVITY_MAX,
                    )
                )
            },
            onValueChangeFinished = onFinished,
            steps = SENSITIVITY_STEPS,
            valueRange = BlendShapeMapper.SENSITIVITY_MIN..BlendShapeMapper.SENSITIVITY_MAX,
            thumb = {
                Box(
                    Modifier
                        .size(InSetThumbSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            },
            track = { _ ->
                // 颜色在组合期读取（DrawScope 内不可读 MaterialTheme）
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(InSetTrackHeight)
                        .drawBehind {
                            val stroke = InSetTrackHeight.toPx()
                            val corner = CornerRadius(stroke / 2f)
                            // ① 整条 inactive 底轨（两端完整圆角，4dp 等高无中缝）
                            drawRoundRect(color = inactiveColor, cornerRadius = corner)
                            // ② active 段覆盖：最小宽度 = stroke，value=MIN 时呈圆头起步
                            drawRoundRect(
                                color = activeColor,
                                size = Size(max(size.width * fraction, stroke), stroke),
                                cornerRadius = corner,
                            )
                        },
                )
            },
        )
    }
}

/** Slider 步进配置：(MAX-MIN)/0.01 = 290 段 → 含两端 291 个离散值 → m3 steps = 291 - 2。 */
private const val SENSITIVITY_STEPS = 289

/** 推流频率滑块档位（Hz）：10..60 每 5Hz 一档共 11 档，m3 steps = 10 段 - 2 = 9。 */
private const val FREQUENCY_MIN = 10f
private const val FREQUENCY_MAX = 60f
private const val FREQUENCY_STEPS = 9

/** InSetSlider 规格（华为 UX）：轨道高 4dp（active/inactive 等高无缝）、thumb 16dp。 */
private val InSetTrackHeight = 4.dp
private val InSetThumbSize = 16.dp

/**
 * 一行推流频率离散滑块：标签 + 数值 + 说明 + Slider。结构/样式对齐 [SensitivitySlider]
 * （m3 简单重载受控语义、InSetSlider 规格 thumb/track），差异仅两处：值显示为整数 Hz、
 * 多一行说明文本（需求要求，样式同 intro 的 bodySmall/onSurfaceVariant）。
 * 档位 10/15/…/60 Hz 每 5Hz 一档共 11 档（steps=9：10..60 共 10 段）；onValueChange 吸附
 * 5Hz 网格（tick 值带浮点尾差，吸附后才是精确档位）并写透渲染器（下一帧生效），
 * onValueChangeFinished 落盘。
 * thumb/track 槽复制自 [SensitivitySlider] 而非抽公共组件：灵敏度滑块真机回归已过，
 * 只加不改，避免回归失效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencySlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                text = "$value Hz",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(R.string.settings_stream_frequency_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // fraction 手算：受控 value 是 10Hz 吸附后的精确真源，与 thumb 位置同源派生
        val fraction = ((value - FREQUENCY_MIN) / (FREQUENCY_MAX - FREQUENCY_MIN)).coerceIn(0f, 1f)
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                onValueChange(
                    ((raw / 5).roundToInt() * 5)
                        .coerceIn(FREQUENCY_MIN.toInt(), FREQUENCY_MAX.toInt())
                )
            },
            onValueChangeFinished = onFinished,
            steps = FREQUENCY_STEPS,
            valueRange = FREQUENCY_MIN..FREQUENCY_MAX,
            thumb = {
                Box(
                    Modifier
                        .size(InSetThumbSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            },
            track = { _ ->
                // 颜色在组合期读取（DrawScope 内不可读 MaterialTheme）
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(InSetTrackHeight)
                        .drawBehind {
                            val stroke = InSetTrackHeight.toPx()
                            val corner = CornerRadius(stroke / 2f)
                            // ① 整条 inactive 底轨（两端完整圆角，4dp 等高无中缝）
                            drawRoundRect(color = inactiveColor, cornerRadius = corner)
                            // ② active 段覆盖：最小宽度 = stroke，value=MIN 时呈圆头起步
                            drawRoundRect(
                                color = activeColor,
                                size = Size(max(size.width * fraction, stroke), stroke),
                                cornerRadius = corner,
                            )
                        },
                )
            },
        )
    }
}

/**
 * 校准卡片按钮主次常量（与首页同值，可独立调整）。
 */
/** 「校准」按钮高（主操作，48dp 触控目标）。 */
private val PrimaryButtonHeight = 48.dp
/** 「重置校准」按钮高（危险操作矮一档，视觉主次）。 */
private val SecondaryButtonHeight = 40.dp

// ---------- 手动映射范围换算基线与滑块常量 ----------

/** MouthSmile 中性点默认值（与 BlendShapeMapper.SYNTH_NEUTRAL_BASE 对齐，mapper 私有不引）。 */
internal const val MANUAL_MOUTHSMILE_NEUTRAL_DEFAULT = 0.5f

/** 灵敏度倍率默认值（1.0 = 恒等，恢复默认用）。 */
internal const val MANUAL_SENSITIVITY_DEFAULT = 1.0f

/**
 * 眉毛灵敏度换算基线（与 BlendShapeMapper 私有常量 BROW_DEVICE_MAX=1.0 对齐）：
 * 应用时 browDeviceMax = 本值 / 倍率，倍率越大越灵敏（分母越小）。
 * mapper 不放权故不引其常量，改动 mapper 默认值时此处同步（漂移风险已记录 P2）。
 */
internal const val MANUAL_BROW_DEVICE_BASELINE = 1.0f

/** 说话灵敏度换算基线（与 BlendShapeMapper 私有常量 MouthOpen deviceMax=0.30 对齐）。 */
internal const val MANUAL_MOUTHOPEN_DEVICE_BASELINE = 0.30f

/** 灵敏度倍率滑块范围/步进：0.50x~2.00x 每 0.05 一档（30 段 31 值，steps=29）。 */
private const val MANUAL_SENSITIVITY_MIN = 0.5f
private const val MANUAL_SENSITIVITY_MAX = 2.0f
private const val MANUAL_SENSITIVITY_STEPS = 29

/**
 * 灵敏度倍率滑块的**显式吸附步长**（0.05 = (MAX-MIN)/30，与 [MANUAL_SENSITIVITY_STEPS] 自洽）。
 *
 * 为什么不用 `(end - start) / (steps + 2)` 反推：m3 Slider 的 `steps = n` 表示 n 个内部
 * 刻度、切成 n+1 段，反推公式分母多算 1 段，得到 1.5/31 ≈ 0.048387——1.00x 落在
 * 20.66 格上，永远吸附不到（真机「停不住 1.00x」的根因）。改用零锚定固定步长后，
 * 端点 0.5 / 2.0 与中点 1.0 都是步长整数倍，精准可达。
 */
private const val MANUAL_SENSITIVITY_STEP = 0.05f

/** MouthSmile 中性点滑块：0~1 每 0.01 一档（100 段 101 值，steps=99）。 */
private const val MANUAL_NEUTRAL_MIN = 0f
private const val MANUAL_NEUTRAL_MAX = 1f
private const val MANUAL_NEUTRAL_STEPS = 99

/** 中性点滑块的显式吸附步长（0.01 = (MAX-MIN)/100，与 [MANUAL_NEUTRAL_STEPS] 自洽）。 */
private const val MANUAL_NEUTRAL_STEP = 0.01f

/**
 * 「手动映射范围」折叠区：可点标题行（箭头随展开旋转）+ AnimatedVisibility 内容
 * （禁用提示行 + MouthSmile 中性点/眉毛灵敏度/说话灵敏度滑块 + 恢复默认按钮）。
 * 展开状态 rememberSaveable 存于调用处（Tab 切走销毁组合时保活）。
 *
 * 原「MouthX 方向翻转」开关已移除（改由推流卡的全局「脸部镜像」承担）；
 * MapperOverrides.mouthXInvert 字段仍保留作底层兜底，当前无 UI 写入点。
 *
 * 禁用规则：[probeActive] = 探测值生效中（自适应开关 ON 且最近 Done/缓存命中）——
 * 此时手动调整不进 mapper（拖动被忽略），整区半透明禁用并显示提示行；OFF/Failed 可用。
 * 写透时机：拖动中 onValueChange 即经各回调上收成品（立即生效），松手 [onFinished] 落盘。
 */
@Composable
private fun ManualMappingSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    probeActive: Boolean,
    mouthSmileNeutral: Float,
    onMouthSmileNeutralChange: (Float) -> Unit,
    browSensitivity: Float,
    onBrowSensitivityChange: (Float) -> Unit,
    mouthOpenSensitivity: Float,
    onMouthOpenSensitivityChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onRestoreDefaults: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 可点标题行：箭头 180° 旋转指示展开态（m3 无自带折叠组件，最小自绘）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.adaptation_manual_title),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null, // 装饰性：展开态由旋转与内容显隐承担
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (probeActive) {
                    Text(
                        text = stringResource(R.string.adaptation_manual_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                ManualRangeSlider(
                    label = stringResource(R.string.adaptation_manual_mouth_smile),
                    value = mouthSmileNeutral,
                    valueRange = MANUAL_NEUTRAL_MIN..MANUAL_NEUTRAL_MAX,
                    steps = MANUAL_NEUTRAL_STEPS,
                    step = MANUAL_NEUTRAL_STEP,
                    format = { String.format(Locale.US, "%.2f", it) },
                    enabled = !probeActive,
                    onValueChange = onMouthSmileNeutralChange,
                    onFinished = onFinished,
                )
                ManualRangeSlider(
                    label = stringResource(R.string.adaptation_manual_brow_sens),
                    value = browSensitivity,
                    valueRange = MANUAL_SENSITIVITY_MIN..MANUAL_SENSITIVITY_MAX,
                    steps = MANUAL_SENSITIVITY_STEPS,
                    step = MANUAL_SENSITIVITY_STEP,
                    format = { String.format(Locale.US, "%.2fx", it) },
                    enabled = !probeActive,
                    onValueChange = onBrowSensitivityChange,
                    onFinished = onFinished,
                )
                ManualRangeSlider(
                    label = stringResource(R.string.adaptation_manual_mouth_open),
                    value = mouthOpenSensitivity,
                    valueRange = MANUAL_SENSITIVITY_MIN..MANUAL_SENSITIVITY_MAX,
                    steps = MANUAL_SENSITIVITY_STEPS,
                    step = MANUAL_SENSITIVITY_STEP,
                    format = { String.format(Locale.US, "%.2fx", it) },
                    enabled = !probeActive,
                    onValueChange = onMouthOpenSensitivityChange,
                    onFinished = onFinished,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRestoreDefaults,
                    enabled = !probeActive,
                    modifier = Modifier.fillMaxWidth().height(SecondaryButtonHeight),
                ) { Text(stringResource(R.string.adaptation_manual_reset)) }
            }
        }
    }
}

/**
 * 一行通用范围滑块（手动映射区专用）：标签 + 数值 + Slider。结构与 InSetSlider 规格
 * （轨道 4dp 等高无缝、thumb 16dp）复制自 [SensitivitySlider]。后者的 fraction 死绑
 * SENSITIVITY_MIN/MAX 常量，无法参数化，故另建一个而非改它，避免动到已回归的路径。
 * m3 简单重载受控语义（外部 value 唯一真源）。
 *
 * 吸附：[step] 为**显式**吸附步长（由调用方按各滑块网格给定，见 [MANUAL_SENSITIVITY_STEP] /
 * [MANUAL_NEUTRAL_STEP]），零锚定 `(raw / step).roundToInt() * step`——与 [FrequencySlider]
 * 的 `(raw / 5).roundToInt() * 5` 同款写法。不再从 `steps` 反推步长：m3 的 `steps = n`
 * 是 n 个内部刻度切成 n+1 段，反推分母会多算一段，使 1.00x 这类非零整数倍锚点吸附不到。
 * 三个滑块的端点都是步长整数倍，故无需从 start 起算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualRangeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    step: Float,
    format: (Float) -> String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .coerceIn(0f, 1f)
        Slider(
            value = value,
            onValueChange = { raw ->
                // 零锚定吸附：端点与常用锚点（1.00x / 0.50）均为 step 整数倍，精准可达
                onValueChange(
                    ((raw / step).roundToInt() * step)
                        .coerceIn(valueRange.start, valueRange.endInclusive)
                )
            },
            onValueChangeFinished = onFinished,
            enabled = enabled,
            steps = steps,
            valueRange = valueRange,
            thumb = {
                Box(
                    Modifier
                        .size(InSetThumbSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            },
            track = { _ ->
                // 颜色在组合期读取（DrawScope 内不可读 MaterialTheme）
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(InSetTrackHeight)
                        .drawBehind {
                            val stroke = InSetTrackHeight.toPx()
                            val corner = CornerRadius(stroke / 2f)
                            drawRoundRect(color = inactiveColor, cornerRadius = corner)
                            drawRoundRect(
                                color = activeColor,
                                size = Size(max(size.width * fraction, stroke), stroke),
                                cornerRadius = corner,
                            )
                        },
                )
            },
        )
    }
}
/** 校准→重置间距（卡片内独立节奏；首页按钮间为 20dp，此处 16dp，真机可调）。 */
private val ResetButtonSpacing = 16.dp
