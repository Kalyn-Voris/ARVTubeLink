# VTS 协议模型：字段名即 JSON 键名，无 @SerializedName，须保留类与字符串常量
-keep class com.exarry.arvtubelink.network.** { *; }

# MapperOverrides 经 Gson 持久化到 SharedPreferences，字段名混淆后读回失败
-keepclassmembers class com.exarry.arvtubelink.mapping.MapperOverrides { <fields>; }

# Gson 依赖泛型签名解析字段类型
-keepattributes Signature
