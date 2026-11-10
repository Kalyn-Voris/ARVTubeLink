package com.exarry.arvtubelink.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.exarry.arvtubelink.network.VtsDiscoveryClient
import com.exarry.arvtubelink.network.VtsState
import com.exarry.arvtubelink.ui.theme.ARVTubeLinkTheme
import com.exarry.arvtubelink.ui.theme.LocalThemeIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tab 索引；tabIndex 由 MainActivity 持有，切换经 onSelectTab 上收 */
internal const val TAB_HOME = 0
internal const val TAB_SETTINGS = 1

/** Live2D 页（Purism Core 原生渲染 + 面捕参数驱动，内容见 [Live2DContent]）。 */
internal const val TAB_LIVE2D = 2
internal const val TAB_LOG = 3

private const val DISCOVERY_STATUS_CLEAR_MS = 4_000L

/** Compose 根：GL 宿主常驻 Box 根层之外无 if/when，切 Tab 不销毁 GLSurfaceView（EGL context 不丢） */
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor API 35 起 deprecated，minSdk 26 无替代 API
@Composable
fun AppRoot(
    arStatus: State<String>,
    vtsStatus: State<String>,
    vtsState: State<VtsState>,
    /** VtsState 无 Error 子类型，错误串单独标记 */
    vtsError: State<Boolean>,
    arReady: State<Boolean>,
    tracking: State<Boolean>,
    vtsConnected: State<Boolean>,
    surfaceFullscreen: State<Boolean>,
    calibrationActive: State<Boolean>,
    calibFacePresent: State<Boolean?>,
    burnInMask: State<Boolean>,
    onBurnInMaskDismiss: () -> Unit,
    tabIndex: State<Int>,
    /** Live2D 沉浸模式：false = 顶栏/底栏收起、系统栏隐藏，只留模型画布 */
    live2dUiVisible: State<Boolean>,
    /** 用户在 Live2D 画布上的触摸：唤醒沉浸 UI 并重启空闲计时 */
    onLive2dUserActivity: () -> Unit,
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
    /** 自动发现命中且 API 开启时先于 onConnectVts 调用 */
    onAutoConnectStarted: () -> Unit,
    /** 自动发现客户端，只持 applicationContext */
    discoveryClient: VtsDiscoveryClient,
    onClearToken: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onOpenAbout: () -> Unit,
    onAboutScrollConsumed: () -> Unit,
    onThemeFollowSystemChange: (Boolean) -> Unit,
    onAmoledBlackChange: (Boolean) -> Unit,
    onCalibrateRequested: () -> Unit,
    onConfirmCalibration: () -> Unit,
    onDismissCalibration: () -> Unit,
    showCalibrationGuide: State<Boolean>,
    /** 零点校准完成标记，源于持久化校准数据 */
    calibrationDone: State<Boolean>,
    /** 引导卡开始校准：未追踪时先自动开追踪 */
    onStartCalibrationFromHome: () -> Unit,
    /** 引导卡稍后：落盘忽略标记 */
    onDismissCalibrationGuide: () -> Unit,
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
    /** 自适应状态文本，null 不显示 */
    adaptationStatus: State<String?>? = null,
    /** 探测值生效中，手动映射区禁用 */
    adaptationProbeActive: State<Boolean>,
    onAdaptationToggle: (Boolean) -> Unit,
    /** 拖动中即时回传的成品 MapperOverrides */
    onManualOverridesChange: (MapperOverrides) -> Unit,
    /** 语言切换：写 prefs + setApplicationLocales */
    onLanguageChange: (String) -> Unit,
    /** 写 prefs + 取消/重启空闲计时器 */
    onBurnInMaskEnabledChange: (Boolean) -> Unit,
) {
    // Tab 互切保留；Activity 重建以 defaultIp/defaultPort 重新初始化
    val ipState = remember { mutableStateOf(defaultIp) }
    val portState = remember { mutableStateOf(defaultPort.toString()) }

    // 输入即落盘；端口非法（清空/脏字符）不覆盖上次有效值
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

    // 状态 AppRoot 级 remember；搜索协程随组合取消，套接字与 MulticastLock 由客户端 finally 释放
    val discoveryState = remember { mutableStateOf<DiscoveryUiState>(DiscoveryUiState.Idle) }
    val discoveryScope = rememberCoroutineScope()

    // 每秒取一次真实送达帧数进 30 点环形缓冲，采样器随组合生命周期
    val telemetry = rememberTelemetryState(renderer = renderer, tracking = tracking.value)

    // 警示行仅覆盖等待授权/认证失败/重连/onError
    val vtsWarningVisible = vtsError.value || when (vtsState.value) {
        VtsState.WaitingForTokenApproval,
        VtsState.AuthenticationFailed,
        is VtsState.Reconnecting -> true
        else -> false
    }
    // 故障态取 error 色；等待授权/重连为过程态
    val vtsWarningIsError = vtsError.value ||
        vtsState.value is VtsState.AuthenticationFailed

    // 握手态含等待授权弹窗与重连，AuthenticationFailed 不算
    val vtsInFlight = when (vtsState.value) {
        VtsState.Connecting,
        VtsState.WaitingForTokenApproval,
        VtsState.Authenticating,
        is VtsState.Reconnecting -> true
        else -> false
    }

    // appPrefs 非 Compose 可观察源，以当前 Tab 为 key 重读
    val configuredHz = remember(tabIndex.value) {
        settingsDeps.appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ)
    }

    // Searching/Scanning 防重入；API 未开启时只填地址不发起连接
    val runAutoDiscover = {
        val busy = discoveryState.value == DiscoveryUiState.Searching ||
            discoveryState.value == DiscoveryUiState.Scanning
        if (!busy) {
            discoveryState.value = DiscoveryUiState.Searching
            discoveryScope.launch {
                when (val result = discoveryClient.discover()) {
                    is VtsDiscoveryClient.DiscoveryResult.Found -> {
                        ipState.value = result.ip
                        portState.value = result.port.toString()
                        if (result.isApiActive) {
                            onAutoConnectStarted()
                            onConnectVts(result.ip, result.port.toString())
                            discoveryState.value =
                                DiscoveryUiState.Found(result.ip, result.port, autoConnecting = true)
                        } else {
                            discoveryState.value =
                                DiscoveryUiState.Found(result.ip, result.port, autoConnecting = false)
                        }
                    }
                    is VtsDiscoveryClient.DiscoveryResult.Failed -> {
                        discoveryState.value = DiscoveryUiState.Failed(result.reason)
                    }
                }
            }
        }
    }

    // Found/Failed 定时回 Idle；Searching 满 TCP_FALLBACK_DELAY_MS 转 Scanning，key 挂状态对象
    LaunchedEffect(discoveryState.value) {
        when (discoveryState.value) {
            is DiscoveryUiState.Found, is DiscoveryUiState.Failed -> {
                delay(DISCOVERY_STATUS_CLEAR_MS)
                discoveryState.value = DiscoveryUiState.Idle
            }
            DiscoveryUiState.Searching -> {
                delay(VtsDiscoveryClient.TCP_FALLBACK_DELAY_MS)
                if (discoveryState.value == DiscoveryUiState.Searching) {
                    discoveryState.value = DiscoveryUiState.Scanning
                }
            }
            else -> {}
        }
    }

    // 过渡状态本地自持：截图必须先于 isDark 翻转，signal 每次 +1，0 = 首帧不动画
    val themeRevealOrigin = remember { mutableStateOf<Offset?>(null) }
    val themeScreenshot = remember { mutableStateOf<ImageBitmap?>(null) }
    val themeSwitchSignal = remember { mutableStateOf(0) }

    // OFF 强制深色；isDark 在顶层求值，切换即整树重组
    val isDark = if (themeFollowSystem.value) isSystemInDarkTheme() else true

    // amoledBlack 仅深色下生效（Theme 内部判定）
    ARVTubeLinkTheme(darkTheme = isDark, amoledBlack = amoledBlack.value) {
        // Surface 提供底色兜底
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalThemeIsDark provides isDark) {
                // statusBar 取 surfaceContainerHigh、navBar 取 surfaceContainer
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

                // 遮罩期隐藏系统栏：API≤34 非 EtE 窗口靠 hide 扩展进栏区；下拉时 transient overlay 不触发 relayout
                val burnInMaskVisible = burnInMask.value
                // Live2D 沉浸：顶栏/底栏收起的同时系统栏一并隐藏，触摸唤醒后恢复
                val live2dImmersive = tabIndex.value == TAB_LIVE2D && !live2dUiVisible.value
                LaunchedEffect(burnInMaskVisible, live2dImmersive) {
                    view.context.findWindow()?.let { window ->
                        WindowCompat.getInsetsController(window, view).apply {
                            systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (burnInMaskVisible || live2dImmersive) {
                                hide(WindowInsetsCompat.Type.systemBars())
                            } else {
                                show(WindowInsetsCompat.Type.systemBars())
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                // 唯一 GLSurfaceView；ThemeTransition 复制 content，包 GL = 双实例
                GlSurfaceHost(
                    renderer = renderer,
                    fullscreen = surfaceFullscreen.value,
                    tracking = tracking,
                    onCreated = onGlSurfaceCreated,
                    // zIndex(-1f) 把 interop View 压到 Compose 内容之下
                    modifier = Modifier
                        .zIndex(-1f)
                        .then(
                            if (surfaceFullscreen.value) Modifier.fillMaxSize()
                            else Modifier.requiredSize(1.dp)
                        ),
                )

                // 校准期间 Scaffold 整体让位，GL 在外不受影响
                ThemeTransition(
                    isDark = isDark,
                    screenshot = themeScreenshot.value,
                    revealOrigin = themeRevealOrigin.value,
                    transitionSignal = themeSwitchSignal.value,
                    onTransitionFinished = {
                        // origin 复位供下一轮；screenshot 置 null 移除覆盖层并回收 Bitmap
                        themeRevealOrigin.value = null
                        themeScreenshot.value = null
                    },
                ) { _ ->
                    if (!calibrationActive.value) {
                        // Live2D 沉浸：顶栏/底栏平滑收起（innerPadding 随之归零，画面自动铺满）
                        val chromeVisible =
                            tabIndex.value != TAB_LIVE2D || live2dUiVisible.value
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            topBar = {
                                AnimatedVisibility(
                                    visible = chromeVisible,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically(),
                                ) {
                                    ArvTopBar(
                                        tabIndex = tabIndex.value,
                                        onSelectTab = onSelectTab,
                                        onOpenAbout = onOpenAbout,
                                    )
                                }
                            },
                            bottomBar = {
                                AnimatedVisibility(
                                    visible = chromeVisible,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically(),
                                ) {
                                    ArvBottomBar(
                                        selected = tabIndex.value,
                                        onSelect = onSelectTab,
                                    )
                                }
                            },
                        ) { innerPadding ->
                            // Live2D 页忽略内边距：改尺寸会让 GLSurfaceView 反复 setFixedSize 并触发 surfaceChanged
                            val contentModifier =
                                if (tabIndex.value == TAB_LIVE2D) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.padding(innerPadding).fillMaxSize()
                                }
                            Box(modifier = contentModifier) {
                                when (tabIndex.value) {
                                    TAB_HOME -> HomeContent(
                                        arStatus = arStatus.value,
                                        arReady = arReady.value,
                                        adaptationStatus = adaptationStatus?.value,
                                        tracking = tracking.value,
                                        showCalibrationGuide = showCalibrationGuide.value,
                                        ipState = ipState,
                                        portState = portState,
                                        discoveryState = discoveryState.value,
                                        onAutoDiscover = runAutoDiscover,
                                        onCheckAr = onCheckAr,
                                        onStartTracking = onStartTracking,
                                        onStopTracking = onStopTracking,
                                        onConnectVts = onConnectVts,
                                        onClearToken = onClearToken,
                                        onStartCalibration = onStartCalibrationFromHome,
                                        onDismissCalibrationGuide = onDismissCalibrationGuide,
                                        telemetry = telemetry,
                                        configuredHz = configuredHz,
                                        vtsConnected = vtsConnected.value,
                                        vtsInFlight = vtsInFlight,
                                        vtsAwaitingApproval = vtsState.value ==
                                            VtsState.WaitingForTokenApproval,
                                        calibrated = calibrationDone.value,
                                        vtsWarning = vtsStatus.value
                                            .takeIf { vtsWarningVisible },
                                        vtsWarningIsError = vtsWarningIsError,
                                    )
                                    TAB_SETTINGS -> SettingsContent(
                                        deps = settingsDeps,
                                        aboutScrollSignal = aboutScrollSignal.value,
                                        onAboutScrollConsumed = onAboutScrollConsumed,
                                        onThemeFollowSystemChange = onThemeFollowSystemChange,
                                        onAmoledBlackChange = onAmoledBlackChange,
                                        onThemeRevealOrigin = { origin ->
                                            // 此刻重组未发生，截图仍是旧主题；截图失败静默降级为无动画
                                            val shot = try {
                                                view.drawToBitmap().asImageBitmap()
                                            } catch (_: Exception) {
                                                // laid out 前调用或创建失败；OOM 属 Error 不在此列
                                                null
                                            }
                                            if (shot != null) {
                                                // 覆盖旧值前 recycle：旧图最后一次 draw 已在上一帧完成
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
                                        onLanguageChange = onLanguageChange,
                                        onBurnInMaskEnabledChange = onBurnInMaskEnabledChange,
                                    )
                                    TAB_LIVE2D -> Live2DContent(
                                        modifier = Modifier.fillMaxSize(),
                                        // 面捕参数源：只读快照，映射算法仍全在 FaceRenderer 内
                                        faceRenderer = renderer,
                                        // 点击/拖动画布：宿主唤醒沉浸 UI 并重启 2s 计时
                                        onUserActivity = onLive2dUserActivity,
                                        // 沉浸模式下页内控件一并淡出
                                        chromeVisible = live2dUiVisible.value,
                                        // 画布恒铺满，只有悬浮控件让开 App 栏
                                        chromePadding = innerPadding,
                                    )
                                    TAB_LOG -> LogContent()
                                }
                            }
                        }
                    }
                }

                // 校准视图压在 GL 表面之上
                CalibrationOverlay(
                    visible = calibrationActive.value,
                    facePresent = calibFacePresent.value,
                    onConfirm = onConfirmCalibration,
                    onCancel = onDismissCalibration,
                )

                // 必须放 ThemeTransition 之外（过渡层复制 content），Dialog 为独立窗口不入截帧
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

                // Box 最后子项 = 视觉最上层；置 ThemeTransition 之外避免被截进主题帧
                BurnInMask(visible = burnInMask.value, onDismiss = onBurnInMaskDismiss)
                }
            }
        }
    }
}

/** 顶栏：标题随 Tab 切换；首页 info 图标跳设置并滚到关于卡，设置页回首页 */
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
                        TAB_LIVE2D -> R.string.tab_live2d
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
                // 日志页无右上角图标
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            // M3 默认不读高档容器色
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** 底部导航：contentDescription=null，label 承担无障碍语义 */
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
        // Live2D 页图标用 Face，不额外引入图标依赖
        NavigationBarItem(
            selected = selected == TAB_LIVE2D,
            onClick = { onSelect(TAB_LIVE2D) },
            icon = { Icon(imageVector = Icons.Filled.Face, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_live2d)) },
        )
        NavigationBarItem(
            selected = selected == TAB_LOG,
            onClick = { onSelect(TAB_LOG) },
            icon = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_log)) },
        )
    }
}

/** GLSurfaceView 宿主：setRenderer 只在 factory，update/onRelease 空实现，onResume 仅追踪中调用 */
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
        update = { /* 空实现：setRenderer 二次调用抛异常，尺寸变化只走 Modifier */ },
        onRelease = { /* detach 由框架处理 */ },
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

/** 沿 ContextWrapper 链找宿主 Activity 的 Window */
private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
