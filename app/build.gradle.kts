plugins {
    alias(libs.plugins.android.application)
    // Compose 编译器（版本 = Kotlin 2.2.10，与 AGP 内置 Kotlin 强一致，见根 build.gradle.kts 注释）
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.exarry.arvtubelink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.exarry.arvtubelink"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "20261030-7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 引用 R8/Gson keep 规则；optimization 打开（enable = true）后这些规则才生效。
            // AGP 9 不按文件名自动发现 proguard-rules.pro，必须显式引用。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidResources {
        // 只保留中英文资源。依赖库（AppCompat/Material 等）默认带入 84 种语言字符串，
        // 其中约 714 KB 与中英文无关。localeFilters 对依赖 AAR 的资源同样生效。
        localeFilters += listOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)

    // Compose（版本选型依据见 libs.versions.toml 注释；ui/material3 由 BOM 管理）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // 顶栏 info 图标（Icons.Filled.Info）：m3 1.4.0 不传递暴露 material-icons，需显式声明（版本由 BOM 管）
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // activity / lifecycle 不受 BOM 管理，显式版本
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 华为 AR Engine（developer.huawei.com/repo 的 maven-metadata.xml：latest=release=4.0.0.5）
    implementation("com.huawei.hms:arenginesdk:4.0.0.5")
    // WebSocket 客户端（连接 VTube Studio）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // VTS JSON 协议序列化
    implementation("com.google.code.gson:gson:2.11.0")
    // 协程（可用性异步检测等）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
