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
import com.exarry.arvtubelink.ui.DEFAULT_SEND_FREQUENCY_HZ
import com.exarry.arvtubelink.ui.KEY_APP_LANGUAGE
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
import com.exarry.arvtubelink.ui.TAB_SETTINGS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.exarry.arvtubelink.utils.InAppLog

/**
 * UI 层：AR 检测 / 追踪控制 + VTS 连接控制 + 连接状态展示 + 设置（校准/灵敏度）。
 *
 * 数据流（Activity 只做装配，不参与逐帧处理）：
 *   FaceRenderer(GL线程) -> BlendShapeMapper 映射(校准偏移+灵敏度+钳制)
 *   -> Smoother EMA 平滑 -> 30Hz 节流 -> VtsWsClient WebSocket 发送(IO协程)
 *
 * 校准/灵敏度由设置页 Tab（ui/SettingsScreen.kt）修改并经 [CalibrationStorage] 持久化；
 * 主题开关（ON 跟随系统 / OFF 强制深色）只写 state + prefs，色板切换由 AppRoot 的 isDark
 * 驱动；强制深色冷启动经 ForceDark theme overlay 修首帧（super.onCreate 前 applyStyle）。
 * mapper 的跨线程状态均为 @Volatile（UI 写、GL 读），启动时在 IO 协程异步加载持久化配置。
 *
 * 校准对准视图（enterCalibrationMode/closeCalibration，UI 见 ui/CalibrationOverlay.kt）：
 *  - 点「校准」（设置页 Tab）→ 隐藏 Tab 骨架（AppRoot 的 Scaffold），1dp 隐藏式
 *    GLSurfaceView 原地展开为全屏（同一窗口内 resize，EGL context 保留），
 *    并弹全屏遮罩承载状态提示与确认/取消；
 *  - 渲染器进入校准可视化模式：绘制人脸网格线框 + 中心十字/对准框 + 质心标记点；
 *  - 点「确认校准」→ mapper.captureCurrentAsBase(当前帧快照) 并持久化，关闭网格模式；
 *  - UI 与 GL 线程只通过 @Volatile 标志（setCalibrationMode/setDebugLogsEnabled）
 *    与主线程回调（onFacePresenceChanged）通信，UI 层绝不直接触碰 OpenGL。
 */
class MainActivity : AppCompatActivity() {

    // ---------- 业务组件（单 Activity 生命周期内复用） ----------
    private val arSessionManager by lazy { ArSessionManager(this) }

    // 映射器实例：携带校准基准与灵敏度（跨线程状态 @Volatile，GL/UI 线程共享安全）。
    // 持久化配置在 [loadPersistedMappingConfig] 中异步加载，加载完成前按中性默认恒等映射运行。
    private val mapper = BlendShapeMapper()

    // 平滑器实例独立，重开追踪/重新校准时 reset 清历史
    private val smoother by lazy { Smoother() }

    // 校准/灵敏度持久化。lazy：Activity 构造期 base context 尚未 attach，
    // 字段初始化时不能取 ApplicationContext，首次访问发生在 onCreate 之后
    private val calibrationStorage by lazy { CalibrationStorage(this) }

    // 应用级设置（详细日志开关等）。独立于 CalibrationStorage 的 prefs 文件，
    // 本类自持，Key 见 [KEY_ENABLE_DEBUG_LOGS]
    private val appPrefs by lazy {
        getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
    }

    // VTS 客户端：状态回调来自 OkHttp 线程，经 runOnUiThread 写 Compose state
    // （Snapshot 要求主线程写）
    private val vtsClient by lazy {
        VtsWsClient(
            context = this,
            onVtsStateChanged = { state ->
                runOnUiThread {
                    vtsStatusState.value = vtsStateLabel(state)
                    when (state) {
                        // 自动发现链路终点：真认证通过才报「已发现并连接」（发现成功 ≠ 连接
                        // 成功——VTS 端授权弹窗/端口错/API 关都会失败，见 pendingAutoConnectToast）
                        VtsState.Authenticated -> if (pendingAutoConnectToast) {
                            pendingAutoConnectToast = false
                            Toast.makeText(this, R.string.discovery_toast_connected, Toast.LENGTH_SHORT).show()
                        }
                        // 手动断开/停止追踪：丢弃陈旧标记（此后再次 Authenticated 属新连接）
                        VtsState.Disconnected -> pendingAutoConnectToast = false
                        else -> {}
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    vtsStatusState.value = getString(R.string.vts_status_error, message)
                }
            },
            // 模型身份（认证后 InputParameterListResponse / 追踪中 ModelLoadedEvent 推送，
            // OkHttp 线程）：参数自适应触发链入口（未开追踪也可适应——连接即适应）
            onModelInfo = { modelName, modelID ->
                runOnUiThread { handleModelInfo(modelName, modelID) }
            }
        )
    }

    // 自动发现客户端（v1.2.5）：只做 UDP 广播监听 + TCP 端口兜底探测，不持连接状态；
    // 命中后由 UI 层复用 connectVts 走既有链路。lazy 同 vtsClient（构造期 base context
    // 未 attach，getSystemService 需等 onCreate 之后）
    private val discoveryClient by lazy { VtsDiscoveryClient(this) }

    // 自动发现连接标记：AppRoot 命中且 API 开启时经 onAutoConnectStarted 置 true；
    // onVtsStateChanged 到 Authenticated 消费弹 Toast，Disconnected 清除防陈旧标记
    // 污染下一次手动连接的 Authenticated
    private var pendingAutoConnectToast = false

    // ---------- 参数自适应（adapt/ 包） ----------

    // 自适应覆盖层持久化。lazy 同 calibrationStorage：构造期 base context 未 attach
    private val adaptationStorage by lazy { AdaptationStorage(this) }

    // 手动映射值独立 prefs（adaptation_manual_prefs，与自适应 overrides 的 adaptation_prefs
    // 分离互不覆盖；优先级 自适应 Done > 手动值 > 硬编码默认，在应用层实现）
    private val adaptationManualPrefs by lazy {
        getSharedPreferences(PREFS_ADAPTATION_MANUAL, Context.MODE_PRIVATE)
    }

    // 探针编排器：门控/Toast/应用 overrides 等副作用全部集中在本类状态回调里，
    // controller 保持纯编排（依赖只有 VtsWsClient + 存储层）。lazy 依赖 vtsClient，
    // 首次访问发生在首个 onModelInfo 之后
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

    // 最近一次模型身份（onModelInfo 缓存）：自适应开关手动打开时复用触发链判定
    // （handleModelInfo 内部 id 判空天然防护未连接场景）
    private var lastModelName: String? = null
    private var lastModelId: String? = null

    // 探测值生效中（手动区禁用真源）：true = mapper.overrides 来自探测
    // （onOverridesReady / 缓存命中）；false = 手动值/默认生效（Failed / 开关 OFF /
    // 手动滑块改动）。Running 期间维持上次终态语义（上次 Done 的值仍生效中）。
    private val probeOverridesActiveState = mutableStateOf(false)

    // 适应状态展示文本（AppRoot/HomeContent 只读消费；null = 不显示）。
    // Running 期间非空（兼作「Running 首次进入」Toast 的判定锚点），Done/Failed/取消清 null
    private val adaptationStatusState = mutableStateOf<String?>(null)

    // 最近一次探测触发是否为「换模型」（区分 Running 首次 Toast 的两条文案），start 前赋值
    private var lastAdaptationIsSwitch = false

    // 本会话已尝试过自动探测的 modelID 集合：探测 Failed（或无产出）后重连同一模型不再
    // 自动重探、不弹「模型更改…」误报（此时 stored 是别的模型的条目，isSwitch 判定会误命中）。
    // 仅内存：Failed 多为环境性/瞬时原因，落盘会让该模型永久不重探且无 UI 可清；
    // 手动重试出口 = 开关 OFF（清空本集合）后重新 ON。仅主线程访问，无需同步。
    private val attemptedModelIds = mutableSetOf<String>()

    // ---------- Compose UI 状态（AppRoot 以 State<T> 只读消费） ----------
    // 初值给空串而非中文：字段初始化早于 attachBaseContext/onCreate，此时 getString 拿到的
    // 是默认资源（未必是用户所选语言）；onCreate 里用 getString 回填正确文案（见 onCreate
    // 中「状态栏初值回填」）。首帧前回填完成，UI 不会看到空串。
    private val arStatusState = mutableStateOf("")
    private val vtsStatusState = mutableStateOf("")
    private val trackingState = mutableStateOf(false)
    private val surfaceFullscreenState = mutableStateOf(false)

    // 底部 Tab 索引：NavigationBar 与顶栏 info 图标的切换都经 onSelectTab 上收
    private val tabIndexState = mutableStateOf(TAB_HOME)

    // 主题开关状态：ON = 跟随系统（默认）/ OFF = 强制深色；
    // 深浅 = if (follow) isSystemInDarkTheme else true，在 AppRoot 派生，本类只持开关布尔
    private val themeFollowSystemState = mutableStateOf(true)

    // AMOLED 纯黑背景开关（默认关闭）：仅深色模式下生效（判定在 Theme 内部），
    // 浅色模式下开启无视觉效果但照常落盘
    private val amoledBlackState = mutableStateOf(false)

    // 顶栏 info「跳设置页并滚到关于」递增信号：Activity 级 state 跨 Tab
    // 组合存活，设置页消费后经 onAboutScrollConsumed 清零
    private val aboutScrollSignalState = mutableStateOf(0)

    // 校准对准视图状态：
    // calibrationActive = 校准进行中（enter/close 守卫与幂等收尾用）；
    // calibFacePresent = 渲染器人脸存在回调，null=尚未收到首帧（显示「正在检测人脸…」过渡文案）
    private val calibrationActiveState = mutableStateOf(false)
    private val calibFacePresentState = mutableStateOf<Boolean?>(null)

    // 校准完成状态（首页首次引导卡的真源）：mapper.calibrationData 是 @Volatile 跨线程值，
    // Compose 快照系统观察不到其变化，必须显式投影成 State 才能驱动重组。
    private val calibrationDoneState = mutableStateOf(false)

    // 「稍后」忽略标记（本进程内有效）：只影响本次冷启动周期，不落盘为永久状态。
    // 落盘的是 dismissed 标记本身（见 KEY_CALIBRATION_GUIDE_DISMISSED），冷启动重新读出。
    private val calibrationGuideDismissedState = mutableStateOf(false)

    // 首页引导卡是否显示：未校准 && 未忽略。派生值，供 AppRoot 只读消费。
    // 不放进 Compose 派生（derivedStateOf）是为了让 AppRoot 保持「只收 State<T>」的约定。
    private val showCalibrationGuideState = derivedStateOf {
        !calibrationDoneState.value && !calibrationGuideDismissedState.value
    }

    // 开屏协议：首次启动阻塞弹窗（已同意持久化于 appPrefs，卸载重装重弹）；
    // showFullLicense = 「查看完整条款」全文弹窗、showPolyForm = PolyForm 许可全文弹窗，
    // 均盖在协议弹窗之上，back 逐层回退
    private val agreementAcceptedState = mutableStateOf(false)
    private val showFullLicenseState = mutableStateOf(false)
    private val showPolyFormState = mutableStateOf(false)

    // ---------- OLED 防烧屏（常驻，无开关，纯黑遮罩方案） ----------
    // 追踪中 2s 无用户操作 → burnInMaskState 置 true 显示全屏纯黑遮罩（ui/BurnInMask.kt）；
    // 任意触摸经 onUserInteraction 移除，退后台/停追踪经 cancelIdleDim 收。
    // 遮罩期 GL/相机/推流继续跑（「推流中」语义），见 BurnInMask KDoc。
    private val dimHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { burnInMaskState.value = true }

    // 遮罩可见性：MainActivity 驱动（dimRunnable 置 true；cancelIdleDim / onBurnInMaskDismiss
    // 置 false——后者 = BurnInMask 的 tap 判定回调上收），AppRoot 以 State<Boolean> 只读消费
    private val burnInMaskState = mutableStateOf(false)

    // 自适应进行中（onAdaptationState 的 Running 分支驱动）：期间防烧屏整体挂起（与校准
    // 同款抑制），屏幕保持点亮——探测 15~25s 内用户需要看首页进度行 (x/7，已跳过 n)，
    // 黑遮罩会把它整块盖住。三个挂计时入口（onUserInteraction / onResume / beginTracking）
    // 与 closeCalibration 出口均以本标志守卫；见各点注释。
    private var adaptationRunning = false

    // 首页引导卡点「开始校准」时若相机权限未授予：置此标记，授权成功后在
    // onRequestPermissionsResult 里自动续上「开追踪 + 进校准」，避免用户点一次没反应
    // 还要再点第二次。只在权限回调这一跳内有效，进入/放弃校准即清。
    private var pendingCalibrationFromHome = false

    // GLSurfaceView 由 AppRoot 的 AndroidView factory 创建并经 onGlSurfaceCreated 回传赋值；
    // beginTracking/stopTracking 的显式 onResume/onPause 仍走本字段
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var faceRenderer: FaceRenderer

    // 校准对准视图状态（calibrationActive/calibrationDialog/calibFaceStatusText/
    // lastCalibFacePresent）在 ui/CalibrationOverlay.kt

    /**
     * 应用语言回放（API < 33）。
     *
     * 背景：AppCompat 1.6.1 只有在 manifest 声明了 `AppLocalesMetadataHolderService`
     * （`autoStoreLocales=true`）时才会自动持久化/恢复 app locale；本工程没声明该 service，
     * 所以 API < 33 上必须自己从 prefs 读回并在 attachBaseContext 里回放。
     *
     * 时机为什么必须是 attachBaseContext（而非 onCreate）：
     *  - `AppCompatActivity.attachBaseContext` → `getDelegate().attachBaseContext2(newBase)`
     *    → `calculateApplicationLocales()` 读的是静态 `sRequestedAppLocales`，并在这一步
     *    通过 `applyOverrideConfiguration` 把 locale 注入 Configuration；
     *  - 必须在 `super.attachBaseContext` **之前** 调 `setApplicationLocales`，静态值才赶得上；
     *  - 此时 Activity 尚未 attach base context，`getSharedPreferences` 不可用 → 用 `newBase`。
     *
     * API 33+ 不走这里：locales 由系统 `LocaleManager` 按包名持久化并自动应用，
     * AppCompat 的 `calculateApplicationLocales` 在 33+ 直接返回 null（不接管）；
     * 且此刻 delegate 还没注册进 `sActivityDelegates`，
     * `AppCompatDelegate.getLocaleManagerForApplication()` 会返回 null 而静默失效。
     */
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val tag = newBase.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
            AppCompatDelegate.setApplicationLocales(localesFor(tag))
        }
        super.attachBaseContext(newBase)
    }

    /**
     * 应用语言切换（设置页语言卡）。
     *
     * 落盘 + 交给 AppCompat 应用：API 33+ 走系统 LocaleManager（系统会持久化，重启自动生效）；
     * API < 33 写静态 sRequestedAppLocales 并 `applyLocalesToActiveDelegates()`。
     *
     * 两条路径都会因 locale 变化触发 Activity recreate（manifest 的 `configChanges` 只声明了
     * `uiMode`，不含 `locale` → `CONFIG_LOCALE` 未被 Activity 处理 → AppCompat 判定需重建）——
     * 这正是期望行为：重建后 attachBaseContext/系统会按新语言重新解析资源，整棵 UI 换语言。
     * 代价是一次重建（GL/AR 会话随 onDestroy→onCreate 重建），与切语言这种低频操作相称。
     */
    private fun applyAppLanguage(tag: String) {
        appPrefs.edit().putString(KEY_APP_LANGUAGE, tag).apply()
        AppCompatDelegate.setApplicationLocales(localesFor(tag))
    }

    /** language tag → LocaleListCompat；空串 = 空列表 = 清除 app locale 覆盖（跟随系统）。 */
    private fun localesFor(tag: String): LocaleListCompat =
        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题初始化：顺序写死——先读 prefs（显式触发 appPrefs lazy 初始化，此时 baseContext
        // 已 attach）→ 强制深色时应用 ForceDark overlay（覆盖 windowBackground/statusBar
        // 首帧，必须在 super.onCreate 的 decor 创建之前）→ super.onCreate()。
        // 不走 AppCompatDelegate.setDefaultNightMode：in-place 路径下启动 night override
        // 无效，且 FOLLOW_SYSTEM 回切粘滞；深浅真源 = AppRoot 的 isDark state（Compose），
        // 系统切换走 configChanges=uiMode 的 dispatch。
        val followSystem = appPrefs.getBoolean(KEY_THEME_FOLLOW_SYSTEM, true)
        themeFollowSystemState.value = followSystem
        amoledBlackState.value = appPrefs.getBoolean(KEY_THEME_AMOLED_BLACK, false)
        if (!followSystem) {
            theme.applyStyle(R.style.ThemeOverlay_ARVTubeLink_ForceDark, true)
        }
        super.onCreate(savedInstanceState)

        // 屏幕常亮：①华为设备息屏/切后台会冻结进程，冻结中 OkHttp 不处理任何下行数据；
        // ②OLED 防烧屏遮罩依赖它——遮罩期黑屏但推流继续，屏幕超时熄灭则遮罩失去意义
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 首启协议判定：字段初始化不读 prefs（appPrefs lazy 依赖 baseContext attach），此处回填
        agreementAcceptedState.value = appPrefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false)

        // 状态栏初值回填（字段初始化时是空串，见 arStatusState 注释）：此刻 getString 已按
        // 用户所选语言解析，回填后首帧即显示正确文案，不会闪空
        arStatusState.value = getString(R.string.ar_status_not_detected)
        vtsStatusState.value = getString(R.string.vts_status_disconnected)

        // 首页校准引导的「稍后」标记：本次启动周期内忽略，下次冷启动重现。
        //  - 点「稍后」写入 appPrefs（KEY_CALIBRATION_GUIDE_DISMISSED = true）；
        //  - 真·冷启动（savedInstanceState == null）在这里清零并回写，下次启动必然重现；
        //  - Activity 重建（savedInstanceState != null）沿用已存值，避免转屏后引导卡又冒出来。
        // 校准是否完成不在这里判定——它来自持久化校准数据，由 loadPersistedMappingConfig
        // 异步加载后回填 calibrationDoneState。
        if (savedInstanceState == null) {
            appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false).apply()
            calibrationGuideDismissedState.value = false
        } else {
            calibrationGuideDismissedState.value =
                appPrefs.getBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false)
        }

        // VTS 地址记忆：恢复上次输入（无记录时用默认值），经 defaultIp/defaultPort 传入
        // AppRoot 初始化输入框；此后输入变化由 AppRoot 的 snapshotFlow 回写 prefs
        val persistedIp = appPrefs.getString(KEY_VTS_IP, null) ?: DEFAULT_IP
        val persistedPort = appPrefs.getInt(KEY_VTS_PORT, DEFAULT_PORT)

        // renderer 在此一次性创建并全程复用；
        // GLSurfaceView 本体改由 AppRoot 的 AndroidView factory 创建（见 ui/AppRoot.kt 的
        // GlSurfaceHost），创建后经 onGlSurfaceCreated 回传赋给 glSurfaceView 字段——
        // beginTracking/stopTracking 的显式 onResume/onPause 仍走该字段
        faceRenderer = FaceRenderer(
            manager = arSessionManager,
            displayRotation = { displayRotation() },
            mapper = mapper,
            smoother = smoother,
            vtsClient = vtsClient,
            onFacePresenceChanged = { present -> calibFacePresentState.value = present }
        )

        // 设置页依赖集（SettingsContent 在设置 Tab 内渲染）
        val settingsDeps = SettingsDeps(
            mapper = mapper,
            smoother = smoother,
            calibrationStorage = calibrationStorage,
            appPrefs = appPrefs,
            adaptationManualPrefs = adaptationManualPrefs,
            faceRenderer = faceRenderer
        )

        // UI 状态 + 业务回调 -> Compose 根组件（组件树见 ui/AppRoot.kt；TopAppBar +
        // Tab 内容区 + NavigationBar 骨架，GL 表面常驻其根层）
        setContent {
            AppRoot(
                arStatus = arStatusState,
                vtsStatus = vtsStatusState,
                tracking = trackingState,
                surfaceFullscreen = surfaceFullscreenState,
                calibrationActive = calibrationActiveState,
                calibFacePresent = calibFacePresentState,
                burnInMask = burnInMaskState,
                onBurnInMaskDismiss = { burnInMaskState.value = false },
                tabIndex = tabIndexState,
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
                onSelectTab = { tabIndexState.value = it },
                onOpenAbout = {
                    // 顶栏 info（首页）：跳设置页 + 递增「滚到关于」信号（设置页消费后清零）
                    tabIndexState.value = TAB_SETTINGS
                    aboutScrollSignalState.value++
                },
                onAboutScrollConsumed = { aboutScrollSignalState.value = 0 },
                onThemeFollowSystemChange = { follow ->
                    // 主题开关：仅 state + prefs 落盘；色板切换由
                    // AppRoot 的 isDark state 驱动。
                    // 不重建 Activity（configChanges=uiMode），GL/AR 连续。
                    themeFollowSystemState.value = follow
                    appPrefs.edit().putBoolean(KEY_THEME_FOLLOW_SYSTEM, follow).apply()
                },
                onAmoledBlackChange = { enabled ->
                    // AMOLED 开关：仅 state + prefs 落盘；背景色由 AppRoot 经
                    // ARVTubeLinkTheme(amoledBlack) 驱动（深色分支内生效）。
                    amoledBlackState.value = enabled
                    appPrefs.edit().putBoolean(KEY_THEME_AMOLED_BLACK, enabled).apply()
                },
                onCalibrateRequested = { enterCalibrationMode() },
                onConfirmCalibration = { onConfirmCalibration() },
                onDismissCalibration = { closeCalibration() },
                showCalibrationGuide = showCalibrationGuideState,
                onStartCalibrationFromHome = { startCalibrationFromHome() },
                onDismissCalibrationGuide = { dismissCalibrationGuide() },
                onCalibrationReset = {
                    // 重置校准 = 回到未完成态：引导卡重新出现（忽略标记一并清掉，
                    // 否则用户点过「稍后」就再也看不到引导）
                    calibrationDoneState.value = false
                    calibrationGuideDismissedState.value = false
                    appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, false).apply()
                },
                agreementAccepted = agreementAcceptedState,
                showFullLicense = showFullLicenseState,
                onAgree = {
                    // 先落盘再翻 state：进程被杀也不重弹
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
            )
        }

        // 以下四项启动即从 SharedPreferences 同步给各组件（UI 侧持有 prefs 真源，
        // 渲染器 / Smoother / mapper 均不自读 prefs）。
        // 详细日志开关
        faceRenderer.setDebugLogsEnabled(appPrefs.getBoolean(KEY_ENABLE_DEBUG_LOGS, false))

        // 推流频率
        faceRenderer.setSendFrequencyHz(appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ))

        // 1€ 滤波器开关
        smoother.setOneEuroFilterEnabled(appPrefs.getBoolean(KEY_ENABLE_ONE_EURO_FILTER, true))

        // 脸部镜像开关（默认关闭）
        mapper.faceMirrorEnabled = appPrefs.getBoolean(KEY_FACE_MIRROR, false)

        loadPersistedMappingConfig()
    }

    // ---------- 持久化配置加载：校准基准 + 灵敏度 ----------
    /**
     * SharedPreferences 首次读取含磁盘 IO，放 IO 协程；加载完成前 mapper 以中性默认运行
     * （灵敏度 1.0、无校准基准 = 恒等映射），加载完成后 @Volatile 写入，下一帧立即生效。
     */
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
            // 校准完成状态投影（必须在主线程写 Compose state；withContext 回主线程）
            val calibrated = calibration.isCalibrated
            withContext(Dispatchers.Main) {
                calibrationDoneState.value = calibrated
            }
        }
    }

    // ---------- 屏幕旋转（FaceRenderer 经 lambda 懒调用；GL 线程首次调用晚于 onCreate） ----------
    private fun displayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    // ---------- 校准对准视图（逻辑侧，UI 见 ui/CalibrationOverlay.kt） ----------

    /**
     * 进入校准对准视图（主线程）：守卫 -> 置渲染器校准标志（只写 @Volatile 标量，UI 线程
     * 可直接调用）-> 切换布局状态（Tab 骨架隐藏 + GLView 全屏：AppRoot 以 !calibrationActive
     * 驱动 Scaffold 显隐，GlSurfaceHost 常驻其外不受影响）-> 置对准视图可见
     * -> 挂起防烧屏（cancelIdleDim：校准需静坐对准、天然「2s 无触摸」，黑遮罩会反复
     * 盖住对准视图；期间防烧屏整体挂起，触摸重挂已被 onUserInteraction 的校准守卫挡住）。
     * facePresent 清 null：先显示「正在检测人脸…」过渡文案，等首帧回调。
     */
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

    /**
     * 关闭对准视图并退出校准模式（主线程，幂等）：确认/取消/返回/stopTracking/onDestroy
     * 的统一收尾入口。确认路径也会走到这里（先落盘再收起）。
     * 校准期间防烧屏挂起（见 enterCalibrationMode）：此处若追踪仍在则从 0 重启 2s 计时。
     */
    private fun closeCalibration() {
        if (!calibrationActiveState.value) return
        calibrationActiveState.value = false
        faceRenderer.setCalibrationMode(false)
        calibFacePresentState.value = null
        surfaceFullscreenState.value = false
        // 确认/取消本身是触摸，「从 0 开始 2s」与 onUserInteraction 的重置收敛为同一锚点
        // （最后一次交互）；stopTracking 路径此处重启的计时由紧随其后的 cancelIdleDim 清掉。
        // 自适应进行中不重启：探测期需屏幕保持点亮看进度行（见 adaptationRunning KDoc）
        if (trackingState.value && !adaptationRunning) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
    }

    /** 确认校准（主线程）：拦截过期快照 -> 捕获当前帧零点 -> 持久化 -> 收起对准视图。 */
    private fun onConfirmCalibration() {
        if (calibFacePresentState.value != true) {
            Toast.makeText(this, R.string.toast_calibrate_no_face, Toast.LENGTH_SHORT).show()
            return
        }
        // 回调渲染链路取当前帧原始数据：mapper 每帧缓存最新 VTS 域原始值快照
        val snapshot = mapper.latestRawSnapshot()
        if (snapshot == null) {
            Toast.makeText(this, R.string.toast_calibrate_no_frame, Toast.LENGTH_SHORT).show()
            return
        }
        val newCalibration = mapper.captureCurrentAsBase(snapshot)
        smoother.reset() // 基准变了，旧 EMA 历史作废，避免旧值拖拽新序列
        lifecycleScope.launch { calibrationStorage.save(newCalibration) }
        // 首页引导卡永久消失（本进程 + 后续启动都经持久化校准数据判定为已校准）
        calibrationDoneState.value = true
        Toast.makeText(this, R.string.toast_calibrate_saved, Toast.LENGTH_SHORT).show()
        closeCalibration()
    }

    /**
     * 首页引导卡「开始校准」（主线程）。
     *
     * 补齐进入校准的前置条件（未开追踪时 enterCalibrationMode 只弹一句 Toast 就返回）：
     *  - 已在追踪：直接进对准视图；
     *  - 未追踪但已有相机权限：beginTracking() 后立刻进（会话创建是同步的，GL 首帧稍后）；
     *  - 未追踪且无权限：requestPermissions，授权成功后在 onRequestPermissionsResult
     *    里经 pendingCalibrationFromHome 标记自动续上校准；
     *  - 追踪创建失败（AR Engine 不可用等）：beginTracking 内部已 Toast + 改状态文案，
     *    此时不进入校准（进入也只会显示「未检测到人脸」），引导卡保留供用户重试。
     */
    private fun startCalibrationFromHome() {
        if (calibrationActiveState.value) return
        if (trackingState.value) {
            enterCalibrationMode()
            return
        }
        if (!arSessionManager.hasCameraPermission()) {
            // 标记意图：授权回调里据此自动拉起校准
            pendingCalibrationFromHome = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
            return
        }
        Toast.makeText(this, getString(R.string.calib_guide_starting), Toast.LENGTH_SHORT).show()
        beginTracking()
        // beginTracking 失败会改 arStatusState 并保持 tracking=false，此时不强行进校准
        if (trackingState.value) {
            enterCalibrationMode()
        }
    }

    /**
     * 首页引导卡「稍后」：本次启动周期内不再显示（冷启动重现，见 onCreate 的清零逻辑）。
     * 落盘而非纯内存，是为了让「Activity 重建」场景也保持一致（重建时读回）。
     */
    private fun dismissCalibrationGuide() {
        calibrationGuideDismissedState.value = true
        appPrefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_DISMISSED, true).apply()
    }

    // ---------- AR Engine 可用性检测 ----------
    private fun checkArEngine() {
        arStatusState.value = getString(R.string.ar_status_checking)
        lifecycleScope.launch {
            val state = arSessionManager.checkAvailability()
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

    // ---------- VTS 连接 ----------
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
        // connect 为异步（OkHttp 后台建连）；首次连接电脑端会弹授权窗，
        // 此处只置「连接中」，后续状态全部由 onVtsStateChanged 回调驱动，UI 不阻塞等待。
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

    // ---------- 参数自适应：触发链 + 状态副作用 ----------

    /**
     * 模型身份到达（主线程，经 vtsClient.onModelInfo 的 runOnUiThread 转入）。
     * 触发链：开关开 && modelID 非空 → 与 adaptation_prefs 的存储比对——
     * modelID 匹配则静默应用已存 overrides（缓存命中，不重探）；不匹配或无存储
     * 则启动探测。modelID 空串/null = 模型卸载推送，忽略。重连认证后本回调再次
     * 到达，缓存命中路径自然幂等；探测中重入由 controller.start 内部判重。
     * 存储读取放 IO 协程（首读含磁盘 IO + JSON 解析），判定回主线程做。
     *
     * 本会话已探测过的模型（[attemptedModelIds]）不再自动重探：探测 Failed 时
     * stored 仍是上一个模型的条目 → 每次重连同一模型都会走「换模型」分支反复
     * 误报并空跑一轮探测。闸门放在缓存命中判定之后、start 之前——缓存命中优先，
     * 避免把「A 成功 → B 失败 → 切回 A」的 A 缓存一并挡掉。
     */
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
                    // 缓存命中：静默应用（探测值生效 → 手动区禁用），无 Toast 无状态行
                    probeOverridesActiveState.value = true
                    mapper.updateOverrides(stored.overrides)
                    InAppLog.i(TAG, "自适应缓存命中（model=$name），直接应用已存覆盖")
                } else if (id in attemptedModelIds) {
                    // 本会话已试过且未产出缓存（探测失败/空产出）：不重探、不弹提示。
                    // 手动重试出口 = 开关 OFF（清空集合）后重新 ON。
                    InAppLog.i(TAG, "自适应：本会话已尝试过 model=$name（id=$id），跳过自动重探")
                } else {
                    attemptedModelIds.add(id)
                    lastAdaptationIsSwitch = stored != null
                    adaptationController.start(id, name, isSwitch = stored != null)
                }
            }
        }
    }

    /**
     * 适应状态上收（主线程，controller 协程经 runOnUiThread 转入）：
     * 门控/状态行/Toast/防烧屏抑制副作用集中在此——Running 进入时拦正常推流（探针不被
     * 30Hz 帧覆盖）并挂起防烧屏（屏幕保持点亮，用户需看进度行）；Done/Failed 复位；
     * Running 首次进入 Toast 一条（状态行在首页，其他页用户看不到；进度更新只刷状态行
     * 不重复 Toast，以状态行非空为「已提示过」锚点）。
     *
     * 防烧屏计时只在 Done/Failed 重启、**Idle 不重启**：cancel() 在主线程同步发 Idle，
     * 而 stopTracking 的 cancelIdleDim() 早于 adaptationController.cancel() 且彼时
     * trackingState 仍为 true——若在 Idle 重启计时会挂出僵尸计时（2s 后冒出遮罩，且
     * onUserInteraction 因 tracking=false 短路无法重挂）。与 closeCalibration 出口同源。
     */
    private fun onAdaptationState(state: AdaptationState) {
        adaptationRunning = state is AdaptationState.Running
        when (state) {
            is AdaptationState.Idle -> {
                faceRenderer.setStreamingPaused(false)
                adaptationStatusState.value = null
                // 不重启防烧屏计时（见上方 KDoc）
            }
            is AdaptationState.Running -> {
                faceRenderer.setStreamingPaused(true)
                // 自适应期间保持屏幕亮：清计时 + 收掉可能已在显示的遮罩
                cancelIdleDim()
                if (adaptationStatusState.value == null) {
                    val textRes = if (lastAdaptationIsSwitch) {
                        R.string.adaptation_running_switch
                    } else {
                        R.string.adaptation_running_first
                    }
                    Toast.makeText(this, getString(textRes), Toast.LENGTH_SHORT).show()
                }
                // 进度格式动态拼接（x/7，已跳过 n），前缀统一用「正在自适应」文案
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
                // probeOverridesActive 已在 onOverridesReady 置 true，无需重复
                Toast.makeText(this, getString(R.string.adaptation_done), Toast.LENGTH_SHORT).show()
                restartIdleDimIfTracking()
            }
            is AdaptationState.Failed -> {
                faceRenderer.setStreamingPaused(false)
                adaptationStatusState.value = null
                // 失败后手动值优先于默认——手动设过则应用，没设过 = 全 null 默认映射
                probeOverridesActiveState.value = false
                applyManualOverrides()
                Toast.makeText(this, getString(R.string.adaptation_failed), Toast.LENGTH_SHORT).show()
                restartIdleDimIfTracking()
            }
        }
    }

    /**
     * 自适应结束后从 0 重启防烧屏计时（追踪中且非校准中才重启，与 closeCalibration 出口同款）。
     */
    private fun restartIdleDimIfTracking() {
        if (trackingState.value && !calibrationActiveState.value) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
    }

    // ---------- 参数自适应：开关回调 + 手动映射值 ----------

    /**
     * 自适应开关切换（设置页，主线程）。落盘在最前：ON 分支的 handleModelInfo 会读同一
     * 键做早退判定，必须先写（apply 的内存写入同步生效），否则拨到 ON 的那次会静默不触发。
     *
     * ON：已认证时复用 handleModelInfo 触发链（缓存命中应用 / 未命中 start——判定逻辑零
     * 重复），未连接则等认证后的 onModelInfo 自然触发；并清空 [attemptedModelIds]，使
     * OFF→ON 成为本会话唯一的手动重试出口（Failed 后的模型可被重新探测）。
     * OFF：取消在途适应（Idle 回调复位门控/状态行）+ 探测值失效 + 恢复手动值（保留在
     * prefs，重开开关自动回到探测值/重新触发链）+ 结束防烧屏挂起（重启计时）。
     */
    private fun onAdaptationToggle(enabled: Boolean) {
        appPrefs.edit().putBoolean(KEY_ENABLE_ADAPTATION, enabled).apply()
        if (enabled) {
            attemptedModelIds.clear()
            if (vtsClient.state is VtsState.Authenticated) {
                handleModelInfo(lastModelName, lastModelId)
            }
            // 未连接/未认证：连接认证后 onModelInfo 自然进触发链，无需动作
        } else {
            adaptationController.cancel()
            probeOverridesActiveState.value = false
            applyManualOverrides()
            // 中断在途探测后结束防烧屏挂起（cancel 发的 Idle 不重启计时，见 onAdaptationState KDoc）
            restartIdleDimIfTracking()
        }
    }

    /** 读取手动映射值（独立 prefs；未设过的键不参与覆盖 = MapperOverrides 对应字段 null）。 */
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
        // mouthXInvert 无 UI 入口，左右翻转由全局「脸部镜像」承担；
        // 字段保留作底层兜底，此处不传 = null，与「无覆盖」语义一致
    )

    /** 应用手动映射值（开关 OFF / 探测 Failed 路径；手动模式生效前提，见触发链注释）。 */
    private fun applyManualOverrides() {
        mapper.updateOverrides(loadManualOverrides())
    }

    // ---------- 追踪控制 ----------
    private fun startTracking() {
        if (trackingState.value) return
        // 相机权限：未授权则申请，授权成功的回调里直接 beginTracking()
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
        // 允许 VTS 未连接时追踪（只本地映射+日志，不发数据）：
        // FaceRenderer 内部 injectParameters 在未认证时自动静默丢弃
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
        // 追踪重启：清空 EMA 历史，避免上一会话的值拖拽新序列
        smoother.reset()
        glSurfaceView.onResume()
        trackingState.value = true
        // 防烧屏计时随追踪启动（启动点击在 onUserInteraction 早于本回调，彼时 tracking
        // 还是 false 不会计时，需在此补启动）；自适应进行中不启动——「连接即适应」窗口内
        // 用户点开始追踪时探测仍在跑，挂计时会让 2s 后的遮罩盖住首页进度行
        if (!adaptationRunning) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
        val sending = vtsClient.state is VtsState.Authenticated
        // 显示的是设置值（prefs），非实际发送率（实际率受采集帧率封顶）；已知限制：
        // 追踪中改频率不实时刷新，下次 beginTracking 才生效
        val sendHz = appPrefs.getInt(KEY_SEND_FREQUENCY_HZ, DEFAULT_SEND_FREQUENCY_HZ)
        arStatusState.value = if (sending) {
            getString(R.string.ar_status_tracking_sending, sendHz)
        } else {
            getString(R.string.ar_status_tracking_local_only)
        }
    }

    private fun stopTracking() {
        if (!trackingState.value) return
        // 校准对准视图中停止追踪（生命周期兜底路径）：先收起对准视图再停渲染。
        // closeCalibration 会按「追踪仍在」重启防烧屏计时，由紧随的 cancelIdleDim 统一清掉
        closeCalibration()
        // 退出防烧屏态（清计时 + 收遮罩；之后 onUserInteraction 因 tracking=false 自然短路）
        cancelIdleDim()
        // 顺序关键：先停渲染线程，再暂停/释放会话，避免 GL 线程访问已暂停的 session
        glSurfaceView.onPause()
        arSessionManager.pauseSession()
        arSessionManager.releaseSession()
        // 清掉渲染链路缓存的最新帧快照：防止之后点「校准」捕获到过期帧
        mapper.clearRawSnapshot()
        // 适应中断收尾：取消探针协程（门控/状态行经 Idle 状态回调复位），再断连接
        adaptationController.cancel()
        // 停止追踪的同时断开 VTS 连接
        vtsClient.disconnect()
        trackingState.value = false
        arStatusState.value = getString(R.string.ar_status_stopped)
    }

    // ---------- OLED 防烧屏：遮罩控制 ----------
    /**
     * 用户交互钩子（仅在 ACTION_DOWN 时由 dispatchTouchEvent 调用，事件照常传递）：
     * 追踪中才重置 2s 防烧屏倒计时；校准中/自适应中不计时——防烧屏整体挂起（校准需静坐
     * 对准、天然「无触摸」；自适应 15~25s 需屏幕保持点亮看进度行，见 enterCalibrationMode /
     * onAdaptationState）。
     *
     * 遮罩移除刻意不在这里：状态栏边缘下拉的 DOWN 同样经过这里，若在此移除会破坏
     * 「下拉 transient 看时间/电量、遮罩保持」；移除由 BurnInMask 的 tap 判定（UP 时）
     * 经 onBurnInMaskDismiss 回调上收。
     */
    override fun onUserInteraction() {
        if (trackingState.value) {
            if (calibrationActiveState.value || adaptationRunning) return
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
    }

    /** 退出防烧屏态（停止追踪 / 退后台统一收尾）：清倒计时 + 收遮罩。 */
    private fun cancelIdleDim() {
        dimHandler.removeCallbacks(dimRunnable)
        burnInMaskState.value = false
    }

    // ---------- 权限回调：授权成功直接开始追踪 ----------
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
            // 权限是从首页引导卡「开始校准」触发的：授权后自动续上校准，用户不必再点一次
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

    // ---------- 生命周期：回前台恢复 AR 会话 + 补防烧屏计时 ----------
    override fun onResume() {
        // 追踪中回前台先恢复 AR Session 再走 super：ON_RESUME 的派发点在 API 29+ 是本方法
        // 返回后（post-callback）、API<29 是 super.onResume() 内（FragmentActivity super 链），
        // GlSurfaceHost observer 在该点重启 GL 渲染循环——resume 放前面保证两条路径下
        // session 均先于 GL 循环恢复（与 stopTracking 的「先停 GL 再停 session」对称）。
        // 恢复的是既有会话：不 create、不重校准（校准基准在 mapper，与 session 无关），
        // 唯一耗时 = 相机重新打开。失败（相机被占）走 stopTracking 统一收尾（状态回
        // 「已停止」，用户重开即可），super 仍必须执行。
        if (trackingState.value && !arSessionManager.resumeSession()) {
            stopTracking()
            Toast.makeText(this, R.string.toast_resume_session_failed, Toast.LENGTH_SHORT).show()
        }
        super.onResume()
        // 追踪中回前台：onPause 已清倒计时并收遮罩，若此后用户不再触摸，
        // 不补计时则静置场景永远不出遮罩——与 beginTracking 一样重启 2s 倒计时；
        // 校准中/自适应中不补（防烧屏挂起，对准视图或进度行需保持点亮）
        if (trackingState.value && !calibrationActiveState.value && !adaptationRunning) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
        }
    }

    // ---------- 生命周期收尾：收遮罩 + 清计时 + 退后台暂停 AR 会话 ----------
    override fun onPause() {
        // 退后台统一收尾：清防烧屏倒计时 + 收遮罩（回前台若追踪仍在，onResume 补计时）
        cancelIdleDim()
        super.onPause()
        // super 之后暂停 AR Session：ON_PAUSE 的派发点在 API 29+ 是本方法体之前
        // （pre-callback）、API<29 是 super.onPause() 的 super 链内，GlSurfaceHost observer
        // 在该点停 GL 渲染循环——两条路径到此处 GL 均已停，保持 stopTracking 确立的
        // 「先停 GL 再停 session」顺序；只 pause 不 release：回前台 resume 既有会话免重校准
        if (trackingState.value) {
            arSessionManager.pauseSession()
        }
    }

    override fun onDestroy() {
        closeCalibration()      // 防 Dialog 泄漏（幂等，未开启时直接返回）
        // 适应取消独立于 stopTracking：未开追踪的「连接即适应」场景 stopTracking
        // 开头就 return，探针协程必须在此收口（stopTracking 内的 cancel 幂等无害）
        adaptationController.cancel()
        stopTracking()          // 内部已含 vtsClient.disconnect()
        faceRenderer.release()  // 取消网络发送协程池
        vtsClient.shutdown()    // 兜底覆盖「只连了 VTS 未开追踪」的场景
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA = 100
        /** 首次使用（prefs 无记录）时 VTS 输入框的默认值。IP 为占位前缀，引导用户补全。 */
        private const val DEFAULT_IP = "192.168."
        private const val DEFAULT_PORT = 8001

        /** 首启协议「已同意」标记（存 app_settings，卸载重装即失）。 */
        private const val KEY_AGREEMENT_ACCEPTED = "agreement_accepted"

        /**
         * 首页校准引导「稍后」标记（存 app_settings）：本次启动周期内忽略，
         * 真·冷启动在 onCreate 里清零，不会变成永久关闭。
         */
        private const val KEY_CALIBRATION_GUIDE_DISMISSED = "calibration_guide_dismissed"

        /** 防烧屏无操作触发时长（ms）。 */
        private const val IDLE_DIM_DELAY_MS = 2000L

        /** 应用级设置 prefs 文件名（详细日志开关等，独立于校准数据的 calibration_prefs）。 */
        private const val PREFS_APP = "app_settings"

        /** 手动映射值 prefs 文件名（与自适应 overrides 的 adaptation_prefs 分离）。 */
        private const val PREFS_ADAPTATION_MANUAL = "adaptation_manual_prefs"

        // KEY_ENABLE_DEBUG_LOGS / KEY_ENABLE_ONE_EURO_FILTER / KEY_VTS_IP / KEY_VTS_PORT /
        // KEY_SEND_FREQUENCY_HZ / DEFAULT_SEND_FREQUENCY_HZ 在 ui/SettingsScreen.kt
        // （internal top-level），本文件经 import 引用，勿在此重复定义
    }
}
