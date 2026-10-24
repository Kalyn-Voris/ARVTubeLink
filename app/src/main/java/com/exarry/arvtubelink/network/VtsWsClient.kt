package com.exarry.arvtubelink.network

import android.content.Context
import com.exarry.arvtubelink.utils.InAppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** WebSocket 连接状态。 */
sealed class VtsState {
    data object Disconnected : VtsState()
    data object Connecting : VtsState()
    /** 已发出 Token 申请，等待用户在 VTS 端弹窗点「同意」。 */
    data object WaitingForTokenApproval : VtsState()
    data object Authenticating : VtsState()
    data object Authenticated : VtsState()
    /** Token 认证被拒（VTS 端已移除授权），已清除本地 Token 并安排自动重试。 */
    data object AuthenticationFailed : VtsState()
    data class Reconnecting(val attempt: Int) : VtsState()

    companion object {
        /**
         * 注入运行态：状态机主链 Disconnected -> Connecting -> Authenticating ->
         * Authenticated 之后的终态，首帧注入成功时由 Authenticated 流转而来。
         *
         * 实现约束：MainActivity.vtsStateLabel 对 VtsState 是「无 else 的穷尽 when」，
         * 新增子类型会直接导致 MainActivity 编译失败，
         * 故 [Injecting] 与 [Authenticated] 共用同一实例：
         * `state is Authenticated` 在注入期恒成立，注入门控与 MainActivity
         * 的「数据发送中」判断（state is Authenticated）均不受影响。
         * 注意：若要把它拆成独立子类型（`data object Injecting : VtsState()`），必须同步在
         * MainActivity.vtsStateLabel 补对应分支，否则该穷尽 when 直接编译失败。
         */
        val Injecting: VtsState get() = Authenticated
    }
}

/**
 * 网络层：VTube Studio WebSocket 客户端。
 *
 * 认证流程（VTS API 1.0 两段式）：
 * 1. 本地无 Token -> 发 [AuthenticationTokenRequest]，VTS 端弹窗让用户确认；
 * 2. 收到 Token -> 存入 SharedPreferences -> 带 Token 发 [AuthenticationRequest]；
 * 3. 认证成功 -> 进入 [VtsState.Authenticated]，此后注入才被放行。
 *
 * 状态机主链：Disconnected -> Connecting -> Authenticating -> Authenticated -> Injecting。
 * 任何网络断开（onFailure/onClosed）先复位 [VtsState.Disconnected]（应用层心跳与
 * 在途注入帧一并清理），wantConnect 时按指数退避重连；重连成功后必须重新走
 * AuthenticationRequest 认证，未认证状态下一律拒发 Inject 请求。
 *
 * 线程模型：
 *  - [connect] 完全异步（OkHttp newWebSocket 后台建连），可在主线程直接调用；
 *  - 回调 [onVtsStateChanged] 在 OkHttp 线程触发，UI 也可直接订阅 [stateFlow]；
 *  - [injectParameters] 仅做参数组装 + WebSocket.send（非阻塞入队），可在任意线程调用。
 *
 * 掉帧保护：上一帧在途（已入队、未收到 VTS 回执）期间新帧一律丢弃，OkHttp 发送队列
 * 至多积压 1 帧——网络卡顿恢复后不会集中回放历史帧，彻底杜绝模型瞬移。
 *
 * @param onVtsStateChanged 连接状态变化回调（UI 更新入口）。
 * @param onError           非致命错误回调（协议错误/Token 失效等，通常伴随状态变化）。
 * @param onModelInfo       模型身份回调：InputParameterListResponse（认证后自动拉取）与
 *                          ModelLoadedEvent（已订阅的模型加载推送）到达时触发，
 *                          OkHttp 线程；参数为 VTS 上报的原始 modelName/modelID
 *                          （模型卸载推送时可能为空串/null，由调用方自行判定）。
 */
class VtsWsClient(
    context: Context,
    private val onVtsStateChanged: (VtsState) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onModelInfo: (modelName: String?, modelID: String?) -> Unit = { _, _ -> }
) {

    private val gson = com.google.gson.Gson()
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 重连调度专用协程池；[shutdown] 时取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // 关键修复：剥离 permessage-deflate 扩展协商。
            // OkHttp 默认在 WebSocket 握手中提供 permessage-deflate，VTS 的 websocket-sharp
            // 会接受并压缩回包，但其压缩输出 OkHttp 端永远凑不齐一帧（onMessage 永不触发，
            // 连接约 50s 后双方互相卡死：VTS 端 NetworkStream ObjectDisposedException，
            // App 端写方向 Broken pipe）。禁用压缩后双方均收发明文帧，一切正常。
            chain.proceed(
                chain.request().newBuilder()
                    .removeHeader("Sec-WebSocket-Extensions")
                    .build()
            )
        }
        .retryOnConnectionFailure(true)
        // 建连超时 10s；连接建立后读超时设 0（无限）：
        // 等待用户在 VTS 端点「允许」可能耗时几十秒，期间不会被 OkHttp 主动断开
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // 帧级心跳保活（稳定化任务）：每 15s 发一次 Ping，连续未收到 Pong 时 OkHttp
        // 主动断链并触发 onFailure 走重连——网络静默期不再只依赖应用层 APIState 心跳。
        // 残留风险：等待 VTS 授权弹窗期间若对端不回 Pong，连接约 30s 后会被误判断线
        // 并重连，Token 看门狗随重连刷新弹窗，以最新一次「允许」为准。
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var ip: String = ""
    private var port: Int = VtsApi.DEFAULT_PORT
    private var token: String? = null

    /** true 表示用户期望保持连接（用于区分主动断开与意外断线）。 */
    @Volatile
    private var wantConnect = false

    /**
     * 连接代数：每次 connect()/disconnect() 自增。
     * 旧 socket 的所有回调（onOpen/onMessage/onFailure/onClosed）以及挂起中的重连协程
     * 都校验此值，过期即忽略——保证任意时刻至多一条"活跃"连接，
     * 杜绝旧连接复活导致的 VTS 多会话并发（VTS 端"插件已连接：N"虚高、回包路由错乱）。
     */
    @Volatile
    private var generation = 0L

    private var reconnectAttempt = 0
    private var requestCounter = 0L

    /** 挂起中的重连任务（可取消，避免新 connect() 后被旧重连"复活"）。 */
    private var reconnectJob: Job? = null

    /** 认证成功后的应用层心跳任务（APIState 轮询）；断开/断线时取消。 */
    private var heartbeatJob: Job? = null

    /** Token 请求看门狗：等待授权期间定时重发（VTS 对重复申请会作废旧 token 重新弹窗）。 */
    private var tokenWatchdogJob: Job? = null

    /** VTS 实际存在的可注入参数名（认证后经 InputParameterListRequest 发现）；null=尚未发现。 */
    @Volatile
    private var supportedParams: Set<String>? = null

    /** APIError 去重用：上一条错误文本与时间。 */
    private var lastApiError: String? = null
    private var lastApiErrorAt = 0L

    /** 注入统计：已发送/已确认计数（内嵌日志按 [INJECT_SUMMARY_EVERY] 汇总打印）。 */
    private var injectSendCount = 0L
    private var injectAckCount = 0L

    /** 注入被拒日志的节流时间戳。 */
    private var lastDropLogAt = 0L

    /** RAW 下行报文日志的节流时间戳（OkHttp 线程读写）。 */
    private var lastRawLogAtMs = 0L

    /**
     * 掉帧保护在途锁：true 表示上一帧已入队、尚未收到 VTS 的 InjectParameterDataResponse。
     * 期间新帧一律拒绝入队（只发最新一帧，彻底丢弃积压历史帧）。
     * 复位点：收到回执 / 入队失败 / 发送异常 / 回执超时接管 / 断线与主动断开。
     */
    private val isSending = AtomicBoolean(false)

    /** 上一帧入队时刻（在途锁超时兜底用，防回执丢失导致锁永久泄漏）。 */
    @Volatile
    private var lastInjectSentAtMs = 0L

    /** 本次认证会话是否已公告过 Injecting（防 30Hz 注入反复触发状态回调）。 */
    @Volatile
    private var injectStateAnnounced = false

    /** 在途 Live2D 参数快照请求（requestID -> 完成器）：[requestLive2DParameterList] 配对用。 */
    private val pendingLive2DRequests =
        ConcurrentHashMap<String, CompletableDeferred<Live2DParameterListResponse.Data>>()

    /**
     * ModelLoadedEvent 订阅时刻（0 = 无在途订阅回执窗口）：窗口
     * [Companion.EVENT_SUB_PENDING_WINDOW_MS] 内到达的 APIError 视为旧版 VTS 无
     * Event API，只记日志不上报 [onError]（避免每次连接都向 UI 报一次错误；模型切换
     * 检测退化为重连认证后的 InputParameterList 触发）。订阅回执/错误任一到达即清零；
     * 窗口按订阅时刻计时、过期自动失效——极旧 VTS 既不回执也不报错时标记不滞留，
     * 不会吞掉后续正常 APIError。
     */
    @Volatile
    private var modelEventSubPendingAtMs = 0L

    private val _stateFlow = MutableStateFlow<VtsState>(VtsState.Disconnected)

    /** 连接状态流（与 [state] 同步更新），供 UI 订阅；初始值 [VtsState.Disconnected]。 */
    val stateFlow: StateFlow<VtsState> = _stateFlow.asStateFlow()

    /** 当前连接状态（OkHttp 线程写入；注入可用性判断以此为准）。 */
    var state: VtsState = VtsState.Disconnected
        private set(value) {
            field = value
            InAppLog.i(TAG, "state -> $value")
            _stateFlow.value = value
            // 只有认证成功（含注入中，Injecting 与 Authenticated 同实例）才需要心跳保活
            if (value is VtsState.Authenticated) startHeartbeat() else stopHeartbeat()
            onVtsStateChanged(value)
        }

    /**
     * 连接 VTS（异步，不阻塞调用线程）。
     * [token] 传 null 时自动读取本地保存的 Token；本地也没有则走首次申请流程
     * （VTS 端会弹窗等待用户授权，期间状态保持 [VtsState.WaitingForTokenApproval]，
     * 用户在电脑端点同意后自动继续，App 端无需任何阻塞等待）。
     * 重复调用会先断开旧连接。
     */
    fun connect(ip: String, port: Int = VtsApi.DEFAULT_PORT, token: String? = null) {
        // 代数自增：旧 socket 的全部回调与挂起中的重连即刻失效（修复双连接并发）
        generation++
        cancelReconnect()
        wantConnect = false // 关闭旧连接期间，其回调不得触发重连
        closeSocket()
        resetInjectRuntime() // 新会话：清掉上一轮的在途帧与状态公告
        this.ip = ip
        this.port = port
        this.token = token ?: prefs.getString(KEY_TOKEN, null)
        wantConnect = true
        reconnectAttempt = 0
        supportedParams = null // 新会话/新模型需重新发现参数
        openSocket()
    }

    /** 主动断开（不触发重连）。 */
    fun disconnect() {
        generation++
        cancelReconnect()
        tokenWatchdogJob?.cancel()
        tokenWatchdogJob = null
        wantConnect = false
        closeSocket()
        resetInjectRuntime()
        state = VtsState.Disconnected
    }

    /** 释放全部资源，之后不可再 connect（如需重连请新建实例）。 */
    fun shutdown() {
        disconnect()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    /**
     * 注入一帧参数。
     *
     * 未连接/未认证时**静默丢弃并返回 false，绝不抛异常**——渲染线程可无脑调用。
     * 掉帧保护：上一帧在途（已入队、未收到 VTS 回执）时本帧直接丢弃——永远只发
     * 最新一帧，彻底丢弃积压历史帧；回执超过 [INJECT_ACK_TIMEOUT_MS] 未到则强制
     * 接管在途锁，防止回执丢失把注入永久堵死。
     * faceFound 由参数是否为空推断；实际发送为 WebSocket.send（非阻塞入队），
     * 不会因网络延迟阻塞调用线程。
     *
     * @return 是否已接受发送。
     */
    fun injectParameters(params: Map<String, Float>): Boolean {
        if (state !is VtsState.Authenticated) {
            logDropThrottled("注入被拒：状态=${state::class.java.simpleName}（未连接/未认证，帧被丢弃）")
            return false
        }
        val socket = webSocket ?: run {
            logDropThrottled("注入被拒：webSocket 为空，帧被丢弃")
            return false
        }
        // 只注入 VTS 实际存在的参数；不存在的参数会被逐次返回 APIError。
        // 空 Map（丢脸帧 faceFound=false）放行——仅拦截"有表情但全被过滤"的情况
        val valid = supportedParams
        val filtered = if (valid != null) params.filterKeys { it in valid } else params
        if (params.isNotEmpty() && filtered.isEmpty()) {
            logDropThrottled("注入被拒：参数全部不在 VTS 支持列表，keys=${params.keys}")
            return false
        }
        // 掉帧保护入口：上一帧在途未回执 -> 本帧丢弃（30Hz 下积压帧全部拒收，只发最新帧）
        if (isSending.get()) {
            val stalled = System.currentTimeMillis() - lastInjectSentAtMs > INJECT_ACK_TIMEOUT_MS
            if (!stalled) {
                logDropThrottled("注入丢弃：上一帧在途未回执（只发最新帧）")
                return false
            }
            // 回执超时兜底：疑似回执丢失（VTS 卡顿/切模型），强制接管避免永久堵死
            InAppLog.w(TAG, "注入回执超时 ${INJECT_ACK_TIMEOUT_MS}ms，强制接管在途锁")
            isSending.set(false)
        }
        if (!isSending.compareAndSet(false, true)) {
            logDropThrottled("注入丢弃：并发发送竞争失败")
            return false
        }
        return try {
            val request = InjectParameterDataRequest(
                requestID = nextRequestId(),
                data = InjectParameterDataRequest.Data(
                    // 空 Map = 丢脸帧，faceFound=false 让 VTS 松开注入、模型回归默认值
                    faceFound = params.isNotEmpty(),
                    mode = "set",
                    parameterValues = filtered.map { (id, value) -> ParameterValue(id, value) }
                )
            )
            // 先记时刻再入队：回执最早也要等帧真正写出后才可能到达
            lastInjectSentAtMs = System.currentTimeMillis()
            val sent = socket.send(gson.toJson(request))
            injectSendCount++
            if (injectSendCount % INJECT_SUMMARY_EVERY == 1L) {
                // 参数发送频率 + 实际发送的参数 map（30Hz 下约每 1s 打印一条）
                InAppLog.i(TAG, ">> 注入 #$injectSendCount params=$filtered")
            }
            if (sent) {
                // 状态机：Authenticated -> Injecting（每次认证会话只公告一次）
                if (!injectStateAnnounced) {
                    injectStateAnnounced = true
                    state = VtsState.Injecting
                }
            } else {
                // 入队失败（连接正在关闭）：不会等到回执，立即释放在途锁
                isSending.set(false)
            }
            sent
        } catch (t: Throwable) {
            isSending.set(false)
            InAppLog.e(TAG, "注入序列化/发送异常，帧被丢弃", t)
            false
        }
    }

    /**
     * 请求一次模型全量 Live2D 参数快照（参数自适应探测的读回通道，IO 协程调用）。
     * 未认证返回 null；按 requestID 配对响应，超时返回 null、绝不抛异常。
     * 与 30Hz 注入回执互不干扰（按 messageType 分发）；调用方应串行调用
     * （适应编排天然串行，并发重入由 pendingLive2DRequests 按 requestID 独立配对兜底）。
     */
    suspend fun requestLive2DParameterList(
        timeoutMs: Long = LIVE2D_REQUEST_TIMEOUT_MS
    ): Live2DParameterListResponse.Data? {
        if (state !is VtsState.Authenticated) return null
        val requestID = nextRequestId()
        val deferred = CompletableDeferred<Live2DParameterListResponse.Data>()
        pendingLive2DRequests[requestID] = deferred
        return try {
            send(Live2DParameterListRequest(requestID = requestID))
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingLive2DRequests.remove(requestID)
        }
    }

    /** 注入被拒原因日志（3s 节流：30Hz 下失败会连续触发，不能逐帧刷屏）。 */
    private fun logDropThrottled(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDropLogAt >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAt = now
            InAppLog.w(TAG, msg)
        }
    }

    /** 本地保存的 Token（null 表示尚未授权过；供设置页展示/清除）。 */
    fun savedToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearSavedToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        token = null
    }

    // ---------- 内部实现 ----------

    private fun openSocket() {
        val gen = generation
        state = VtsState.Connecting
        // 版本探针 + epoch 时间戳（对齐手机/PC 时钟，用于跨设备日志比对）
        InAppLog.i(TAG, "openSocket -> ws://$ip:$port gen=$gen probe=$PROBE epoch=${System.currentTimeMillis()}")
        val request = Request.Builder().url("ws://$ip:$port").build()
        webSocket = httpClient.newWebSocket(request, createListener(gen))
    }

    private fun closeSocket() {
        webSocket?.close(CODE_NORMAL, "client closing")
        webSocket = null
    }

    /**
     * 断线/新会话时清理注入运行态：释放在途锁（回执永远不会再来）、
     * 复位 Injecting 公告标记与在途时刻。
     */
    private fun resetInjectRuntime() {
        isSending.set(false)
        lastInjectSentAtMs = 0L
        injectStateAnnounced = false
    }

    /** 每次建连创建独立 listener，闭包持有当次代数；过期 socket 的回调一律忽略。 */
    private fun createListener(gen: Long): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) {
                InAppLog.w(TAG, "onOpen: stale socket (gen=$gen) ignored")
                return
            }
            // 下行探针（官方推荐的首个请求）：VTS 无需用户交互必答 APIStateResponse，
            // 收到即证明 PC→手机方向通；始终收不到则问题在网络层而非协议层
            send(ApiStateRequest(requestID = nextRequestId()))
            val currentToken = token
            if (currentToken == null) {
                state = VtsState.WaitingForTokenApproval
                sendTokenRequest()
            } else {
                state = VtsState.Authenticating
                sendAuthentication(currentToken)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) {
                InAppLog.w(TAG, "onMessage: stale socket (gen=$gen) ignored, raw=$text")
                return
            }
            // RAW 下行报文 5s 节流：注入回执 30Hz 逐条到达会刷爆 Logcat 与内嵌面板；
            // 窗口内保留第一条、后续静默。低频消息（认证/错误等）不受影响，仍逐条打印。
            val now = System.currentTimeMillis()
            if (now - lastRawLogAtMs >= RAW_LOG_INTERVAL_MS) {
                lastRawLogAtMs = now
                InAppLog.i(TAG, "<< RAW: ${text.take(MAX_LOG_JSON)}")
            }
            try {
                handleMessage(text)
            } catch (t: Throwable) {
                InAppLog.e(TAG, "onMessage handling crashed", t)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // VTS 官方文档承诺只发 text 帧；若真收到 binary 帧也记日志，避免 OkHttp 默认静默丢弃
            if (gen != generation) {
                InAppLog.w(TAG, "onMessage(binary): stale socket (gen=$gen) ignored, ${bytes.size} bytes")
                return
            }
            InAppLog.i(TAG, "<< RAW(binary, ${bytes.size} bytes): ${bytes.hex()}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) {
                InAppLog.w(TAG, "onFailure: stale socket (gen=$gen) ignored, cause=${t.message}")
                return
            }
            InAppLog.e(TAG, "socket failure: ${t.message}, httpCode=${response?.code}", t)
            // 任何网络断开：先复位状态机与在途帧，再按需重连（重连成功后必须重新认证）
            resetInjectRuntime()
            state = VtsState.Disconnected
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) {
                InAppLog.w(TAG, "onClosed: stale socket (gen=$gen) ignored, code=$code")
                return
            }
            InAppLog.i(TAG, "socket closed: $code $reason")
            // 任何网络断开：复位为 Disconnected；wantConnect 时走重连（重连后重新走认证）
            resetInjectRuntime()
            state = VtsState.Disconnected
            if (wantConnect) {
                scheduleReconnect()
            }
        }
    }

    private fun handleMessage(text: String) {
        val envelope = VtsProtocol.parseEnvelope(text)
        if (envelope == null) {
            // Gson 解析信封失败：打印原始报文，便于对照 VTS 协议排查
            InAppLog.w(TAG, "<< 解析失败, raw=${text.take(MAX_LOG_JSON)}")
            onError("响应解析失败（原始报文见 Logcat）")
            return
        }
        val type = envelope.messageType
        if (type == "InjectParameterDataResponse") {
            // 掉帧保护核心复位点：回执到达，释放在途锁，允许发送下一帧
            isSending.set(false)
            // 注入回执 30Hz 逐条打印会刷爆内嵌日志：计数汇总（每 30 条 ≈1s 打一条）
            injectAckCount++
            if (injectAckCount % INJECT_SUMMARY_EVERY == 1L) {
                InAppLog.i(TAG, "<< InjectParameterDataResponse x$injectAckCount（每 $INJECT_SUMMARY_EVERY 条汇总一次）")
            }
        } else {
            // 用 Info 而非 Debug：Debug 级会被 Logcat 过滤，回包会看不到
            InAppLog.i(TAG, "<< $type, raw=${text.take(MAX_LOG_JSON)}")
        }
        when (type) {
            "AuthenticationTokenResponse" -> {
                // Token 已颁发：停掉看门狗，持久化 -> 带 Token 走正式认证。
                tokenWatchdogJob?.cancel()
                tokenWatchdogJob = null
                // 注意：VTS 1.0 实际字段名为 authenticationToken（早期文档写作 token），
                // 直接从 envelope.data(JsonObject) 提取并兼容两种写法
                val tokenValue = extractToken(envelope)
                if (tokenValue.isNullOrEmpty()) {
                    InAppLog.e(TAG, "token 字段缺失, raw=${text.take(MAX_LOG_JSON)}")
                    onError("Token 响应中缺少 authenticationToken 字段")
                    return
                }
                token = tokenValue
                prefs.edit().putString(KEY_TOKEN, tokenValue).apply()
                InAppLog.i(TAG, "VTS token saved (${tokenValue.length} chars)")
                state = VtsState.Authenticating
                sendAuthentication(tokenValue)
            }

            "AuthenticationResponse" -> {
                // 官方 1.0 字段为 authenticated（非 authenticationSuccess）
                val success =
                    VtsProtocol.parseAuthenticationResponse(text)?.data?.authenticated
                InAppLog.i(TAG, "AuthenticationResponse authenticated=$success")
                if (success == true) {
                    reconnectAttempt = 0
                    state = VtsState.Authenticated
                    // 认证成功后拉取可注入参数列表：注入不存在的参数会被 VTS 逐次返回 APIError
                    send(InputParameterListRequest(requestID = nextRequestId()))
                    // 订阅模型加载事件：追踪中模型切换的检测通道（断线由 VTS 端自动退订，
                    // 重连认证后在此重订；旧版 VTS 无 Event API 时静默降级，见 APIError 分支）
                    sendModelEventSubscription()
                } else {
                    InAppLog.e(TAG, "认证被拒, raw=${text.take(MAX_LOG_JSON)}")
                    // Token 已失效（VTS 端被移除授权）：清 Token 后跳过退避立即重连，
                    // onOpen 因本地已无 token 自动重走 AuthenticationTokenRequest 弹窗申请
                    state = VtsState.AuthenticationFailed
                    onError("Token 已失效，已清除本地 Token，请到 VTS 电脑端重新点『允许』授权")
                    clearSavedToken()
                    reconnectAttempt = 0
                    scheduleReconnect(immediate = true)
                }
            }

            "InputParameterListResponse" -> {
                val data = VtsProtocol.parseParamListResponse(text)?.data
                if (data != null) {
                    val names = (data.defaultParameters.orEmpty() + data.customParameters.orEmpty())
                        .map { it.name }.toSet()
                    supportedParams = names
                    InAppLog.i(
                        TAG,
                        "VTS 可注入参数 ${names.size} 项 " +
                            "(默认${data.defaultParameters?.size ?: 0}/自定义${data.customParameters?.size ?: 0}): " +
                            names.sorted().joinToString(",")
                    )
                    // 模型身份上收：连接/重连认证后的首次发现（自适应缓存的 modelID 比对源）
                    onModelInfo(data.modelName, data.modelID)
                }
            }

            "Live2DParameterListResponse" -> {
                val data = VtsProtocol.parseLive2DParameterListResponse(text)?.data
                if (data != null) {
                    val deferred = pendingLive2DRequests.remove(envelope.requestID ?: "")
                    deferred?.complete(data)
                }
            }

            "EventSubscriptionResponse" -> {
                // 订阅回执：结束 APIError 抑制窗口（成功与否以回执列表为准，此处只关心窗口结束）
                modelEventSubPendingAtMs = 0L
            }

            "ModelLoadedEvent" -> {
                val data = VtsProtocol.parseModelLoadedEvent(text)?.data
                InAppLog.i(
                    TAG,
                    "ModelLoadedEvent: loaded=${data?.modelLoaded}, model=${data?.modelName}, id=${data?.modelID}"
                )
                onModelInfo(data?.modelName, data?.modelID)
            }

            "InjectParameterDataResponse" -> Unit // 注入回执，已在上方统一处理

            "APIStateResponse" -> {
                // 下行探针回包（onOpen 时发出）：收到即证明 PC→手机方向通
                InAppLog.i(TAG, "APIStateResponse: ${envelope.data}")
            }

            "APIError" -> {
                val now = System.currentTimeMillis()
                // 订阅回执窗口内的 APIError = 旧版 VTS 无 Event API：静默降级为「仅重连
                // 认证后检测模型切换」，只记日志、不走 onError（防止每次连接都闪错误提示）；
                // 窗口按订阅时刻计时，过期自动失效、不滞留吞错（窗口外的错误照常走去重上报）
                val subPendingAt = modelEventSubPendingAtMs
                if (subPendingAt > 0L) {
                    modelEventSubPendingAtMs = 0L
                    if (now - subPendingAt <= EVENT_SUB_PENDING_WINDOW_MS) {
                        InAppLog.w(TAG, "Event API 订阅失败（旧版 VTS？），ModelLoadedEvent 不可用: $envelope")
                        return
                    }
                }
                val err = VtsProtocol.parseApiError(envelope)
                val msg = "VTS APIError ${err?.errorID}: ${err?.message ?: envelope.data}"
                // 同一错误 30 秒内只上报一次：30Hz 注入若失败会连续刷屏
                if (msg != lastApiError || now - lastApiErrorAt > ERROR_DEDUPE_MS) {
                    lastApiError = msg
                    lastApiErrorAt = now
                    InAppLog.e(TAG, msg)
                    onError(msg)
                } else {
                    InAppLog.w(TAG, "(dedup) $msg")
                }
            }

            else -> InAppLog.w(TAG, "unhandled messageType: $type")
        }
    }

    /** 从 token 响应的 data 中提取凭证，兼容 authenticationToken（VTS 1.0 实际字段）/ token 两种写法。 */
    private fun extractToken(envelope: VtsEnvelope): String? {
        val data = envelope.data ?: return null
        return runCatching {
            (data.get("authenticationToken") ?: data.get("token"))
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        }.getOrNull()
    }

    private fun sendAuthentication(tokenValue: String) {
        send(
            AuthenticationRequest(
                requestID = nextRequestId(),
                data = AuthenticationRequest.Data(authenticationToken = tokenValue)
            )
        )
    }

    /** 订阅模型加载事件（认证成功后调用一次；fire-and-forget，回执/错误只用于结束抑制窗口）。 */
    private fun sendModelEventSubscription() {
        modelEventSubPendingAtMs = System.currentTimeMillis()
        send(
            EventSubscriptionRequest(
                requestID = nextRequestId(),
                data = EventSubscriptionRequest.Data(
                    eventName = MODEL_LOADED_EVENT_NAME,
                    subscribe = true
                )
            )
        )
    }

    /**
     * 发送 Token 申请并启动看门狗：等待授权期间每 10s 重发一次（最多 [TOKEN_RETRY_MAX] 次）。
     * VTS 对重复申请会作废旧 token 并重新弹窗（用户日志已证实），因此每次重发后
     * 都需要用户再点一次"允许"，以最后一次为准。全程无任何回包则提示网络方向问题。
     */
    private fun sendTokenRequest() {
        val gen = generation
        send(
            AuthenticationTokenRequest(
                requestID = nextRequestId(),
                data = AuthenticationTokenRequest.Data(
                    pluginName = VtsApi.PLUGIN_NAME,
                    pluginDeveloper = VtsApi.PLUGIN_DEVELOPER,
                    pluginIcon = ""
                )
            )
        )
        tokenWatchdogJob?.cancel()
        tokenWatchdogJob = scope.launch {
            repeat(TOKEN_RETRY_MAX) { attempt ->
                delay(TOKEN_RETRY_INTERVAL_MS)
                if (gen != generation || state !is VtsState.WaitingForTokenApproval) return@launch
                InAppLog.i(
                    TAG,
                    "Token 未收到响应，第 ${attempt + 1}/$TOKEN_RETRY_MAX 次重发" +
                        "（VTS 会刷新弹窗，请点最新一次\"允许\"）"
                )
                send(
                    AuthenticationTokenRequest(
                        requestID = nextRequestId(),
                        data = AuthenticationTokenRequest.Data(
                            pluginName = VtsApi.PLUGIN_NAME,
                            pluginDeveloper = VtsApi.PLUGIN_DEVELOPER,
                            pluginIcon = ""
                        )
                    )
                )
            }
            // 给最后一次重发留出响应窗口，仍无回包则判定下行方向不通
            delay(TOKEN_RETRY_INTERVAL_MS)
            if (gen == generation && state is VtsState.WaitingForTokenApproval) {
                InAppLog.e(TAG, "token 看门狗重试耗尽，全程未收到 VTS 任何回包")
                onError(
                    "VTS 对所有请求均无回包：疑似 PC→手机方向被拦截" +
                        "（AP 隔离/防火墙/Wi-Fi 休眠），可尝试手机开热点供 PC 连接"
                )
            }
        }
    }

    private fun send(message: Any) {
        val json = gson.toJson(message)
        if (message is InjectParameterDataRequest) {
            // 注入帧每秒最多 30 条且体积大，只记条数不记内容
            InAppLog.d(TAG, ">> InjectParameterDataRequest (${json.length} bytes)")
        } else {
            // 控制类消息（认证/心跳等）打印完整 raw，便于对照 VTS 协议排查
            InAppLog.i(TAG, ">> ${message.javaClass.simpleName}, raw=$json")
        }
        webSocket?.send(json)
    }

    /** 认证成功后启动：每 15s 发一次 APIState 请求，兼作应用层心跳与活性探测。 */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                send(ApiStateRequest(requestID = nextRequestId()))
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 指数退避：1s,2s,4s,8s,16s，其后恒为 30s；主动断开（wantConnect=false）不重连。
     * [immediate]=true 时跳过退避立即重连（Token 失效后的重新授权流程用），
     * wantConnect 门控、代数校验与 reconnectJob 可取消语义不变。
     */
    private fun scheduleReconnect(immediate: Boolean = false) {
        if (!wantConnect) return
        reconnectAttempt++
        // 恰好等于阈值时报一次（周期内不重复提醒），认证成功/手动 connect 重置计数后重新累计
        if (reconnectAttempt == RECONNECT_FAIL_NOTIFY_THRESHOLD) {
            onError("VTS 连续重连失败 $reconnectAttempt 次，请检查电脑/VTS 是否在线")
        }
        val delayMs = if (immediate) {
            0L
        } else {
            minOf(
                BASE_RECONNECT_MS shl (reconnectAttempt.coerceAtMost(MAX_SHIFT) - 1),
                MAX_RECONNECT_MS
            )
        }
        state = VtsState.Reconnecting(reconnectAttempt)
        val gen = generation
        reconnectJob = scope.launch {
            delay(delayMs)
            // 连接意图仍在、且未被新的 connect()/disconnect() 取代（代数一致）才重连
            if (wantConnect && gen == generation) {
                InAppLog.i(TAG, "reconnecting (attempt=$reconnectAttempt, after ${delayMs}ms)")
                openSocket()
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun nextRequestId(): String = "arvtubelink-${++requestCounter}"

    companion object {
        private const val TAG = "VtsWsClient"
        private const val PREFS_NAME = "vts_client"
        private const val KEY_TOKEN = "auth_token"
        private const val CODE_NORMAL = 1000
        private const val BASE_RECONNECT_MS = 1000L
        private const val MAX_RECONNECT_MS = 30_000L
        private const val MAX_SHIFT = 5 // 1s,2s,4s,8s,16s 之后恒为 30s
        /** 连续重连失败达到该次数时经 onError 提醒一次（认证成功/手动 connect 重置计数） */
        private const val RECONNECT_FAIL_NOTIFY_THRESHOLD = 5
        /** 日志中原始 JSON 报文的最大打印长度 */
        private const val MAX_LOG_JSON = 500
        /** 认证成功后的应用层心跳间隔（毫秒） */
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        /** 注入在途回执超时（毫秒）：超过即强制接管在途锁，防回执丢失永久堵死注入 */
        private const val INJECT_ACK_TIMEOUT_MS = 2_000L
        /** Token 看门狗：重发次数与间隔 */
        private const val TOKEN_RETRY_MAX = 5
        private const val TOKEN_RETRY_INTERVAL_MS = 10_000L
        /** 同一 APIError 的去重窗口（毫秒） */
        private const val ERROR_DEDUPE_MS = 30_000L
        /** 内嵌日志中注入收发汇总的间隔（条数；30Hz 下 ≈1s 一条） */
        private const val INJECT_SUMMARY_EVERY = 30L
        /** 注入被拒原因日志的节流间隔（毫秒） */
        private const val DROP_LOG_INTERVAL_MS = 3_000L

        /** RAW 下行报文日志的节流间隔（毫秒） */
        private const val RAW_LOG_INTERVAL_MS = 5_000L
        /** Live2D 参数快照请求的默认超时（毫秒；LAN 正常回包远快于此，超时=读回失败） */
        private const val LIVE2D_REQUEST_TIMEOUT_MS = 3_000L
        /** 订阅的事件名：模型加载/卸载推送（官方 Event API）。 */
        private const val MODEL_LOADED_EVENT_NAME = "ModelLoadedEvent"
        /** Event API 订阅回执窗口（毫秒）：窗口内 APIError 视为旧版 VTS 不支持事件推送并静默降级 */
        private const val EVENT_SUB_PENDING_WINDOW_MS = 5_000L
        /** 构建探针标记：每次改动递增，用于 Logcat 确认真机包版本 */
        private const val PROBE = "20261012-3"
    }
}
