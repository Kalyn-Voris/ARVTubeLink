import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    // Compose 编译器（版本 = Kotlin 2.2.10，与 AGP 内置 Kotlin 强一致，见根 build.gradle.kts 注释）
    alias(libs.plugins.kotlin.compose)
}

// ---------- Release 签名配置（v1.2.9） ----------
//
// 安全约定（务必保持）：
//  - 密钥路径 / 别名 / 口令**一律不进本文件**，只来自仓库根目录的 keystore.properties；
//  - keystore.properties 已在 .gitignore 中忽略，绝不入库；
//  - 文件缺失时 signRelease = null → release 产物退回未签名 APK，构建不报错，
//    这样没有密钥的机器（CI、同事）依然能跑 assembleRelease 做体积/lint 校验。
//
// 为什么用 rootProject.file：keystore.properties 放在工程根目录（与 local.properties 同级），
// 而本脚本的 projectDir 是 app/，直接相对路径会指向 app/keystore.properties 找不到文件。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

/** 签名四项；任一项为空即视为"未配置签名"。 */
val signingStorePath = keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val signingStorePassword = keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val signingKeyAlias = keystoreProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val signingKeyPassword = keystoreProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }

/** 四项齐全且有密钥文件才认为是可用的签名配置。 */
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

    // 签名配置块：只有四项齐全时才创建，避免留下一个 storeFile=null 的半成品配置
    // 让 AGP 在打包阶段抛出难以定位的异常。密钥信息全部来自 keystore.properties。
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword

                // ---- 签名方案（v1.3.0）----
                // 必须显式打开 V2 与 V3：三个开关的默认值不是"全开"，不写死会出现
                // apksigner 报 "Verified using v3 scheme: false" 的情况（v2.9 产物实测如此），
                // 老系统装得上但 Play/更新链路缺少 V3 的密钥轮换与完整性保护。
                // V1（JAR 签名）保持关闭：minSdk=26，V2/V3 全覆盖，V1 只会白增体积。
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
        versionCode = 9
        versionName = "1.2.6 (20261102)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 签名：来自 keystore.properties（见文件顶部说明）。未配置时不设置该属性，
            // AGP 会产出 app-release-unsigned.apk —— 文件名本身即提示"没签名"。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            // ---- R8 三件套（v1.2.6 起开启）----
            // AGP 9 中这是两条独立开关，必须同时打开才有完整效果：
            //   isMinifyEnabled  -> minifiedEnabled，控制 R8 的「压缩+混淆」阶段
            //   isShrinkResources-> resourcesShrink，控制无用资源裁剪（依赖 minify 生效）
            //   optimization.enable -> optimizationEnabled，控制 R8 的「优化」阶段
            // 只开 optimization 不开 minify 时 R8 仍不会跑（源码实证见
            // OptimizationCreationConfigImpl.minifiedEnabled）。
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
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

    // ---------- ABI 分包（v1.3.0）----------
    //
    // 只出 arm64-v8a 与 armeabi-v7a 两个包：x86/x86_64 仅模拟器需要，而本项目依赖的
    // 华为 AR Engine 在模拟器上本就不工作，多打一份纯属白占体积。
    //
    // isUniversalApk = false：不产 universal 合并包。华为 AR Engine 的 native 库
    // （libhuawei_arengine_*.so，约 400KB/架构）在两个包里各出现一次，合并包会让用户
    // 额外下载另一架构的 so。
    //
    // 副作用：产物不再叫 app-release.apk，而是 app-<abi>-release.apk 两个，
    // 因此下方 renameReleaseApk 的产物枚举逻辑必须同步改（见该任务注释）。
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
    buildFeatures {
        // v1.2.6：全 Compose 项目，res/layout 为空目录、零 viewBinding 引用 → 关闭以省构建期代码生成
        viewBinding = false
        compose = true
    }

    packaging {
        resources {
            // 剔除依赖库重复携带的第三方许可文本（5 份 LICENSE.txt ≈ 50 KB 纯重复）。
            // 注意：这些文本仅在 APK 内，不属于本仓库的 LICENSE / NOTICE 合规声明。
            excludes += "META-INF/**/LICENSE.txt"
            // OkHttp 的 publicsuffixes.gz（≈41 KB）仅供 Cookie 域匹配使用；
            // 本项目零 Cookie 处理（无 CookieJar、无 Set-Cookie 解析），属纯死重。
            excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
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

// ---------- 产物重命名 ----------
//
// 目标名：ARVTubeLink-release-<abi>-<yyMMdd>[-<n>].apk，n 为当天第几次构建（首次省略）。
//
// ABI 标签取值（v1.3.0 起开启 ABI 分包，一次构建产出多个 APK）：
//  - 优先用**文件名**里的架构：AGP 分包的产物名是 app-<abi>-release.apk，
//    直接截取比开 zip 读 lib/ 更稳（分包后每个包只会含自己那一个 ABI）；
//  - 文件名认不出时回退读 zip 内的 lib/<abi>/ 目录。这是旧版单包/universal 的判定路径，
//    保留它是因为改名任务也可能被独立触发（产物目录里还躺着未改名的默认产物）。
//
// 计数依赖仓库根目录的 .build-counter 文件（不入库）：先读日期，日期变了就归零，
// 同日则 +1。用文件而非时间戳，是因为"第几次构建"语义上就该是计数，
// 且同一分钟内连打两次时时间戳方案会撞名。
//
// 注意：本项目开了 configuration-cache，doLast 里禁止捕获脚本作用域对象
// （project / logger / val 闭包都会报 "cannot serialize Gradle script object references"）。
// 因此把所有需要在执行期用到的值提前压成 String，任务体只碰局部变量与 File。
val buildCounterPath = rootProject.file(".build-counter").absolutePath

androidComponents {
    onVariants(selector().all()) { variant ->
        // 只重命名 release；debug 保留 AGP 默认名，便于 IDE 识别
        if (variant.buildType != "release") return@onVariants

        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val apkDirPath = layout.buildDirectory.dir("outputs/apk/${variant.buildType}")
            .get().asFile.absolutePath
        val counterPath = buildCounterPath

        val renameTask = tasks.register("rename${cap}Apk") {
            dependsOn(tasks.named("package$cap"))
            // 每次都重算：产物名带构建序号，命中 UP-TO-DATE 就不会更新
            outputs.upToDateWhen { false }

            doLast {
                val dir = File(apkDirPath)

                // 分包后同目录有多个 APK，必须逐个处理。
                // 旧实现取 firstOrNull 再「复制后删掉其余所有 apk」——在双包场景下，
                // 处理第二个包时会把第一个已改好名的包一并删除，最终只剩一个。
                val apks = dir.listFiles()
                    ?.filter { it.extension == "apk" && !it.name.startsWith("ARVTubeLink-release-") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (apks.isEmpty()) return@doLast

                // ABI 标签：先看文件名（app-<abi>-release.apk / app-<abi>-<flavor>-release.apk），
                // 认不出再回退开 zip 读 lib/<abi>/。
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
                            // 多 ABI 合并包（isUniversalApk=true 时）也标 universal
                            else -> if (abis.size == 1) abis.first() else "universal"
                        }
                    }
                    return when (abi) {
                        "arm64-v8a" -> "v8a"
                        "armeabi-v7a" -> "v7a"
                        else -> abi                        // 未预期架构原样输出，便于发现
                    }
                }

                val today = SimpleDateFormat("yyMMdd", Locale.US).format(Date())

                // 计数：本次构建算「一次」，同一次构建产出的多个包共用同一个序号，
                // 而不是每个包各加 1（否则双包会变成 xxx-5.apk / xxx-6.apk，看起来像两次构建）。
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
                    // 先落目标名再删源文件；反序会把自己的源一起删掉（曾经踩过）。
                    if (apk.absolutePath != target.absolutePath) {
                        apk.copyTo(target, overwrite = true)
                        apk.delete()
                    }
                    renamed += target
                }

                // 清理目标名之外的残留（如旧的 universal 包、AGP 默认名产物）。
                val keep = renamed.map { it.name }.toSet()
                dir.listFiles()
                    ?.filter { it.extension == "apk" && it.name !in keep }
                    ?.forEach { it.delete() }

                renamed.sortedBy { it.name }.forEach { println("[apk] ${it.name}") }
            }
        }

        // 关键：让 assemble<Variant> 依赖本任务。只 dependsOn(package) 而不反向挂载，
        // 任务虽注册但不在 assemble 的任务图里，永远不会执行。
        tasks.matching { it.name == "assemble$cap" }.configureEach {
            dependsOn(renameTask)
        }
    }
}
