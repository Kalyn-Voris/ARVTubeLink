# ============================================================================
#  ARVTubeLink — R8 / ProGuard 规则
# ============================================================================
#
#  当前状态：release 的 optimization 关闭，R8 尚未运行。
#  本文件的规则为「预先上保险」——将来把 release 的
#  `optimization { enable = true }` 打开后，可避免以下已识别的坑。
#
#  ⚠️ 本文件必须被 app/build.gradle.kts 的 release 块显式引用才会生效：
#       proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
#                     "proguard-rules.pro")
#     AGP 9 不会按文件名自动发现 proguard-rules.pro（已核实 AGP 9.4.1 源码
#     对 "proguard-rules.pro" 零引用）。遗漏该行 = 本文件形同虚设。
#
#  ============================================================================
#  为什么需要 Gson 规则（重要）
#  ============================================================================
#  VtsProtocol.kt 的全部协议模型依赖「Kotlin 属性名 == JSON 键名」做序列化，
#  项目内没有任何 @SerializedName 注解：
#
#      data class ParameterValue(val id: String, val value: Float, ...)
#      gson.toJson(request)   ->  {"id":..., "value":...}
#
#  而 Gson 2.11.0 自带的 META-INF/proguard/gson.pro 明确不保留字段名，原文：
#      # Also allow obfuscation, assuming that users will additionally use
#      # @SerializedName or other means to preserve the field names
#
#  一旦字段名被 R8 混淆（modelID -> a），发出的 JSON 键名随之改变，VTS 端会
#  静默忽略未知字段 —— 表现为「连接成功、认证通过，但参数完全不生效」，且
#  不报错、难排查。下面三条规则即为阻断该路径。
# ============================================================================

# VTS WebSocket 协议模型：AuthenticationTokenRequest / AuthenticationRequest /
# AuthenticationResponse / ApiStateRequest / InjectParameterDataRequest /
# InputParameterListResponse / Live2DParameterListResponse / ModelLoadedEvent /
# VtsEnvelope / VtsApiError / ParameterValue 等全部位于 network 包。
-keepclassmembers class com.exarry.arvtubelink.network.** { <fields>; }

# 参数自适应覆盖层：经 Gson 持久化到 SharedPreferences（adaptation_prefs）。
# 字段名被混淆会导致历史存档读回失败（当前代码用 runCatching 兜底，
# 表现为自适应缓存静默失效，不崩溃）。
-keepclassmembers class com.exarry.arvtubelink.mapping.MapperOverrides { <fields>; }

# Gson 依赖泛型签名做类型解析（TypeToken / 反射取字段类型）。
# R8 full mode 下 -keepattributes 只对同时命中 -keep 规则的类生效，故必须显式保留。
-keepattributes Signature

# ----------------------------------------------------------------------------
# 说明：
#  - 这里刻意不使用 `allowobfuscation`，与 Gson 自带的 gson.pro 不同 —— 本项目
#    以「字段名即协议」为前提，保留字段名比节省几个字节更重要。
#  - 华为 AR Engine 无需在此加规则：其 AAR 自带 consumer proguard.txt，已
#    keep com.huawei.hiar.* / native 方法 / @UsedByNative / @UsedByReflection，
#    AGP 会自动合并。
#  - 本项目源码无 Class.forName / 反射字段访问，仅 InAppLog.kt 与 VtsWsClient.kt
#    用 javaClass.simpleName 打日志（混淆后仅日志可读性下降，无功能影响）。
# ----------------------------------------------------------------------------
