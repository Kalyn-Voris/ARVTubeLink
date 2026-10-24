// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Compose 编译器插件：版本必须与 Kotlin 版本完全一致（本项目 Kotlin 2.2.10 由 AGP 9.4.1 内置）
    alias(libs.plugins.kotlin.compose) apply false
}