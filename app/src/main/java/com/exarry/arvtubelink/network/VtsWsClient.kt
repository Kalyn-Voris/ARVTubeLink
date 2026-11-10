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

sealed class VtsState {
    data object Disconnected : VtsState()
    data object Connecting : VtsState()
    data object WaitingForTokenApproval : VtsState()
    data object Authenticating : VtsState()
    data object Authenticated : VtsState()
    data object AuthenticationFailed : VtsState()
    data class Reconnecting(val attempt: Int) : VtsState()

    companion object {
        /** Injecting 复用 Authenticated 实例，注入期 state is Authenticated 恒成立 */
        val Injecting: VtsState get() = Authenticated
    }
}

/** VTube Studio WebSocket 客户端：两段式 Token 认证 + 30Hz 注入 + 指数退避重连 */
class VtsWsClient(
    context: Context,
    private val onVtsStateChanged: (VtsState) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onModelInfo: (modelName: String?, modelID: String?) -> Unit = { _, _ -> }
) {

    private val gson = com.google.gson.Gson()
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // 剥离 permessage-deflate：websocket-sharp 压缩回包 OkHttp 凑不齐帧，约 50s 后双向卡死
            chain.proceed(
                chain.request().newBuilder()
                    .removeHeader("Sec-WebSocket-Extensions")
                    .build()
            )
        }
        .retryOnConnectionFailure(true)
        // 读超时 0，等 VTS 弹窗授权可能数十秒
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // 15s Ping 保活，无 Pong 由 OkHttp 断链触发 onFailure 重连
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var ip: String = ""
    private var port: Int = VtsApi.DEFAULT_PORT
    private var token: String? = null

    @Volatile
    private var wantConnect = false

    /** 连接代数：connect/disconnect 自增，旧回调与重连协程校验后忽略 */
    @Volatile
    private var generation = 0L

    private var reconnectAttempt = 0
    private var requestCounter = 0L

    private var reconnectJob: Job? = null

    private var heartbeatJob: Job? = null

    /** Token 看门狗：VTS 对重复申请会重新弹窗 */
    private var tokenWatchdogJob: Job? = null

    /** 可注入参数名，null=未拉取 */
    @Volatile
    private var supportedParams: Set<String>? = null

    private var lastApiError: String? = null
    private var lastApiErrorAt = 0L

    private var injectSendCount = 0L
    private var injectAckCount = 0L

    private var lastDropLogAt = 0L

    /** RAW 下行日志节流时刻，OkHttp 线程读写 */
    private var lastRawLogAtMs = 0L

    private val isSending = AtomicBoolean(false)

    @Volatile
    private var lastInjectSentAtMs = 0L

    @Volatile
    private var injectStateAnnounced = false

    private val pendingLive2DRequests =
        ConcurrentHashMap<String, CompletableDeferred<Live2DParameterListResponse.Data>>()

    /** 订阅时刻；窗口内 APIError 视为旧版 VTS 无 Event API */
    @Volatile
    private var modelEventSubPendingAtMs = 0L

    private val _stateFlow = MutableStateFlow<VtsState>(VtsState.Disconnected)

    val stateFlow: StateFlow<VtsState> = _stateFlow.asStateFlow()

    /** OkHttp 线程写入 */
    var state: VtsState = VtsState.Disconnected
        private set(value) {
            field = value
            InAppLog.i(TAG, "state -> $value")
            _stateFlow.value = value
            if (value is VtsState.Authenticated) startHeartbeat() else stopHeartbeat()
            onVtsStateChanged(value)
        }

    /** token 为 null 时读本地，无本地走授权弹窗 */
    fun connect(ip: String, port: Int = VtsApi.DEFAULT_PORT, token: String? = null) {
        generation++
        cancelReconnect()
        wantConnect = false
        closeSocket()
        resetInjectRuntime()
        this.ip = ip
        this.port = port
        this.token = token ?: prefs.getString(KEY_TOKEN, null)
        wantConnect = true
        reconnectAttempt = 0
        supportedParams = null
        openSocket()
    }

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

    fun shutdown() {
        disconnect()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    /** 丢弃一律返回 false，不抛异常 */
    fun injectParameters(params: Map<String, Float>): Boolean {
        if (state !is VtsState.Authenticated) {
            logDropThrottled("注入被拒：状态=${state::class.java.simpleName}（未连接/未认证，帧被丢弃）")
            return false
        }
        val socket = webSocket ?: run {
            logDropThrottled("注入被拒：webSocket 为空，帧被丢弃")
            return false
        }
        // 空 Map 丢脸帧放行
        val valid = supportedParams
        val filtered = if (valid != null) params.filterKeys { it in valid } else params
        if (params.isNotEmpty() && filtered.isEmpty()) {
            logDropThrottled("注入被拒：参数全部不在 VTS 支持列表，keys=${params.keys}")
            return false
        }
        if (isSending.get()) {
            val stalled = System.currentTimeMillis() - lastInjectSentAtMs > INJECT_ACK_TIMEOUT_MS
            if (!stalled) {
                logDropThrottled("注入丢弃：上一帧在途未回执（只发最新帧）")
                return false
            }
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
                    // 空 Map = 丢脸帧，VTS 侧回归默认值
                    faceFound = params.isNotEmpty(),
                    mode = "set",
                    parameterValues = filtered.map { (id, value) -> ParameterValue(id, value) }
                )
            )
            lastInjectSentAtMs = System.currentTimeMillis()
            val sent = socket.send(gson.toJson(request))
            injectSendCount++
            if (injectSendCount % INJECT_SUMMARY_EVERY == 1L) {
                InAppLog.i(TAG, ">> 注入 #$injectSendCount params=$filtered")
            }
            if (sent) {
                if (!injectStateAnnounced) {
                    injectStateAnnounced = true
                    state = VtsState.Injecting
                }
            } else {
                // 入队失败不会来回执
                isSending.set(false)
            }
            sent
        } catch (t: Throwable) {
            isSending.set(false)
            InAppLog.e(TAG, "注入序列化/发送异常，帧被丢弃", t)
            false
        }
    }

    /** 未认证或超时返回 null，不抛异常 */
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

    private fun logDropThrottled(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDropLogAt >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAt = now
            InAppLog.w(TAG, msg)
        }
    }

    fun savedToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearSavedToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        token = null
    }

    private fun openSocket() {
        val gen = generation
        state = VtsState.Connecting
        // probe + epoch 供跨设备日志比对
        InAppLog.i(TAG, "openSocket -> ws://$ip:$port gen=$gen probe=$PROBE epoch=${System.currentTimeMillis()}")
        val request = Request.Builder().url("ws://$ip:$port").build()
        webSocket = httpClient.newWebSocket(request, createListener(gen))
    }

    private fun closeSocket() {
        webSocket?.close(CODE_NORMAL, "client closing")
        webSocket = null
    }

    private fun resetInjectRuntime() {
        isSending.set(false)
        lastInjectSentAtMs = 0L
        injectStateAnnounced = false
    }

    /** 闭包持有当次代数 */
    private fun createListener(gen: Long): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) {
                InAppLog.w(TAG, "onOpen: stale socket (gen=$gen) ignored")
                return
            }
            // 下行探针：VTS 必答 APIStateResponse，收到即证明 PC→手机方向通
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
            val safeText = maskToken(text)
            if (gen != generation) {
                InAppLog.w(TAG, "onMessage: stale socket (gen=$gen) ignored, raw=$safeText")
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastRawLogAtMs >= RAW_LOG_INTERVAL_MS) {
                lastRawLogAtMs = now
                InAppLog.i(TAG, "<< RAW: ${safeText.take(MAX_LOG_JSON)}")
            }
            try {
                handleMessage(text)
            } catch (t: Throwable) {
                InAppLog.e(TAG, "onMessage handling crashed", t)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            // VTS 只发 text 帧
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
            resetInjectRuntime()
            state = VtsState.Disconnected
            if (wantConnect) {
                scheduleReconnect()
            }
        }
    }

    private fun handleMessage(text: String) {
        // 解析必须用原始 text
        val safeText = maskToken(text)
        val envelope = VtsProtocol.parseEnvelope(text)
        if (envelope == null) {
            InAppLog.w(TAG, "<< 解析失败, raw=${safeText.take(MAX_LOG_JSON)}")
            onError("响应解析失败（原始报文见 Logcat）")
            return
        }
        val type = envelope.messageType
        if (type == "InjectParameterDataResponse") {
            isSending.set(false)
            injectAckCount++
            if (injectAckCount % INJECT_SUMMARY_EVERY == 1L) {
                InAppLog.i(TAG, "<< InjectParameterDataResponse x$injectAckCount（每 $INJECT_SUMMARY_EVERY 条汇总一次）")
            }
        } else {
            // Debug 级被 Logcat 过滤
            InAppLog.i(TAG, "<< $type, raw=${safeText.take(MAX_LOG_JSON)}")
        }
        when (type) {
            "AuthenticationTokenResponse" -> {
                tokenWatchdogJob?.cancel()
                tokenWatchdogJob = null
                // VTS 1.0 字段名为 authenticationToken，兼容旧文档的 token 写法
                val tokenValue = extractToken(envelope)
                if (tokenValue.isNullOrEmpty()) {
                    InAppLog.e(TAG, "token 字段缺失, raw=${safeText.take(MAX_LOG_JSON)}")
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
                // 标准 1.0 字段为 authenticated，非 authenticationSuccess
                val success =
                    VtsProtocol.parseAuthenticationResponse(text)?.data?.authenticated
                InAppLog.i(TAG, "AuthenticationResponse authenticated=$success")
                if (success == true) {
                    reconnectAttempt = 0
                    state = VtsState.Authenticated
                    send(InputParameterListRequest(requestID = nextRequestId()))
                    // 断线由 VTS 端自动退订，认证后重订
                    sendModelEventSubscription()
                } else {
                    InAppLog.e(TAG, "认证被拒, raw=${safeText.take(MAX_LOG_JSON)}")
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

            "InjectParameterDataResponse" -> Unit

            "APIStateResponse" -> {
                InAppLog.i(TAG, "APIStateResponse: ${envelope.data}")
            }

            "APIError" -> {
                val now = System.currentTimeMillis()
                // 抑制窗口内 APIError = 旧版 VTS 无 Event API
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

    /** 兼容 authenticationToken 与 token 两种字段 */
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
            // 最后一次重发后留响应窗口
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
            // 注入帧 30Hz 且体积大，只记字节数
            InAppLog.d(TAG, ">> InjectParameterDataRequest (${json.length} bytes)")
        } else {
            InAppLog.i(TAG, ">> ${message.javaClass.simpleName}, raw=${maskToken(json)}")
        }
        webSocket?.send(json)
    }

    private fun maskToken(json: String): String =
        TOKEN_JSON_REGEX.replace(json) { m -> "\"" + m.groupValues[1] + "\":\"***\"" }

    /** 认证后每 15s 一次 APIState 心跳 */
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

    /** 指数退避 1s,2s,4s,8s,16s 后恒 30s；immediate=true 跳过退避 */
    private fun scheduleReconnect(immediate: Boolean = false) {
        if (!wantConnect) return
        reconnectAttempt++
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
        private const val MAX_SHIFT = 5
        private const val RECONNECT_FAIL_NOTIFY_THRESHOLD = 5
        private const val MAX_LOG_JSON = 500
        private val TOKEN_JSON_REGEX = Regex("\"(authenticationToken|token)\"\\s*:\\s*\"[^\"]*\"")
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        /** 在途回执超时 2s，超过强制接管在途锁 */
        private const val INJECT_ACK_TIMEOUT_MS = 2_000L
        private const val TOKEN_RETRY_MAX = 5
        private const val TOKEN_RETRY_INTERVAL_MS = 10_000L
        private const val ERROR_DEDUPE_MS = 30_000L
        /** 条数，30Hz≈1s */
        private const val INJECT_SUMMARY_EVERY = 30L
        private const val DROP_LOG_INTERVAL_MS = 3_000L

        private const val RAW_LOG_INTERVAL_MS = 5_000L
        private const val LIVE2D_REQUEST_TIMEOUT_MS = 3_000L
        private const val MODEL_LOADED_EVENT_NAME = "ModelLoadedEvent"
        /** 窗口内 APIError 视为旧版 VTS 无事件推送 */
        private const val EVENT_SUB_PENDING_WINDOW_MS = 5_000L
        private const val PROBE = "20261012-3"
    }
}
