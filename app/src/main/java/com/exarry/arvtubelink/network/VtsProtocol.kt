package com.exarry.arvtubelink.network

import com.google.gson.Gson
import com.google.gson.JsonObject

/** VTube Studio Public API 1.0 JSON 协议数据类 */

object VtsApi {
    const val API_NAME = "VTubeStudioPublicAPI"
    const val API_VERSION = "1.0"
    const val DEFAULT_PORT = 8001

    const val PLUGIN_NAME = "ARVTubeLink"
    const val PLUGIN_DEVELOPER = "exarry"
}

data class ParameterValue(
    val id: String,
    val value: Float,
    val weight: Float = 1f
)

data class AuthenticationTokenRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "AuthenticationTokenRequest",
    val data: Data = Data()
) {
    data class Data(
        // 1.0 规范必填，缺失则静默忽略不回包
        val pluginName: String = VtsApi.PLUGIN_NAME,
        val pluginDeveloper: String = VtsApi.PLUGIN_DEVELOPER,
        // 空串表示不带图标
        val pluginIcon: String = ""
    )
}

/** 认证成功后作应用层心跳 */
data class ApiStateRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "APIStateRequest"
)

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

/** mode="set" 直接覆盖当前值 */
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

/** 通用响应信封，按 messageType 分派后再解析 data */
data class VtsEnvelope(
    val apiName: String? = null,
    val apiVersion: String? = null,
    val requestID: String? = null,
    val messageID: String? = null,
    val messageType: String? = null,
    val timestamp: Long? = null,
    val data: JsonObject? = null
)

data class AuthenticationTokenResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val token: String? = null
    )
}

/** 1.0 字段为 authenticated/reason */
data class AuthenticationResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val authenticated: Boolean? = null,
        val reason: String? = null
    )
}

/** 注入不存在的参数会返回 APIError */
data class InputParameterListRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "InputParameterListRequest"
)

data class InputParameterListResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val modelLoaded: Boolean? = null,
        val modelName: String? = null,
        /** 模型稳定 GUID，自适应缓存以它为键（模型名可改可重名） */
        val modelID: String? = null,
        val defaultParameters: List<Param>? = null,
        val customParameters: List<Param>? = null
    )

    /** 量程因参数而异：表情 0..1，眼球 -1..1，FaceAngleX -30..30 */
    data class Param(
        val name: String,
        val value: Float = 0f,
        val min: Float = 0f,
        val max: Float = 1f,
        val defaultValue: Float = 0f
    )
}

/** 字段为 data.parameters[] */
data class Live2DParameterListRequest(
    val apiName: String = VtsApi.API_NAME,
    val apiVersion: String = VtsApi.API_VERSION,
    val requestID: String,
    val messageType: String = "Live2DParameterListRequest"
)

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

    /** min/max 为模型配置量程，用于 OUT 钳制判定 */
    data class Live2DParam(
        val name: String,
        val value: Float = 0f,
        val min: Float = 0f,
        val max: Float = 1f,
        val defaultValue: Float = 0f
    )
}

/** 无 config 的事件可整体省略 config 字段 */
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

data class EventSubscriptionResponse(
    val messageType: String? = null,
    val data: Data? = null
) {
    data class Data(
        val subscribedEventCount: Int? = null,
        val subscribedEvents: List<String>? = null
    )
}

/** 模型加载/卸载推送，须先订阅 */
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

/** errorID=50 为用户拒绝授权 */
data class VtsApiError(
    val errorID: Int? = null,
    val message: String? = null
)

/** 解析入口，格式非法返回 null */
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

    fun parseApiError(envelope: VtsEnvelope): VtsApiError? = runCatching {
        gson.fromJson(envelope.data, VtsApiError::class.java)
    }.getOrNull()
}
