package com.exarry.arvtubelink.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.drawToBitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.ui.theme.ARVTubeLinkTheme
import com.exarry.arvtubelink.ui.theme.LocalThemeIsDark

/** Tab 索引取值（tabIndex State 由 MainActivity 持有；切换一律经 onSelectTab 回调上收）。 */
internal const val TAB_HOME = 0
internal const val TAB_SETTINGS = 1
internal const val TAB_LOG = 2

/**
 * 底部 Tab 三页面骨架（首页 / 设置 / 日志）的 Compose 根组件：UI 与 AR 渲染彻底分离。
 * 结构（Box 四层，z 序从低到高）：[GlSurfaceHost]（常驻，zIndex(-1f)，GLSurfaceView +
 * FaceRenderer 唯一宿主）→ [ThemeTransition]（主题过渡层，常驻，单层包 Scaffold——TopAppBar
 * （App 名 + info 图标）+ Tab 内容区（[HomeContent]/[SettingsContent]/[LogContent]，tabIndex
 * 驱动 when 切换）+ NavigationBar）→ [CalibrationOverlay]（校准对准视图，组合序最后 =
 * 画在 GL 与 Scaffold 之上）→ 开屏协议弹窗（[AgreementDialog]/[LicenseDialog]/
 * [PolyFormDialog]，首次启动阻塞；独立 Dialog 窗口与主题截帧无关，见各文件 KDoc）→
 * [BurnInMask]（OLED 防烧屏遮罩，Box 最后一子项 = 本窗口视觉最上层，触发/移除逻辑在
 * MainActivity；系统栏隐藏见下方 insets effect 与 BurnInMask KDoc）。
 *
 * ★ 红线：切 Tab 绝不中断 AR 追踪 ★ [GlSurfaceHost] 必须在本 Box 根层、一切 if/when 之外，
 * ThemeTransition 内部同样不得包含——不离开组合 → AndroidView 不触发 onRelease/dispose →
 * GLSurfaceView 不销毁 → EGL context 不丢；挪进 Tab 内容/过渡层 = SurfaceView 销毁 = AR
 * 闪烁/重初始化；校准全屏只是 1dp↔全屏尺寸变化，走 framework surfaceChanged。
 * 渲染红线：不用 AndroidExternalSurface，不让渲染逻辑向声明式靠拢；不自管理渲染线程
 * （GLSurfaceView.setRenderer(FaceRenderer) + RENDERMODE_CONTINUOUSLY）；setRenderer 只允许
 * 出现在 factory（二次调用抛 IllegalStateException），update/onRelease 均为空实现；调用处
 * 固定带 Modifier.zIndex(-1f)——interop View 必须压在 Compose 内容之下，否则全屏校准时
 * GL 表面会遮住面板。
 *
 * 主题：themeFollowSystem = ON 跟随系统 / OFF 强制深色；isDark 唯一深浅真源（读取点与派生
 * 见函数体内注释）；过渡机制与已知限制详见 ThemeTransition KDoc。已知风险：浅色主题下 GL
 * 绿色网格/准星对比度可能不足（FaceRenderer 不随主题变，属已知取舍；校准全屏 GL 背景为
 * 黑底，实际风险低）。
 *
 * 状态与数据流：业务状态全由 MainActivity 以 mutableStateOf 持有、State&lt;T&gt; 只读传下
 * （单向数据流），逻辑经回调上收；本文件仅自持 ip/port 输入框 remember（Tab 互切保留、
 * Activity 重建回默认值）。顶栏/底栏语义见 ArvTopBar/ArvBottomBar KDoc。
 */
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor：API 35 起 deprecated（edge-to-edge 替代），
// minSdk 26 下无等效替代 API，保留至项目提升 minSdk/全面 edge-to-edge 时再处理
@Composable
fun AppRoot(
    arStatus: State<String>,
    vtsStatus: State<String>,
    tracking: State<Boolean>,
    surfaceFullscreen: State<Boolean>,
    calibrationActive: State<Boolean>,
    calibFacePresent: State<Boolean?>,
    burnInMask: State<Boolean>,
    onBurnInMaskDismiss: () -> Unit,
    tabIndex: State<Int>,
    themeFollowSystem: State<Boolean>,
    amoledBlack: State<Boolean>,
    aboutScrollSignal: State<Int>,
    settingsDeps: SettingsDeps,
    renderer: FaceRenderer,
    defaultIp: String,
    defaultPort: Int,
    onGlSurfaceCreated: (GLSurfaceView) -> Unit,
    onCheckAr: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onConnectVts: (ip: String, portText: String) -> Unit,
    onClearToken: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onAboutScrollConsumed: () -> Unit,
    onThemeFollowSystemChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onCalibrateRequested: () -> Unit,
    onConfirmCalibration: () -> Unit,
    onDismissCalibration: () -> Unit,
    /** 首页首次校准引导卡是否显示（MainActivity 用校准状态 + 忽略标记求值）。 */
    showCalibrationGuide: State<Boolean>,
    /** 引导卡「开始校准」（未追踪时由 MainActivity 自动开追踪再拉起对准视图）。 */
    onStartCalibrationFromHome: () -> Unit,
    /** 引导卡「稍后」（落盘忽略标记，冷启动重现）。 */
    onDismissCalibrationGuide: () -> Unit,
    /** 设置页重置校准后通知 Activity（首页引导卡重现）。 */
    onCalibrationReset: () -> Unit,
    agreementAccepted: State<Boolean>,
    showFullLicense: State<Boolean>,
    showPolyForm: State<Boolean>,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onOpenFullLicense: () -> Unit,
    onDismissLicense: () -> Unit,
    onOpenPolyForm: () -> Unit,
    onDismissPolyForm: () -> Unit,
    /** 参数自适应状态展示文本（null = 不显示；Running 进度行，见 MainActivity.onAdaptationState）。 */
    adaptationStatus: State<String?>? = null,
    /** 探测值生效中（手动映射区禁用真源，MainActivity 维护）。 */
    adaptationProbeActive: State<Boolean>,
    /** 自适应开关切换（副作用在 MainActivity，见 onAdaptationToggle）。 */
    onAdaptationToggle: (Boolean) -> Unit,
    /** 手动映射值写透（拖动中即时调用，成品 MapperOverrides，MainActivity 直写 mapper）。 */
    onManualOverridesChange: (MapperOverrides) -> Unit,
) {
    // ip/port 输入框状态：AppRoot 级 remember——Tab 互切不清空；Activity 重建随新组合
    // 以 defaultIp/defaultPort 重新初始化（MainActivity 已从 prefs 恢复上次输入）
    val ipState = remember { mutableStateOf(defaultIp) }
    val portState = remember { mutableStateOf(defaultPort.toString()) }

    // VTS 地址记忆（每次输入即写）：snapshotFlow 订阅输入变化回写 app_settings，下次启动
    // 由 MainActivity 读出经 defaultIp/defaultPort 恢复；首帧发射 = 初值原样回写，无害。
    // IP 原样存（清空也是一次输入）；端口非法（清空中途/粘贴脏字符）不覆盖上次有效值。
    LaunchedEffect(Unit) {
        snapshotFlow { ipState.value }
            .collect { settingsDeps.appPrefs.edit().putString(KEY_VTS_IP, it).apply() }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { portState.value }.collect { text ->
            val port = text.toIntOrNull() ?: return@collect
            settingsDeps.appPrefs.edit().putInt(KEY_VTS_PORT, port).apply()
        }
    }

    // 主题切换过渡三件套，AppRoot 本地自持不进 MainActivity（回调上收约定不破）：
    //  - themeRevealOrigin：圆心（Switch 中心 root 坐标），与截图同帧写入，动画结束清 null；
    //  - themeScreenshot：旧主题帧（圆心回调里同步截取，必须先于 isDark 翻转——晚截就是新
    //    主题，覆盖层与底层同色、动画不可见）；动画结束置 null → 覆盖层移除并回收；
    //  - themeSwitchSignal：每次切换 +1 单调递增，ThemeTransition 以此驱动动画；0 = 首帧不动画。
    // 系统深浅切换不产生截图与 signal → 无过渡动画（预期，见 ThemeTransition KDoc）。
    val themeRevealOrigin = remember { mutableStateOf<Offset?>(null) }
    val themeScreenshot = remember { mutableStateOf<ImageBitmap?>(null) }
    val themeSwitchSignal = remember { mutableStateOf(0) }

    // 深浅判定：开关 ON = 跟随系统（isSystemInDarkTheme 经系统 onConfigurationChanged
    // dispatch 更新）；OFF = 强制深色。isDark 是唯一深浅真源，读取点在 AppRoot 顶层作用域——
    // 开关/系统切换直接驱动整个 AppRoot 重启，ARVTubeLinkTheme(darkTheme) 据此切换色板；
    // 不走 AppCompatDelegate 的原因见 MainActivity onCreate 注释。
    val isDark = if (themeFollowSystem.value) isSystemInDarkTheme() else true

    // amoledBlack 仅深色下生效（Theme 内部判定），浅色下传 true 也无效果
    ARVTubeLinkTheme(darkTheme = isDark, amoledBlack = amoledBlack.value) {
        // Surface 提供底色兜底（windowBackground 双保险不变）；Box 三层结构见类 KDoc
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalThemeIsDark provides isDark) {
                // 系统栏同步：XML 主题只管启动首帧（强制深色经 ForceDark overlay），切换后
                // 手动同步颜色与图标深浅；取色走 colorScheme（随 darkTheme 切换；
                // statusBar=colorTopBar 值=surfaceContainerHigh 档，navBar=surfaceContainer 档）
                val view = LocalView.current
                val statusBarArgb = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                val navBarArgb = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                LaunchedEffect(isDark, statusBarArgb, navBarArgb) {
                    view.context.findWindow()?.apply {
                        statusBarColor = statusBarArgb
                        navigationBarColor = navBarArgb
                        WindowCompat.getInsetsController(this, view).apply {
                            isAppearanceLightStatusBars = !isDark
                            isAppearanceLightNavigationBars = !isDark
                        }
                    }
                }

                // 防烧屏遮罩期的系统栏隐藏/恢复：API 35+（targetSdk 37 强制 EtE）窗口本就
                // 延伸到栏后，API≤34 非 EtE 窗口靠 hide 让窗口扩展进状态栏区域——两档遮罩
                // 均全覆盖（themes.xml 的 statusBarColor 在 35+ 被系统忽略、≤34 隐藏时无影响）。
                // behavior = TRANSIENT_BY_SWIPE：遮罩期下拉时栏以半透明 overlay 临时透出
                // （overlay 不触发 relayout，遮罩不闪），数秒后自动收回（见 BurnInMask KDoc）。
                val burnInMaskVisible = burnInMask.value
                LaunchedEffect(burnInMaskVisible) {
                    view.context.findWindow()?.let { window ->
                        WindowCompat.getInsetsController(window, view).apply {
                            systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (burnInMaskVisible) hide(WindowInsetsCompat.Type.systemBars())
                            else show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                // ★ 常驻 GL 宿主：唯一 GLSurfaceView，绝不进 Tab 内容/条件块/ThemeTransition
                //   （见类 KDoc 红线——ThemeTransition 覆盖层会复制 content，包 GL = 双实例崩溃）
                GlSurfaceHost(
                    renderer = renderer,
                    fullscreen = surfaceFullscreen.value,
                    tracking = tracking,
                    onCreated = onGlSurfaceCreated,
                    // zIndex(-1f)：GLSurfaceView 是 interop View，显式压到 Compose 内容之下，
                    // 保证校准全屏时底部面板（Compose）画在 GL 表面之上
                    modifier = Modifier
                        .zIndex(-1f)
                        .then(
                            if (surfaceFullscreen.value) Modifier.fillMaxSize()
                            else Modifier.requiredSize(1.dp)
                        ),
                )

                // Tab 骨架：主题过渡层包裹；校准期间整体让位（GL 在其外不受影响）
                ThemeTransition(
                    isDark = isDark,
                    screenshot = themeScreenshot.value,
                    revealOrigin = themeRevealOrigin.value,
                    transitionSignal = themeSwitchSignal.value,
                    onTransitionFinished = {
                        // 两件都清：origin 复位供下一轮；screenshot 置 null 触发覆盖层移除，
                        // 其内部 DisposableEffect 随离组回收 Bitmap
                        themeRevealOrigin.value = null
                        themeScreenshot.value = null
                    },
                ) { _ ->
                    if (!calibrationActive.value) {
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            topBar = {
                                ArvTopBar(
                                    tabIndex = tabIndex.value,
                                    onSelectTab = onSelectTab,
                                    onOpenAbout = onOpenAbout,
                                )
                            },
                            bottomBar = { ArvBottomBar(selected = tabIndex.value, onSelect = onSelectTab) },
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .fillMaxSize()
                            ) {
                                when (tabIndex.value) {
                                    TAB_HOME -> HomeContent(
                                        arStatus = arStatus.value,
                                        vtsStatus = vtsStatus.value,
                                        adaptationStatus = adaptationStatus?.value,
                                        tracking = tracking.value,
                                        showCalibrationGuide = showCalibrationGuide.value,
                                        ipState = ipState,
                                        portState = portState,
                                        onCheckAr = onCheckAr,
                                        onStartTracking = onStartTracking,
                                        onStopTracking = onStopTracking,
                                        onConnectVts = onConnectVts,
                                        onClearToken = onClearToken,
                                        onStartCalibration = onStartCalibrationFromHome,
                                        onDismissCalibrationGuide = onDismissCalibrationGuide,
                                    )
                                    TAB_SETTINGS -> SettingsContent(
                                        deps = settingsDeps,
                                        aboutScrollSignal = aboutScrollSignal.value,
                                        onAboutScrollConsumed = onAboutScrollConsumed,
                                        onThemeFollowSystemChange = onThemeFollowSystemChange,
                                        onAmoledBlackChange = onAmoledBlackChange,
                                        onThemeRevealOrigin = { origin ->
                                            // 截图时机 = 成败关键：此刻重组未发生，View 画的仍是旧主题
                                            // （SwitchRow 保证本回调先于 onCheckedChange / isDark 翻转执行）
                                            // 先截后收：截图失败静默降级为无动画（主题照常切），
                                            // 且绝不动旧值——穿透场景旧值可能还挂在覆盖层上
                                            val shot = try {
                                                view.drawToBitmap().asImageBitmap()
                                            } catch (_: Exception) {
                                                // laid out 前调用 / 创建失败等；OOM 属 Error 不在此列，
                                                // 真发生时按系统行为崩
                                                null
                                            }
                                            if (shot != null) {
                                                // 主路径回收（连点穿透兜底）：覆盖旧值前 recycle。
                                                // 安全性：本回调在点击事件处理中，旧图最后一次 draw 在
                                                // 上一帧已完成，下一帧覆盖层已换新图，中间无 draw
                                                themeScreenshot.value?.asAndroidBitmap()?.let { old ->
                                                    if (!old.isRecycled) old.recycle()
                                                }
                                                themeScreenshot.value = shot
                                                themeRevealOrigin.value = origin
                                                themeSwitchSignal.value += 1
                                            }
                                        },
                                        onCalibrateRequested = onCalibrateRequested,
                                        onCalibrationReset = onCalibrationReset,
                                        adaptationProbeActive = adaptationProbeActive.value,
                                        onAdaptationToggle = onAdaptationToggle,
                                        onManualOverridesChange = onManualOverridesChange,
                                    )
                                    TAB_LOG -> LogContent()
                                }
                            }
                        }
                    }
                }

                // 校准对准视图：全屏遮罩 + 底部面板，压在 GL 表面之上
                CalibrationOverlay(
                    visible = calibrationActive.value,
                    facePresent = calibFacePresent.value,
                    onConfirm = onConfirmCalibration,
                    onCancel = onDismissCalibration,
                )

                // 开屏协议弹窗：组合序最后 = 视觉最上层；必须放 ThemeTransition 之外
                // （过渡层重组 content 会复制弹窗），AlertDialog 为独立窗口也不入主题截帧
                if (!agreementAccepted.value) {
                    AgreementDialog(
                        onAgree = onAgree,
                        onDecline = onDecline,
                        onViewFullLicense = onOpenFullLicense,
                        onViewPolyForm = onOpenPolyForm,
                    )
                }
                if (showFullLicense.value) {
                    LicenseDialog(onDismiss = onDismissLicense)
                }
                if (showPolyForm.value) {
                    PolyFormDialog(onDismiss = onDismissPolyForm)
                }

                // 防烧屏遮罩：Box 组合序最后 = 本窗口视觉最上层（协议弹窗为独立 Dialog 窗口，
                // 恒在其上）；必须在 ThemeTransition 之外（过渡层复制 content，遮罩入内会被
                // 截进主题截帧）——GlSurfaceHost 在根层不受影响（红线③）。
                // 移除回调上收 MainActivity（State 只读 + 回调上收约定）
                BurnInMask(visible = burnInMask.value, onDismiss = onBurnInMaskDismiss)
                }
            }
        }
    }
}

/**
 * 顶栏：标题随 Tab 切换，右上角图标语义 = 首页跳设置并滚到关于卡（[onOpenAbout]）/
 * 设置页跳首页 / 日志页无。
 *
 * 标题分支显式三分支 + else 抛错（fail-fast）：不用「else -> tab_log」兜底——未来加第 4 个
 * Tab 时静默错位到日志标题不报错，难排查。
 *
 * 颜色显式传 TopAppBarDefaults.topAppBarColors(...)：M3 默认容器色不含高档容器色，取色走
 * colorScheme 槽（XML 层 colorTopBar 仍供 statusBarColor）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArvTopBar(
    tabIndex: Int,
    onSelectTab: (Int) -> Unit,
    onOpenAbout: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                stringResource(
                    when (tabIndex) {
                        TAB_HOME -> R.string.app_name
                        TAB_SETTINGS -> R.string.tab_settings
                        TAB_LOG -> R.string.tab_log
                        else -> error("Unknown tabIndex: $tabIndex")
                    }
                )
            )
        },
        actions = {
            when (tabIndex) {
                TAB_HOME -> IconButton(onClick = onOpenAbout) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.tab_settings),
                    )
                }
                TAB_SETTINGS -> IconButton(onClick = { onSelectTab(TAB_HOME) }) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = stringResource(R.string.tab_home),
                    )
                }
                // TAB_LOG：右上角无图标（日志页无跳转目标；新增 Tab 记得在此补分支或有意留空）
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            // 取色走 ColorScheme 槽（XML 层 colorTopBar 仍供 themes.xml statusBarColor）；
            // 显式传 topAppBarColors——M3 默认不读高档容器色
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * 底部导航栏：首页 / 设置 / 日志。图标 = Home / Settings / AutoMirrored.List（RTL 正确）。
 * contentDescription = null：label 已承担语义（无障碍读文字即可）。
 * 容器色走主题 surfaceContainer（Theme 已映射 colorSurface，colors.xml 单一来源）。
 */
@Composable
private fun ArvBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == TAB_HOME,
            onClick = { onSelect(TAB_HOME) },
            icon = { Icon(imageVector = Icons.Filled.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_home)) },
        )
        NavigationBarItem(
            selected = selected == TAB_SETTINGS,
            onClick = { onSelect(TAB_SETTINGS) },
            icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_settings)) },
        )
        NavigationBarItem(
            selected = selected == TAB_LOG,
            onClick = { onSelect(TAB_LOG) },
            icon = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_log)) },
        )
    }
}

/**
 * GLSurfaceView 宿主：factory 一次性创建并 setRenderer（经 [onCreated] 回传给 Activity
 * 字段，beginTracking/stopTracking 的显式 onResume/onPause 仍走该字段）；update/onRelease
 * 均为空实现——尺寸变化（校准 1dp->全屏）只经 [modifier] 驱动。
 *
 * 位置红线：必须由 [AppRoot] 在 Box 根层无条件调用（一切 Tab 分支 / 条件块之外），
 * 否则切 Tab 会销毁本 View（EGL context 丢失），原因详见 AppRoot KDoc。
 *
 * 生命周期兜底（与按钮驱动并存，GLSurfaceView onResume/onPause 均幂等）：
 *  - onResume：仅当 tracking 活值为 true 才调 onResume()；
 *  - onPause：无条件调 onPause()——退后台必须停渲染循环，否则后台耗电。
 */
@Composable
private fun GlSurfaceHost(
    renderer: FaceRenderer,
    fullscreen: Boolean,
    tracking: State<Boolean>,
    onCreated: (GLSurfaceView) -> Unit,
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val viewState = remember { mutableStateOf<GLSurfaceView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                viewState.value = this
                onCreated(this)
            }
        },
        update = { /* 空实现：绝不在此 setRenderer（二次调用抛异常），尺寸变化只走 Modifier */ },
        onRelease = { /* 什么都不做：detach 由框架处理，Activity.onDestroy 兜底 */ },
    )

    val surfaceView = viewState.value
    if (surfaceView != null) {
        DisposableEffect(lifecycleOwner, surfaceView) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    // 仅追踪中恢复渲染循环
                    if (tracking.value) surfaceView.onResume()
                }

                override fun onPause(owner: LifecycleOwner) {
                    // 无条件暂停
                    surfaceView.onPause()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
}

/** 沿 ContextWrapper 链找宿主 Activity 的 Window（systemBars 同步用；找不到返回 null）。 */
private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
