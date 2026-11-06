plugins {
    alias(libs.plugins.android.application) apply false
    // Compose 编译器插件版本须与 Kotlin 完全一致（2.2.10，AGP 9.4.1 内置）
    alias(libs.plugins.kotlin.compose) apply false
}