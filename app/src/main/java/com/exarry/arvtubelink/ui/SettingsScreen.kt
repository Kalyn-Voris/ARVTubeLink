package com.exarry.arvtubelink.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.mapping.BlendShapeMapper
import com.exarry.arvtubelink.mapping.CalibrationData
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.mapping.CalibrationStorage
import com.exarry.arvtubelink.mapping.SensitivityConfig
import com.exarry.arvtubelink.mapping.Smoother
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal const val KEY_ENABLE_DEBUG_LOGS = "enable_debug_logs"

internal const val KEY_ENABLE_ONE_EURO_FILTER = "enable_one_euro_filter"

internal const val KEY_ENABLE_ADAPTATION = "enable_adaptation"

// 手动值存独立 prefs，与自适应 overrides 互不覆盖；探测值生效中时手动区禁用
/** Float 0~1，默认 0.5，对应 MapperOverrides.mouthSmileNeutralBase */
internal const val KEY_MANUAL_MOUTHSMILE_NEUTRAL = "manual_mouthsmile_neutral"

/** Float 0.5~2.0，默认 1.0；应用时 browDeviceMax = 1.0f / 倍率 */
internal const val KEY_MANUAL_BROW_SENSITIVITY = "manual_brow_sensitivity"

/** Float 0.5~2.0，默认 1.0；应用时 mouthOpenDeviceMax = 0.30f / 倍率 */
internal const val KEY_MANUAL_MOUTHOPEN_SENSITIVITY = "manual_mouthopen_sensitivity"

/** Boolean，默认 false；开启后 mapper 对所有出网参数左右镜像 */
internal const val KEY_FACE_MIRROR = "face_mirror"

/** ON = 跟随系统（默认），OFF = 强制深色 */
internal const val KEY_THEME_FOLLOW_SYSTEM = "theme_follow_system"

/** 默认关闭；仅深色模式下生效 */
internal const val KEY_THEME_AMOLED_BLACK = "theme_amoled_black"

internal const val KEY_VTS_IP = "vts_ip"
internal const val KEY_VTS_PORT = "vts_port"

/** Int，10/20/30/40/50/60 Hz */
internal const val KEY_SEND_FREQUENCY_HZ = "send_frequency_hz"

/** 默认 30，与 FaceRenderer 初始节流间隔 1000/30ms 对齐 */
internal const val DEFAULT_SEND_FREQUENCY_HZ = 30

/** 控制 MainActivity 的 2s 空闲全屏纯黑遮罩（ui/BurnInMask.kt） */
internal const val KEY_BURN_IN_MASK_ENABLED = "burn_in_mask_enabled"

internal const val DEFAULT_BURN_IN_MASK_ENABLED = true

// 存 language tag，空串 = 跟随系统；values/ 为中文默认资源，values-en/ 为英文
/** 值见 [LANGUAGE_SYSTEM] / [LANGUAGE_ZH] / [LANGUAGE_EN] */
internal const val KEY_APP_LANGUAGE = "app_language"

internal const val LANGUAGE_SYSTEM = ""

internal const val LANGUAGE_ZH = "zh"

internal const val LANGUAGE_EN = "en"

/** 关于卡在 LazyColumn 中的 item 索引（第 7 张卡） */
private const val AboutCardIndex = 6

/** 由 MainActivity 构造，经 AppRoot 传入 */
data class SettingsDeps(
    val mapper: BlendShapeMapper,
    val smoother: Smoother,
    val calibrationStorage: CalibrationStorage,
    val appPrefs: SharedPreferences,
    /** 手动映射值独立 prefs（adaptation_manual_prefs） */
    val adaptationManualPrefs: SharedPreferences,
    val faceRenderer: FaceRenderer
)

/** Tab 内容：进入组合时读一次 mapper/prefs 进本地 state，交互同时写透 mapper */
@Composable
fun SettingsContent(
    deps: SettingsDeps,
    aboutScrollSignal: Int,
    onAboutScrollConsumed: () -> Unit,
    onThemeFollowSystemChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onThemeRevealOrigin: (Offset) -> Unit,
    onCalibrateRequested: () -> Unit,
    onCalibrationReset: () -> Unit,
    /** 探测值生效中（开关 ON 且最近 Done/缓存命中）时手动映射区禁用 */
    adaptationProbeActive: Boolean,
    onAdaptationToggle: (Boolean) -> Unit,
    onManualOverridesChange: (MapperOverrides) -> Unit,
    onLanguageChange: (String) -> Unit,
    onBurnInMaskEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // rememberSaveable + Saver：Tab 切走销毁组合后仍恢复滚动位置
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

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
    var faceMirrorEnabled by remember {
        mutableStateOf(deps.appPrefs.getBoolean(KEY_FACE_MIRROR, false))
    }
    var appLanguage by remember {
        mutableStateOf(deps.appPrefs.getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM)
    }
    // 必须用 remember：rememberSaveable 会把 true 存进 Bundle，Activity 重建后弹窗自动重开
    var languageDialogVisible by remember { mutableStateOf(false) }

    // 同样用 remember，Activity 重建后不恢复打开态
    var ossDialogVisible by remember { mutableStateOf(false) }

    // API < 33 的 setApplicationLocales 同步调用 recreate()，须等弹窗窗口移除后再应用
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingLanguageTag, languageDialogVisible) {
        val tag = pendingLanguageTag ?: return@LaunchedEffect
        if (languageDialogVisible) return@LaunchedEffect
        pendingLanguageTag = null
        onLanguageChange(tag)
    }

    // ON_STOP 早于 onDestroy，此时改 false 让 Dialog 走正常 dismiss，不留孤儿窗口
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                languageDialogVisible = false
                pendingLanguageTag = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var burnInMaskEnabled by remember {
        mutableStateOf(
            deps.appPrefs.getBoolean(KEY_BURN_IN_MASK_ENABLED, DEFAULT_BURN_IN_MASK_ENABLED)
        )
    }

    // 倍率 ÷ 换算基线：brow 1.0 / mouthOpen 0.30，倍率越大分母越小越灵敏
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
                    onClick = onCalibrateRequested,
                    modifier = Modifier.fillMaxWidth().height(PrimaryButtonHeight),
                ) { Text(stringResource(R.string.action_calibrate)) }
                Spacer(Modifier.height(ResetButtonSpacing))
                Button(
                    onClick = {
                        deps.mapper.updateCalibration(CalibrationData())
                        deps.smoother.reset()
                        scope.launch { deps.calibrationStorage.reset() }
                        calibrated = deps.mapper.calibrationData.isCalibrated
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
                        deps.faceRenderer.setSendFrequencyHz(hz)
                    },
                    onFinished = {
                        deps.appPrefs.edit().putInt(KEY_SEND_FREQUENCY_HZ, sendFrequencyHz).apply()
                    },
                )
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
                SwitchRow(
                    label = stringResource(R.string.face_mirror_label),
                    subtitle = stringResource(R.string.face_mirror_desc),
                    checked = faceMirrorEnabled,
                    onCheckedChange = { checked ->
                        faceMirrorEnabled = checked
                        deps.appPrefs.edit().putBoolean(KEY_FACE_MIRROR, checked).apply()
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
                        // 清手动 prefs 并重置滑块，不动自适应 overrides
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
                        onThemeFollowSystemChange(checked)
                    },
                    // origin 先于 onCheckedChange 上收，同一帧快照送达 Reveal
                    onCheckedChangeWithOrigin = { _, origin -> onThemeRevealOrigin(origin) },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                // 不走 Reveal 过渡；浅色模式下 pref 照写，切回深色自动生效
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
            SettingsCard(title = stringResource(R.string.settings_card_ui_effects)) {
                SwitchRow(
                    label = stringResource(R.string.switch_burn_in_mask),
                    subtitle = stringResource(R.string.switch_burn_in_mask_desc),
                    checked = burnInMaskEnabled,
                    onCheckedChange = { checked ->
                        burnInMaskEnabled = checked
                        onBurnInMaskEnabledChange(checked)
                    },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_card_language)) {
                // 语言名用各自的自称，不随界面语言变化
                val currentLanguageLabel = when (appLanguage) {
                    LANGUAGE_ZH -> stringResource(R.string.language_zh)
                    LANGUAGE_EN -> stringResource(R.string.language_en)
                    else -> stringResource(R.string.language_system)
                }
                ClickableValueRow(
                    label = stringResource(R.string.settings_language_label),
                    value = currentLanguageLabel,
                    onClick = { languageDialogVisible = true },
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
                        // InAppLog 进程级闸门，WARN/INFO/DEBUG 全部受控
                        InAppLog.setDebugEnabled(checked)
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
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ClickableValueRow(
                    label = stringResource(R.string.settings_oss_label),
                    subtitle = stringResource(R.string.settings_oss_desc),
                    value = "",
                    onClick = { ossDialogVisible = true },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_trademark),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (languageDialogVisible) {
        LanguageDialog(
            current = appLanguage,
            onSelect = { tag ->
                languageDialogVisible = false
                if (tag != appLanguage) {
                    appLanguage = tag
                    pendingLanguageTag = tag
                }
            },
            onDismiss = { languageDialogVisible = false },
        )
    }

    if (ossDialogVisible) {
        com.exarry.arvtubelink.ui.licenses.OpenSourceLicensesDialog(
            onDismiss = { ossDialogVisible = false },
        )
    }
}

/** 三个单选：跟随系统 / 简体中文 / English */
@Composable
private fun LanguageDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 逐事件 consume() 才立即吞掉；detectTapGestures 要等抬手，期间 DOWN 会漏到下层
    val swallowPointer: Modifier = Modifier
        .fillMaxWidth()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true,
        ),
        title = {
            Box(modifier = swallowPointer) {
                Text(text = stringResource(R.string.language_dialog_title))
            }
        },
        text = {
            Column(modifier = swallowPointer) {
                LanguageOptionRow(
                    label = stringResource(R.string.language_system),
                    selected = current == LANGUAGE_SYSTEM,
                    onClick = { onSelect(LANGUAGE_SYSTEM) },
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_zh),
                    selected = current == LANGUAGE_ZH,
                    onClick = { onSelect(LANGUAGE_ZH) },
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_en),
                    selected = current == LANGUAGE_EN,
                    onClick = { onSelect(LANGUAGE_EN) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text = label)
    }
}

/** 开关行；onCheckedChangeWithOrigin 上收 Switch 中心 root 坐标且先于 onCheckedChange */
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

/** 灵敏度滑块 0.10x~3.00x，步进 0.01（steps=289） */
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
        ARVSlider(
            value = value,
            onValueChange = { raw ->
                // tick 值带 ~1e-7 浮点尾差，先按 0.01 吸附再 coerceIn
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
        )
    }
}

/** 含两端 291 个离散值 → steps = 291 - 2 */
private const val SENSITIVITY_STEPS = 289

/** 10..60 Hz 每 5Hz 一档共 11 档，steps = 10 段 - 2 */
private const val FREQUENCY_MIN = 10f
private const val FREQUENCY_MAX = 60f
private const val FREQUENCY_STEPS = 9

/** 推流频率滑块 10..60 Hz，吸附 5Hz 网格 */
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
        ARVSlider(
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
        )
    }
}

private val PrimaryButtonHeight = 48.dp
private val SecondaryButtonHeight = 40.dp

/** 默认 0.5，与 BlendShapeMapper.SYNTH_NEUTRAL_BASE 对齐 */
internal const val MANUAL_MOUTHSMILE_NEUTRAL_DEFAULT = 0.5f

internal const val MANUAL_SENSITIVITY_DEFAULT = 1.0f

/** 与 BlendShapeMapper 私有 BROW_DEVICE_MAX=1.0 对齐 */
internal const val MANUAL_BROW_DEVICE_BASELINE = 1.0f

/** 与 BlendShapeMapper 私有 MouthOpen deviceMax=0.30 对齐 */
internal const val MANUAL_MOUTHOPEN_DEVICE_BASELINE = 0.30f

/** 0.50x~2.00x 每 0.05 一档（31 值，steps=29） */
private const val MANUAL_SENSITIVITY_MIN = 0.5f
private const val MANUAL_SENSITIVITY_MAX = 2.0f
private const val MANUAL_SENSITIVITY_STEPS = 29

/** 显式吸附步长 0.05；从 steps 反推会多算一段，1.00x 吸附不到 */
private const val MANUAL_SENSITIVITY_STEP = 0.05f

/** 0~1 每 0.01 一档（101 值，steps=99） */
private const val MANUAL_NEUTRAL_MIN = 0f
private const val MANUAL_NEUTRAL_MAX = 1f
private const val MANUAL_NEUTRAL_STEPS = 99

/** 显式吸附步长 0.01 */
private const val MANUAL_NEUTRAL_STEP = 0.01f

/** 折叠区：中性点 / 眉毛灵敏度 / 说话灵敏度滑块；probeActive 时整区禁用 */
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
                contentDescription = null,
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

/** 标签 + 数值 + ARVSlider；零锚定吸附 (raw / step).roundToInt() * step */
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
        ARVSlider(
            value = value,
            onValueChange = { raw ->
                onValueChange(
                    ((raw / step).roundToInt() * step)
                        .coerceIn(valueRange.start, valueRange.endInclusive)
                )
            },
            onValueChangeFinished = onFinished,
            enabled = enabled,
            steps = steps,
            valueRange = valueRange,
        )
    }
}
private val ResetButtonSpacing = 16.dp
