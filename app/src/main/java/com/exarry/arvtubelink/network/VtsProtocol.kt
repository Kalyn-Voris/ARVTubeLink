package com.exarry.arvtubelink.network

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 网络层：VTube Studio Public API（apiVersion 1.0）JSON 协议数据类。
 * VTS WebSocket API 文档：https://github.com/DenchiSoft/VTubeStudio
 */

/** VTS API 公共字段常量。 */
object VtsApi {
    const val API_NAME = "VTubeStudioPublicAPI"
    const val API_VERSION = "1.0"
    const val DEFAULT_PORT = 8001

    /** 插件身份（认证弹窗中展示给用户）。 */
    const val PLUGIN_NAME = "ARVTubeLink"
    const val PLUGIN_DEVELOPER = "exarry"
}

/** 单个注入参数项。 */
data class ParameterValue(
    val id: String,
    val value: Float,
    val weight: Float = 1f
)

/** 首次连接（本地无 Token）：请求 VTS 颁发 Token，VTS 端会弹窗让用户确认授权。 */
data class AuthenticationTokenRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "AuthenticationTokenRequest",
    val data: Data = Data()
) {
    data class Data(
        // VTS 1.0 规范必填字段（缺失会被静默忽略，不弹窗不回包）
        val pluginName: String = VtsApi.PLUGIN_NAME,
        val pluginDeveloper: String = VtsApi.PLUGIN_DEVELOPER,
        // 可选：插件图标文件名，暂用空串占位
        val pluginIcon: String = ""
    )
}

/** API 状态查询：认证成功后用作应用层心跳。官方请求类型为 APIStateRequest（响应为 APIStateResponse）。 */
data class ApiStateRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "APIStateRequest"
)

/** 已持有 Token 后的认证请求。 */
data class AuthenticationRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "AuthenticationRequest",
    val data: Data
) {
    data class Data(
        val pluginName: String = VtsApi.PLUGIN_NAME,
        val pluginDeveloper: String = VtsApi.PLUGIN_DEVELOPER,
        val authenticationToken: String
    )
}

/** 参数注入请求（mode="set" 表示直接覆盖参数当前值）。 */
data class InjectParameterDataRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "InjectParameterDataRequest",
    val data: Data
) {
    data class Data(
        val faceFound: Boolean,
        val mode: String = "set",
        val parameterValues: List<ParameterValue>
    )
}

// ---------- 响应 ----------

/** 通用响应信封：先解析 messageType，再按需解析 data。 */
data class VtsEnvelope(
    val apiName: String? = null,
    val apiVersion: String? = null,
    val requestID: String? = null,
    val messageID: String? = null,
    val messageType: String? = null,
    val timestamp: Long? = null,
    val data: JsonObject? = null
)

/** [AuthenticationTokenRequest] 的响应：携带新颁发的 Token。 */
data class AuthenticationTokenResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val token: String? = null
    )
}

/** [AuthenticationRequest] 的响应（官方 1.0 字段为 authenticated/reason，无 authenticationSuccess）。 */
data class AuthenticationResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val authenticated: Boolean? = null,
        val reason: String? = null
    )
}

/** 查询 VTS 当前可注入参数列表（认证成功后调用一次；注入不存在的参数会被 VTS 返回 APIError）。 */
data class InputParameterListRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "InputParameterListRequest"
)

/** [InputParameterListRequest] 的响应：默认参数 + 各插件创建的自定义参数。 */
data class InputParameterListResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val modelLoaded: Boolean? = null,
        val modelName: String? = null,
        /** 模型稳定 GUID（VTS 生成；模型名可改可重名，自适应缓存的匹配键用它）。 */
        val modelID: String? = null,
        val defaultParameters: List<Param>? = null,
        val customParameters: List<Param>? = null
    )

    /** min/max 因参数而异：表情类 0..1，眼球 -1..1，FaceAngleX -30..30。 */
    data class Param(
        val name: String,
        val value: Float = 0f,
        val min: Float = 0f,
        val max: Float = 1f,
        val defaultValue: Float = 0f
    )
}

/** 查询当前模型全量 Live2D 参数（参数自适应探测的读回通道；官方字段为 data.parameters[]）。 */
data class Live2DParameterListRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "Live2DParameterListRequest"
)

/** [Live2DParameterListRequest] 的响应：模型加载态 + 全量 Live2D 参数的当前值/量程。 */
data class Live2DParameterListResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val modelLoaded: Boolean? = null,
        val modelName: String? = null,
        val modelID: String? = null,
        val parameters: List<Live2DParam>? = null
    )

    /** 单个 Live2D 模型参数：value 为当前值，min/max 为模型配置的量程（OUT 钳制判定用）。 */
    data class Live2DParam(
        val name: String,
        val value: Float = 0f,
        val min: Float = 0f,
        val max: Float = 1f,
        val defaultValue: Float = 0f
    )
}

/**
 * 订阅/退订 VTS 推送事件（当前仅订阅 ModelLoadedEvent：检测追踪中模型切换）。
 * 官方 Event API：无 config 的事件可整体省略 config 字段（ModelLoadedEvent 属此类）。
 */
data class EventSubscriptionRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "EventSubscriptionRequest",
    val data: Data
) {
    data class Data(
        val eventName: String,
        val subscribe: Boolean
    )
}

/** [EventSubscriptionRequest] 的响应：回执当前已订阅事件列表（事件本身另以推送到达）。 */
data class EventSubscriptionResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val subscribedEventCount: Int? = null,
        val subscribedEvents: List<String>? = null
    )
}

/** 模型加载/卸载推送事件（需先订阅）：载荷即模型身份，追踪中模型切换的检测通道。 */
data class ModelLoadedEvent(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val modelLoaded: Boolean? = null,
        val modelName: String? = null,
        val modelID: String? = null
    )
}

/** APIError 详情（errorID=50 为用户拒绝授权）。 */
data class VtsApiError(
    val errorID: Int? = null,
    val message: String? = null
)

/** 便捷解析入口（容错：格式非法返回 null）。 */
object VtsProtocol {
    private val gson = Gson()

    fun parseEnvelope(json: String): VtsEnvelope? = runCatching {
        gson.fromJson(json, VtsEnvelope::class.java)
    }.getOrNull()

    fun parseTokenResponse(json: String): AuthenticationTokenResponse? = runCatching {
        gson.fromJson(json, AuthenticationTokenResponse::class.java)
    }.getOrNull()

    fun parseAuthenticationResponse(json: String): AuthenticationResponse? = runCatching {
        gson.fromJson(json, AuthenticationResponse::class.java)
    }.getOrNull()

    fun parseParamListResponse(json: String): InputParameterListResponse? = runCatching {
        gson.fromJson(json, InputParameterListResponse::class.java)
    }.getOrNull()

    fun parseLive2DParameterListResponse(json: String): Live2DParameterListResponse? = runCatching {
        gson.fromJson(json, Live2DParameterListResponse::class.java)
    }.getOrNull()

    fun parseModelLoadedEvent(json: String): ModelLoadedEvent? = runCatching {
        gson.fromJson(json, ModelLoadedEvent::class.java)
    }.getOrNull()

    /** 从信封的 data 中解析 APIError（errorID + message）。 */
    fun parseApiError(envelope: VtsEnvelope): VtsApiError? = runCatching {
        gson.fromJson(envelope.data, VtsApiError::class.java)
    }.getOrNull()
}
