package com.exarry.arvtubelink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.exarry.arvtubelink.adapt.AdaptationController
import com.exarry.arvtubelink.adapt.AdaptationState
import com.exarry.arvtubelink.adapt.AdaptationStorage
import com.exarry.arvtubelink.ar.ArEngineState
import com.exarry.arvtubelink.ar.ArSessionManager
import com.exarry.arvtubelink.ar.FaceRenderer
import com.exarry.arvtubelink.mapping.BlendShapeMapper
import com.exarry.arvtubelink.mapping.CalibrationStorage
import com.exarry.arvtubelink.mapping.MapperOverrides
import com.exarry.arvtubelink.mapping.Smoother
import com.exarry.arvtubelink.network.VtsDiscoveryClient
import com.exarry.arvtubelink.network.VtsState
import com.exarry.arvtubelink.network.VtsWsClient
import com.exarry.arvtubelink.ui.AppRoot
import com.exarry.arvtubelink.ui.CalibrationOverlay
import com.exarry.arvtubelink.ui.DEFAULT_BURN_IN_MASK_ENABLED
import com.exarry.arvtubelink.ui.DEFAULT_SEND_FREQUENCY_HZ
import com.exarry.arvtubelink.ui.KEY_APP_LANGUAGE
import com.exarry.arvtubelink.ui.KEY_BURN_IN_MASK_ENABLED
import com.exarry.arvtubelink.ui.KEY_ENABLE_ADAPTATION
import com.exarry.arvtubelink.ui.KEY_ENABLE_DEBUG_LOGS
import com.exarry.arvtubelink.ui.KEY_ENABLE_ONE_EURO_FILTER
import com.exarry.arvtubelink.ui.KEY_FACE_MIRROR
import com.exarry.arvtubelink.ui.KEY_MANUAL_BROW_SENSITIVITY
import com.exarry.arvtubelink.ui.KEY_MANUAL_MOUTHOPEN_SENSITIVITY
import com.exarry.arvtubelink.ui.KEY_MANUAL_MOUTHSMILE_NEUTRAL
import com.exarry.arvtubelink.ui.LANGUAGE_SYSTEM
import com.exarry.arvtubelink.ui.MANUAL_BROW_DEVICE_BASELINE
import com.exarry.arvtubelink.ui.MANUAL_MOUTHOPEN_DEVICE_BASELINE
import com.exarry.arvtubelink.ui.KEY_SEND_FREQUENCY_HZ
import com.exarry.arvtubelink.ui.KEY_THEME_AMOLED_BLACK
import com.exarry.arvtubelink.ui.KEY_THEME_FOLLOW_SYSTEM
import com.exarry.arvtubelink.ui.KEY_VTS_IP
import com.exarry.arvtubelink.ui.KEY_VTS_PORT
import com.exarry.arvtubelink.ui.SettingsDeps
import com.exarry.arvtubelink.ui.TAB_HOME
import com.exarry.arvtubelink.ui.TAB_LIVE2D
import com.exarry.arvtubelink.ui.TAB_SETTINGS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.exarry.arvtubelink.utils.InAppLog

/** UI 装配层，不参与逐帧；帧链路 FaceRenderer(GL) -> mapper -> Smoother -> 30Hz -> VtsWsClient(IO) */
class MainActivity : AppCompatActivity() {

    private val arSessionManager by lazy { ArSessionManager(this) }

    // mapper 跨线程状态 @Volatile：UI 写、GL 读
    private val mapper = BlendShapeMapper()

    private val smoother by lazy { Smoother() }

    // lazy：构造期 base context 未 attach，不能取 ApplicationContext
    private val calibrationStorage by lazy { CalibrationStorage(this) }

    private val appPrefs by lazy {
        getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
    }

    // 状态回调来自 OkHttp 线程，经 runOnUiThread 写 Compose state
    private val vtsClient by lazy {
        VtsWsClient(
            context = this,
            onVtsStateChanged = { state ->
                runOnUiThread {
                    vtsStatusState.value = vtsStateLabel(state)
                    vtsConnectedState.value = state is VtsState.Authenticated
                    vtsStateState.value = state
                    vtsErrorState.value = false
                    when (state) {
                        VtsState.Authenticated -> if (pendingAutoConnectToast) {
                            pendingAutoConnectToast = false
                            Toast.makeText(this, R.string.discovery_toast_connected, Toast.LENGTH_SHORT).show()
                        }
                        VtsState.Disconnected -> pendingAutoConnectToast = false
                        else -> {}
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    vtsStatusState.value = getString(R.string.vts_status_error, message)
                    vtsErrorState.value = true
                }
            },
            onModelInfo = { modelName, modelID ->
                runOnUiThread { handleModelInfo(modelName, modelID) }
            }
        )
    }

    // 自动发现：UDP 广播 + TCP 兜底探测；缓存地址在 discover() 时从 prefs 惰性读取
    private val discoveryClient by lazy {
        VtsDiscoveryClient(
            context = this,
            cachedAddressProvider = {
                val ip = appPrefs.getString(KEY_VTS_IP, null)
                val port = appPrefs.getInt(KEY_VTS_PORT, 0)
                if (ip.isNullOrBlank() || port == 0) null else ip to port
            },
        )
    }

    private var pendingAutoConnectToast = false

    private val adaptationStorage by lazy { AdaptationStorage(this) }

    private val adaptationManualPrefs by lazy {
        getSharedPreferences(PREFS_ADAPTATION_MANUAL, Context.MODE_PRIVATE)
    }

    private val adaptationController by lazy {
        AdaptationController(
            vtsClient = vtsClient,
            storage = adaptationStorage,
            onState = { state -> runOnUiThread { onAdaptationState(state) } },
            onOverridesReady = { overrides, _, _ ->
                probeOverridesActiveState.value = true
                mapper.updateOverrides(overrides)
            }
        )
    }

    private var lastModelName: String? = null
    private var lastModelId: String? = null

    private val probeOverridesActiveState = mutableStateOf(false)

    private val adaptationStatusState = mutableStateOf<String?>(null)

    private var lastAdaptationIsSwitch = false

    private val attemptedModelIds = mutableSetOf<String>()

    // 字段初始化早于 attachBaseContext，getString 取默认资源：初值空串，onCreate 回填
    private val arStatusState = mutableStateOf("")
    private val vtsStatusState = mutableStateOf("")
    private val trackingState = mutableStateOf(false)
    private val surfaceFullscreenState = mutableStateOf(false)

    private val vtsConnectedState = mutableStateOf(false)

    private val vtsStateState = mutableStateOf<VtsState>(VtsState.Disconnected)

    private val vtsErrorState = mutableStateOf(false)

    private val arReadyState = mutableStateOf(false)

    private val tabIndexState = mutableStateOf(TAB_HOME)

    private val themeFollowSystemState = mutableStateOf(true)

    private val amoledBlackState = mutableStateOf(false)

    private val aboutScrollSignalState = mutableStateOf(0)

    private val calibrationActiveState = mutableStateOf(false)
    private val calibFacePresentState = mutableStateOf<Boolean?>(null)

    // mapper.calibrationData 是 @Volatile，Compose 观察不到变化，需投影成 State
    private val calibrationDoneState = mutableStateOf(false)

    private val calibrationGuideDismissedState = mutableStateOf(false)

    private val showCalibrationGuideState = derivedStateOf {
        !calibrationDoneState.value && !calibrationGuideDismissedState.value
    }

    private val agreementAcceptedState = mutableStateOf(false)
    private val showFullLicenseState = mutableStateOf(false)
    private val showPolyFormState = mutableStateOf(false)

    private val dimHandler = Handler(Looper.getMainLooper())
    // 空闲到点：普通页面落防烧屏黑屏；Live2D 页改走沉浸模式（绝不全黑）
    private val dimRunnable = Runnable { onIdleTimeout() }

    @Volatile
    private var burnInMaskEnabled = true

    private val burnInMaskState = mutableStateOf(false)

    // Live2D 页沉浸模式：false = App 顶栏/底栏与系统栏收起，只留模型
    private val live2dUiVisibleState = mutableStateOf(true)

    private var adaptationRunning = false

    // 首页引导触发权限申请后，授权回调据此续上校准
    private var pendingCalibrationFromHome = false

    // GLSurfaceView 由 AppRoot 的 AndroidView factory 创建后经 onGlSurfaceCreated 回传
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var faceRenderer: FaceRenderer

    // API < 33 须在 super.attachBaseContext 之前 setApplicationLocales，prefs 走 newBase
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val tag = newBase.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
            AppCompatDelegate.setApplicationLocales(localesFor(tag))
        }
        super.attachBaseContext(newBase)
    }

    // API 33+ 系统 LocaleManager 持久化，<33 写 AppCompat 静态 sRequestedAppLocales
    private fun applyAppLanguage(tag: String) {
        appPrefs.edit().putString(KEY_APP_LANGUAGE, tag).apply()
        AppCompatDelegate.setApplicationLocales(localesFor(tag))
    }

    private fun localesFor(tag: String): LocaleListCompat =
        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 须在任何组件产出日志前就位
        InAppLog.setDebugEnabled(appPrefs.getBoolean(KEY_ENABLE_DEBUG_LOGS, false))

        // 强制深色须在 super.onCreate 前 applyStyle，覆盖首帧 windowBackground
        val followSystem = appPrefs.getBoolean(KEY_THEME_FOLLOW_SYSTEM, true)
        themeFollowSystemState.value = followSystem
        amoledBlackState.value = appPrefs.getBoolean(KEY_THEME_AMOLED_BLACK, false)
        if (!followSystem) {
            theme.applyStyle(R.style.ThemeOverlay_ARVTubeLink_ForceDark, true)
        }
        super.onCreate(savedInstanceState)

        // 华为设备息屏/后台会冻结进程，OkHttp 不收下行
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        agreementAcceptedState.value = appPrefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false)

        arStatusState.value = getString(R.string.ar_status_not_detected)
        vtsStatusState.value = getString(R.string.vts_status_disconnected)

        if (savedInstanceState == null) {
            appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false).apply()
            calibrationGuideDismissedState.value = false
        } else {
            calibrationGuideDismissedState.value =
                appPrefs.getBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false)
        }

        val persistedIp = appPrefs.getString(KEY_VTS_IP, null) ?: DEFAULT_IP
        val persistedPort = appPrefs.getInt(KEY_VTS_PORT, DEFAULT_PORT)

        faceRenderer = FaceRenderer(
            manager = arSessionManager,
            displayRotation = { displayRotation() },
            mapper = mapper,
            smoother = smoother,
            vtsClient = vtsClient,
            onFacePresenceChanged = { present -> calibFacePresentState.value = present }
        )

        val settingsDeps = SettingsDeps(
            mapper = mapper,
            smoother = smoother,
            calibrationStorage = calibrationStorage,
            appPrefs = appPrefs,
            adaptationManualPrefs = adaptationManualPrefs,
            faceRenderer = faceRenderer
        )

        setContent {
            AppRoot(
                arStatus = arStatusState,
                vtsStatus = vtsStatusState,
                vtsState = vtsStateState,
                vtsError = vtsErrorState,
                arReady = arReadyState,
                tracking = trackingState,
                vtsConnected = vtsConnectedState,
                surfaceFullscreen = surfaceFullscreenState,
                calibrationActive = calibrationActiveState,
                calibFacePresent = calibFacePresentState,
                burnInMask = burnInMaskState,
                onBurnInMaskDismiss = { burnInMaskState.value = false },
                tabIndex = tabIndexState,
                live2dUiVisible = live2dUiVisibleState,
                onLive2dUserActivity = { wakeLive2dUi() },
                themeFollowSystem = themeFollowSystemState,
                amoledBlack = amoledBlackState,
                aboutScrollSignal = aboutScrollSignalState,
                settingsDeps = settingsDeps,
                renderer = faceRenderer,
                defaultIp = persistedIp,
                defaultPort = persistedPort,
                onGlSurfaceCreated = { glSurfaceView = it },
                onCheckAr = { checkArEngine() },
                onStartTracking = { startTracking() },
                onStopTracking = { stopTracking() },
                onConnectVts = { ip, portText -> connectVts(ip, portText) },
                onAutoConnectStarted = { pendingAutoConnectToast = true },
                discoveryClient = discoveryClient,
                onClearToken = {
                    vtsClient.clearSavedToken()
                    Toast.makeText(this, R.string.toast_token_cleared, Toast.LENGTH_SHORT).show()
                },
                onSelectTab = { selectTab(it) },
                onOpenAbout = {
                    selectTab(TAB_SETTINGS)
                    aboutScrollSignalState.value++
                },
                onAboutScrollConsumed = { aboutScrollSignalState.value = 0 },
                onThemeFollowSystemChange = { follow ->
                    themeFollowSystemState.value = follow
                    appPrefs.edit().putBoolean(KEY_THEME_FOLLOW_SYSTEM, follow).apply()
                },
                onAmoledBlackChange = { enabled ->
                    amoledBlackState.value = enabled
                    appPrefs.edit().putBoolean(KEY_THEME_AMOLED_BLACK, enabled).apply()
                },
                onCalibrateRequested = { enterCalibrationMode() },
                onConfirmCalibration = { onConfirmCalibration() },
                onDismissCalibration = { closeCalibration() },
                showCalibrationGuide = showCalibrationGuideState,
                calibrationDone = calibrationDoneState,
                onStartCalibrationFromHome = { startCalibrationFromHome() },
                onDismissCalibrationGuide = { dismissCalibrationGuide() },
                onCalibrationReset = {
                    calibrationDoneState.value = false
                    calibrationGuideDismissedState.value = false
                    appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false).apply()
                },
                agreementAccepted = agreementAcceptedState,
                showFullLicense = showFullLicenseState,
                onAgree = {
                    appPrefs.edit().putBoolean(KEY_AGREEMENT_ACCEPTED, true).apply()
                    agreementAcceptedState.value = true
                },
                onDecline = { finish() },
                onOpenFullLicense = { showFullLicenseState.value = true },
                onDismissLicense = { showFullLicenseState.value = false },
                showPolyForm = showPolyFormState,
                onOpenPolyForm = { showPolyFormState.value = true },
                onDismissPolyForm = { showPolyFormState.value = false },
                adaptationStatus = adaptationStatusState,
                adaptationProbeActive = probeOverridesActiveState,
                onAdaptationToggle = { enabled -> onAdaptationToggle(enabled) },
                onManualOverridesChange = { overrides ->
                    probeOverridesActiveState.value = false
                    mapper.updateOverrides(overrides)
                },
                onLanguageChange = { tag -> applyAppLanguage(tag) },
                onBurnInMaskEnabledChange = { enabled -> setBurnInMaskEnabled(enabled) },
            )
        }

        faceRenderer.setSendFrequencyHz(appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ))

        smoother.setOneEuroFilterEnabled(appPrefs.getBoolean(KEY_ENABLE_ONE_EURO_FILTER, true))

        mapper.faceMirrorEnabled = appPrefs.getBoolean(KEY_FACE_MIRROR, false)

        // 须在任何 scheduleIdleDim 之前就位
        burnInMaskEnabled = appPrefs.getBoolean(KEY_BURN_IN_MASK_ENABLED, DEFAULT_BURN_IN_MASK_ENABLED)

        loadPersistedMappingConfig()
    }

    // SharedPreferences 首次读取含磁盘 IO，放 IO 协程
    private fun loadPersistedMappingConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            val calibration = calibrationStorage.load()
            val sensitivities = calibrationStorage.loadSensitivities()
            mapper.updateCalibration(calibration)
            mapper.angleSensitivity = sensitivities.angle
                .coerceIn(BlendShapeMapper.SENSITIVITY_MIN, BlendShapeMapper.SENSITIVITY_MAX)
            mapper.positionSensitivity = sensitivities.position
                .coerceIn(BlendShapeMapper.SENSITIVITY_MIN, BlendShapeMapper.SENSITIVITY_MAX)
            mapper.expressionSensitivity = sensitivities.expression
                .coerceIn(BlendShapeMapper.SENSITIVITY_MIN, BlendShapeMapper.SENSITIVITY_MAX)
            val calibrated = calibration.isCalibrated
            withContext(Dispatchers.Main) {
                calibrationDoneState.value = calibrated
            }
        }
    }

    private fun displayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    // 置渲染器 @Volatile 标志 + 隐藏 Tab 骨架 + GLView 全屏 + 挂起防烧屏
    private fun enterCalibrationMode() {
        if (calibrationActiveState.value) return
        if (!trackingState.value) {
            Toast.makeText(this, R.string.toast_calibrate_requires_tracking, Toast.LENGTH_SHORT).show()
            return
        }
        faceRenderer.setCalibrationMode(true)
        calibFacePresentState.value = null
        surfaceFullscreenState.value = true
        calibrationActiveState.value = true
        cancelIdleDim()
    }

    // 确认/取消/返回/stopTracking/onDestroy 统一收尾，幂等
    private fun closeCalibration() {
        if (!calibrationActiveState.value) return
        calibrationActiveState.value = false
        faceRenderer.setCalibrationMode(false)
        calibFacePresentState.value = null
        surfaceFullscreenState.value = false
        if (trackingState.value && !adaptationRunning) {
            scheduleIdleDim()
        }
    }

    private fun onConfirmCalibration() {
        if (calibFacePresentState.value != true) {
            Toast.makeText(this, R.string.toast_calibrate_no_face, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = mapper.latestRawSnapshot()
        if (snapshot == null) {
            Toast.makeText(this, R.string.toast_calibrate_no_frame, Toast.LENGTH_SHORT).show()
            return
        }
        val newCalibration = mapper.captureCurrentAsBase(snapshot)
        smoother.reset()
        lifecycleScope.launch { calibrationStorage.save(newCalibration) }
        calibrationDoneState.value = true
        Toast.makeText(this, R.string.toast_calibrate_saved, Toast.LENGTH_SHORT).show()
        closeCalibration()
    }

    private fun startCalibrationFromHome() {
        if (calibrationActiveState.value) return
        if (trackingState.value) {
            enterCalibrationMode()
            return
        }
        if (!arSessionManager.hasCameraPermission()) {
            pendingCalibrationFromHome = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
            return
        }
        Toast.makeText(this, getString(R.string.calib_guide_starting), Toast.LENGTH_SHORT).show()
        beginTracking()
        if (trackingState.value) {
            enterCalibrationMode()
        }
    }

    private fun dismissCalibrationGuide() {
        calibrationGuideDismissedState.value = true
        appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, true).apply()
    }

    private fun checkArEngine() {
        arStatusState.value = getString(R.string.ar_status_checking)
        lifecycleScope.launch {
            val state = arSessionManager.checkAvailability()
            arReadyState.value = state is ArEngineState.Available
            val engineText = getString(
                when (state) {
                    ArEngineState.Available -> R.string.ar_engine_available
                    ArEngineState.NotInstalled -> R.string.ar_engine_not_installed
                    ArEngineState.Unsupported -> R.string.ar_engine_unsupported
                }
            )
            val permissionText = getString(
                if (arSessionManager.hasCameraPermission()) {
                    R.string.camera_permission_granted
                } else {
                    R.string.camera_permission_denied
                }
            )
            arStatusState.value = getString(R.string.ar_status_format, engineText, permissionText)
        }
    }

    private fun connectVts(ip: String, portText: String) {
        val ipTrimmed = ip.trim()
        val port = portText.trim().toIntOrNull()
        if (ipTrimmed.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_ip, Toast.LENGTH_SHORT).show()
            return
        }
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, R.string.toast_invalid_port, Toast.LENGTH_SHORT).show()
            return
        }
        // connect 异步，状态由 onVtsStateChanged 回调驱动
        vtsStatusState.value = getString(R.string.vts_status_connecting)
        vtsClient.connect(ipTrimmed, port, token = null)
    }

    private fun vtsStateLabel(state: VtsState): String = when (state) {
        VtsState.Disconnected -> getString(R.string.vts_status_disconnected)
        VtsState.Connecting -> getString(R.string.vts_status_connecting)
        VtsState.WaitingForTokenApproval -> getString(R.string.vts_status_waiting_approval)
        VtsState.Authenticating -> getString(R.string.vts_status_authenticating)
        VtsState.AuthenticationFailed -> getString(R.string.vts_status_auth_failed)
        VtsState.Authenticated -> getString(R.string.vts_status_authenticated)
        is VtsState.Reconnecting -> getString(R.string.vts_status_reconnecting, state.attempt)
    }

    // 开关开且 modelID 非空：命中 adaptation_prefs 直接应用，否则启动探测
    private fun handleModelInfo(modelName: String?, modelID: String?) {
        val id = modelID?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        val name = modelName?.trim().takeUnless { it.isNullOrEmpty() } ?: id
        lastModelId = id
        lastModelName = name
        if (!appPrefs.getBoolean(KEY_ENABLE_ADAPTATION, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val stored = adaptationStorage.load()
            withContext(Dispatchers.Main) {
                if (stored != null && stored.modelId == id) {
                    probeOverridesActiveState.value = true
                    mapper.updateOverrides(stored.overrides)
                    InAppLog.i(TAG, "自适应缓存命中（model=$name），直接应用已存覆盖")
                } else if (id in attemptedModelIds) {
                    InAppLog.i(TAG, "自适应：本会话已尝试过 model=$name（id=$id），跳过自动重探")
                } else {
                    attemptedModelIds.add(id)
                    lastAdaptationIsSwitch = stored != null
                    adaptationController.start(id, name, isSwitch = stored != null)
                }
            }
        }
    }

    // Running 暂停推流并挂起防烧屏；Done/Failed 重启计时，Idle 不重启
    private fun onAdaptationState(state: AdaptationState) {
        adaptationRunning = state is AdaptationState.Running
        when (state) {
            is AdaptationState.Idle -> {
                faceRenderer.setStreamingPaused(false)
                adaptationStatusState.value = null
            }
            is AdaptationState.Running -> {
                faceRenderer.setStreamingPaused(true)
                cancelIdleDim()
                if (adaptationStatusState.value == null) {
                    val textRes = if (lastAdaptationIsSwitch) {
                        R.string.adaptation_running_switch
                    } else {
                        R.string.adaptation_running_first
                    }
                    Toast.makeText(this, getString(textRes), Toast.LENGTH_SHORT).show()
                }
                adaptationStatusState.value =
                    getString(R.string.adaptation_running_first) + " " +
                        getString(
                            R.string.adaptation_running_progress,
                            state.index,
                            state.total,
                            state.skipped
                        )
            }
            is AdaptationState.Done -> {
                faceRenderer.setStreamingPaused(false)
                adaptationStatusState.value = null
                Toast.makeText(this, getString(R.string.adaptation_done), Toast.LENGTH_SHORT).show()
                restartIdleDimIfTracking()
            }
            is AdaptationState.Failed -> {
                faceRenderer.setStreamingPaused(false)
                adaptationStatusState.value = null
                probeOverridesActiveState.value = false
                applyManualOverrides()
                Toast.makeText(this, getString(R.string.adaptation_failed), Toast.LENGTH_SHORT).show()
                restartIdleDimIfTracking()
            }
        }
    }

    private fun restartIdleDimIfTracking() {
        if (trackingState.value && !calibrationActiveState.value) {
            scheduleIdleDim()
        }
    }

    // 先落盘再走分支：ON 分支读同一键做早退判定
    private fun onAdaptationToggle(enabled: Boolean) {
        appPrefs.edit().putBoolean(KEY_ENABLE_ADAPTATION, enabled).apply()
        if (enabled) {
            attemptedModelIds.clear()
            if (vtsClient.state is VtsState.Authenticated) {
                handleModelInfo(lastModelName, lastModelId)
            }
        } else {
            adaptationController.cancel()
            probeOverridesActiveState.value = false
            applyManualOverrides()
            restartIdleDimIfTracking()
        }
    }

    private fun loadManualOverrides(): MapperOverrides = MapperOverrides(
        mouthSmileNeutralBase = if (adaptationManualPrefs.contains(KEY_MANUAL_MOUTHSMILE_NEUTRAL)) {
            adaptationManualPrefs.getFloat(KEY_MANUAL_MOUTHSMILE_NEUTRAL, 0.5f)
        } else null,
        browDeviceMax = if (adaptationManualPrefs.contains(KEY_MANUAL_BROW_SENSITIVITY)) {
            MANUAL_BROW_DEVICE_BASELINE /
                adaptationManualPrefs.getFloat(KEY_MANUAL_BROW_SENSITIVITY, 1f)
        } else null,
        mouthOpenDeviceMax = if (adaptationManualPrefs.contains(KEY_MANUAL_MOUTHOPEN_SENSITIVITY)) {
            MANUAL_MOUTHOPEN_DEVICE_BASELINE /
                adaptationManualPrefs.getFloat(KEY_MANUAL_MOUTHOPEN_SENSITIVITY, 1f)
        } else null
    )

    private fun applyManualOverrides() {
        mapper.updateOverrides(loadManualOverrides())
    }

    private fun startTracking() {
        if (trackingState.value) return
        if (!arSessionManager.hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
            return
        }
        beginTracking()
    }

    private fun beginTracking() {
        if (trackingState.value) return
        // 未认证时 FaceRenderer 的 injectParameters 静默丢弃
        val session = arSessionManager.createArSession()
        if (session == null) {
            arStatusState.value = getString(R.string.ar_status_create_session_failed)
            Toast.makeText(this, R.string.toast_create_session_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!arSessionManager.resumeSession()) {
            arStatusState.value = getString(R.string.ar_status_resume_session_failed)
            return
        }
        smoother.reset()
        glSurfaceView.onResume()
        trackingState.value = true
        // 启动点击早于本回调，计时须在此补启动
        if (!adaptationRunning) {
            scheduleIdleDim()
        }
        val sending = vtsClient.state is VtsState.Authenticated
        val sendHz = appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ)
        arStatusState.value = if (sending) {
            getString(R.string.ar_status_tracking_sending, sendHz)
        } else {
            getString(R.string.ar_status_tracking_local_only)
        }
    }

    private fun stopTracking() {
        if (!trackingState.value) return
        closeCalibration()
        cancelIdleDim()
        // 顺序：先停 GL 再暂停/释放 session
        glSurfaceView.onPause()
        arSessionManager.pauseSession()
        arSessionManager.releaseSession()
        mapper.clearRawSnapshot()
        adaptationController.cancel()
        vtsClient.disconnect()
        trackingState.value = false
        arStatusState.value = getString(R.string.ar_status_stopped)
    }

    // 仅 ACTION_DOWN 调用；遮罩移除由 BurnInMask 的 UP tap 回调上收
    override fun onUserInteraction() {
        if (calibrationActiveState.value || adaptationRunning) return
        // Live2D 页：任何触摸（点击/拖动模型）都先唤醒 UI，再重启 2s 计时
        if (tabIndexState.value == TAB_LIVE2D) {
            wakeLive2dUi()
            return
        }
        if (trackingState.value) {
            scheduleIdleDim()
        }
    }

    /** 唤醒 Live2D 沉浸模式：UI 淡入 + 系统栏恢复（由 AppRoot 的 effect 驱动） */
    private fun wakeLive2dUi() {
        live2dUiVisibleState.value = true
        scheduleIdleDim()
    }

    private fun scheduleIdleDim() {
        dimHandler.removeCallbacks(dimRunnable)
        // Live2D 页的空闲不是防烧屏：到点只收 UI，与防烧屏开关无关
        if (tabIndexState.value == TAB_LIVE2D) {
            burnInMaskState.value = false
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
            return
        }
        if (!burnInMaskEnabled) {
            burnInMaskState.value = false
            return
        }
        dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
    }

    /** 空闲到点：Live2D 页进沉浸模式，其余页面落防烧屏黑屏遮罩 */
    private fun onIdleTimeout() {
        if (tabIndexState.value == TAB_LIVE2D) {
            InAppLog.i(TAG, "空闲 2s → Live2D 沉浸模式")
            live2dUiVisibleState.value = false
            return
        }
        live2dUiVisibleState.value = true
        burnInMaskState.value = true
    }

    /** Tab 切换统一入口：负责 Live2D 沉浸态的进入与退出收尾 */
    private fun selectTab(tab: Int) {
        val previous = tabIndexState.value
        if (previous == tab) return
        tabIndexState.value = tab
        if (tab == TAB_LIVE2D) {
            // 进页先亮 UI，2s 后自动进沉浸；若刚从黑屏遮罩过来须先撤掉
            live2dUiVisibleState.value = true
            cancelIdleDim()
            scheduleIdleDim()
        } else if (previous == TAB_LIVE2D) {
            // 离开 Live2D：退出沉浸并恢复默认防烧屏规则（系统栏由 AppRoot 复原）
            live2dUiVisibleState.value = true
            cancelIdleDim()
            if (trackingState.value && !calibrationActiveState.value && !adaptationRunning) {
                scheduleIdleDim()
            }
        }
    }

    private fun setBurnInMaskEnabled(enabled: Boolean) {
        burnInMaskEnabled = enabled
        appPrefs.edit().putBoolean(KEY_BURN_IN_MASK_ENABLED, enabled).apply()
        if (tabIndexState.value == TAB_LIVE2D) {
            burnInMaskState.value = false
            scheduleIdleDim()
            return
        }
        if (!enabled) {
            cancelIdleDim()
        } else if (trackingState.value && !calibrationActiveState.value && !adaptationRunning) {
            scheduleIdleDim()
        }
    }

    private fun cancelIdleDim() {
        dimHandler.removeCallbacks(dimRunnable)
        burnInMaskState.value = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            arStatusState.value = getString(R.string.ar_status_permission_granted)
            beginTracking()
            if (pendingCalibrationFromHome) {
                pendingCalibrationFromHome = false
                if (trackingState.value) {
                    enterCalibrationMode()
                }
            }
        } else {
            pendingCalibrationFromHome = false
            arStatusState.value = getString(R.string.ar_status_permission_denied)
            Toast.makeText(this, R.string.toast_camera_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    // 先 resume session 再 super：ON_RESUME 派发点 API 29+ 在本方法返回后
    override fun onResume() {
        if (trackingState.value && !arSessionManager.resumeSession()) {
            stopTracking()
            Toast.makeText(this, R.string.toast_resume_session_failed, Toast.LENGTH_SHORT).show()
        }
        super.onResume()
        // 回前台先亮 UI，避免带着沉浸态（系统栏隐藏）直接回来
        if (tabIndexState.value == TAB_LIVE2D) {
            live2dUiVisibleState.value = true
            cancelIdleDim()
            scheduleIdleDim()
        } else if (trackingState.value && !calibrationActiveState.value && !adaptationRunning) {
            scheduleIdleDim()
        }
    }

    override fun onPause() {
        cancelIdleDim()
        super.onPause()
        // super 之后暂停 session：ON_PAUSE 派发点 API 29+ 在本方法体之前
        if (trackingState.value) {
            arSessionManager.pauseSession()
        }
    }

    override fun onDestroy() {
        closeCalibration()
        // 未开追踪时 stopTracking 直接 return，探针须在此收口
        adaptationController.cancel()
        stopTracking()
        faceRenderer.release()
        vtsClient.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA = 100
        /** 首次使用默认 IP 前缀 */
        private const val DEFAULT_IP = "192.168."
        private const val DEFAULT_PORT = 8001

        private const val KEY_AGREEMENT_ACCEPTED = "agreement_accepted"

        private const val KEY_CALIBRATION_GUIDE_DISMISSED = "calibration_guide_dismissed"

        private const val IDLE_DIM_DELAY_MS = 2000L

        private const val PREFS_APP = "app_settings"

        private const val PREFS_ADAPTATION_MANUAL = "adaptation_manual_prefs"

        // KEY_* 与 DEFAULT_SEND_FREQUENCY_HZ 定义在 ui/SettingsScreen.kt，经 import 引用
    }
}
