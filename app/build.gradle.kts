import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// keystore.properties 在工程根目录，projectDir 为 app/，须用 rootProject.file 解析
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val signingStorePath = keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val signingStorePassword = keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val signingKeyAlias = keystoreProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val signingKeyPassword = keystoreProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }

val hasReleaseSigning = signingStorePath != null && signingStorePassword != null &&
    signingKeyAlias != null && signingKeyPassword != null &&
    rootProject.file(signingStorePath).exists()

if (!hasReleaseSigning) {
    logger.lifecycle("[signing] keystore.properties 缺失/不完整或密钥文件不存在 → release 产物不签名")
}

android {
    namespace = "com.exarry.arvtubelink"
    compileSdk {
        version = release(37)
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword

                // AGP 三开关默认值并非全开，须显式写死；minSdk 26 由 V2/V3 覆盖，V1 关闭
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.exarry.arvtubelink"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.3.1 (20261112)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // splits.abi 与 defaultConfig.ndk.abiFilters 不能共存，且 splits.abi 同步约束 externalNativeBuild 只编这两个架构
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            // AGP 9 拆成三条独立开关，须同开；只开 optimization 时 R8 不跑
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            // AGP 9 不按文件名自动发现 proguard-rules.pro，须显式引用
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidResources {
        // localeFilters 对依赖 AAR 的资源同样生效
        localeFilters += listOf("zh", "en")
    }

    // x86/x86_64 仅模拟器需要，华为 AR Engine 在模拟器上不工作；不产 universal 合并包
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ndkVersion 须显式写死：AGP 默认取本机最新 NDK，未安装时按已接受 license 自动下载该版本
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // 全 Compose 项目，无 layout 与 viewBinding 引用
        viewBinding = false
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/**/LICENSE.txt"
            excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // material-icons 不被 material3 传递暴露，须显式声明
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // activity / lifecycle 不受 BOM 管理，须显式版本
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 华为 AR Engine，来自 developer.huawei.com/repo
    implementation("com.huawei.hms:arenginesdk:4.0.0.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // 模型导入：普通 CoroutineWorker 后台调度，不升前台服务
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// 产物名 ARVTubeLink-release-<abi>-<yyMMdd>[-<n>]；configuration-cache 下执行期值须提前压成 String
// ABI 标签优先取文件名，认不出回退读 zip 的 lib/<abi>/
val buildCounterPath = rootProject.file(".build-counter").absolutePath

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType != "release") return@onVariants

        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val apkDirPath = layout.buildDirectory.dir("outputs/apk/${variant.buildType}")
            .get().asFile.absolutePath
        val counterPath = buildCounterPath

        val renameTask = tasks.register("rename${cap}Apk") {
            dependsOn(tasks.named("package$cap"))
            // 产物名带构建序号，须每次重算
            outputs.upToDateWhen { false }

            doLast {
                val dir = File(apkDirPath)

                val apks = dir.listFiles()
                    ?.filter { it.extension == "apk" && !it.name.startsWith("ARVTubeLink-release-") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (apks.isEmpty()) return@doLast

                fun abiTagOf(apk: File): String {
                    val fromName = Regex("""-((?:arm64-v8a|armeabi-v7a|x86_64|x86))-""")
                        .find(apk.name)?.groupValues?.get(1)
                    val abi = fromName ?: run {
                        val abis = ZipFile(apk).use { zf ->
                            zf.entries().asSequence()
                                .map { it.name }
                                .filter { it.startsWith("lib/") && it.length > 4 }
                                .map { it.removePrefix("lib/").substringBefore('/') }
                                .filter { it.isNotBlank() }
                                .toSortedSet()
                        }
                        when (abis.size) {
                            0 -> "universal"
                            else -> if (abis.size == 1) abis.first() else "universal"
                        }
                    }
                    return when (abi) {
                        "arm64-v8a" -> "v8a"
                        "armeabi-v7a" -> "v7a"
                        else -> abi
                    }
                }

                val today = SimpleDateFormat("yyMMdd", Locale.US).format(Date())

                // 同一次构建产出的多个包共用一个序号
                val counter = File(counterPath)
                var seq = 1
                if (counter.exists()) {
                    val parts = counter.readText().trim().split(' ')
                    if (parts.size == 2 && parts[0] == today) {
                        seq = parts[1].toIntOrNull()?.plus(1) ?: 1
                    }
                }
                counter.writeText("$today $seq")

                val suffix = if (seq <= 1) "" else "-$seq"
                val renamed = mutableListOf<File>()

                for (apk in apks) {
                    val target = File(dir, "ARVTubeLink-release-${abiTagOf(apk)}-$today$suffix.apk")
                    // 先复制再删源，反序会连源一起删
                    if (apk.absolutePath != target.absolutePath) {
                        apk.copyTo(target, overwrite = true)
                        apk.delete()
                    }
                    renamed += target
                }

                val keep = renamed.map { it.name }.toSet()
                dir.listFiles()
                    ?.filter { it.extension == "apk" && it.name !in keep }
                    ?.forEach { it.delete() }

                renamed.sortedBy { it.name }.forEach { println("[apk] ${it.name}") }
            }
        }

        // 须由 assemble 反向依赖，否则任务虽注册但不在任务图内，不会执行
        tasks.matching { it.name == "assemble$cap" }.configureEach {
            dependsOn(renameTask)
        }
    }
}
