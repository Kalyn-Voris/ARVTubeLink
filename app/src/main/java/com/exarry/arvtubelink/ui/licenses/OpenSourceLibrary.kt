package com.exarry.arvtubelink.ui.licenses

import androidx.annotation.StringRes
import com.exarry.arvtubelink.R

/**
 * 一条第三方依赖记录。
 *
 * @param name 库名。**刻意保留英文原名**（与上游仓库、LICENSE/NOTICE 文件里的正式标识
 *   一致）——翻译成中文后用户反而无法与依赖清单、构建脚本、错误日志对应上。
 * @param descriptionRes 一句话说明「它在本项目里负责什么」，走 `@StringRes`
 *   而非裸字符串：这样中英双版由 `values/` 与 `values-en/` 自动切换，
 *   而不是把两种语言拼在一行里同时显示（那会让界面变成中英混排）。
 *   文案刻意写成与**本项目用途**相关而非通用介绍——用户看这页想知道的是
 *   「ARVTubeLink 为什么需要它」，不是通读上游 README。
 * @param licenseName 协议短名（如 "Apache License 2.0"），列表页直接展示。
 *   语言中立，不需翻译。
 * @param licenseText 协议全文，用于二级详情页。多个库指向同一常量实例。
 * @param version 实际解析到的版本号（取自 `:app:dependencies` 的 releaseRuntimeClasspath，
 *   非声明版本——声明与传递依赖上浮后的真实版本常不一致，展示给用户的是后者）
 * @param url 上游项目地址，详情页给一个可追溯出处
 */
data class OpenSourceLibrary(
    val name: String,
    @param:StringRes val descriptionRes: Int,
    val licenseName: String,
    val licenseText: String,
    val version: String,
    val url: String,
)

/**
 * 列表里的一个分区（带标题的一组条目）。
 *
 * 为什么先分组再排序、而不是继续拉平成一个 list：页面上「核心平台」与「开源库」
 * 是**性质不同**的两类东西——前者是专有 SDK 或仅接口互操作的第三方服务，后者是
 * 真正随包分发的 Apache-2.0 代码。拉平后靠字母序混排会让用户误以为华为 AR Engine
 * 也是开源软件，这正是这一版要修的表达问题。
 *
 * @param titleRes 分区标题（界面用 bodyTitle 级 Text 渲染，见 LicensesList）
 * @param libraries 该分区内的条目，**按此处声明顺序**展示，不再做字母序重排
 */
data class OpenSourceLibrarySection(
    @param:StringRes val titleRes: Int,
    val libraries: List<OpenSourceLibrary>,
)

/**
 * 本项目的第三方清单（v1.3.x），分「核心平台 / 开源库」两段。
 *
 * ## 收录口径
 * 只收**直接声明**在 `app/build.gradle.kts` 的 `dependencies {}` 里的库，以及随包分发
 * 的**必要运行时**（Okio 是 OkHttp 的实现依赖，Kotlin 标准库是语言运行时）。
 * 不逐条罗列 AndroidX 的数百个传递 artifact（core / lifecycle / savedstate ...）——
 * 它们全部是 Apache-2.0、全部由 AndroidX 统一发布，拆成几十行只会让页面变成噪音，
 * 反而看不出真实的技术选型。同理，「OkHttp & Okio」「Kotlin Coroutines &
 * Standard Library」各自合并成一条：它们同源、同协议、总是成对出现，分开只增加行数。
 *
 * 另收两条「非开源但用户会问」的条目，单独放进「核心平台」段：
 * 华为 AR Engine（专有 SDK，必须说明性质）与 VTube Studio（仅通过公开 WebSocket
 * API 互操作，不分发其代码）。
 *
 * ## 版本号来源
 * 全部取自 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
 * 的实际解析结果，与打进 APK 的版本一致。
 */
val licenseSections: List<OpenSourceLibrarySection> = listOf(
    OpenSourceLibrarySection(
        titleRes = R.string.oss_section_core_platforms,
        libraries = listOf(
            OpenSourceLibrary(
                name = "HUAWEI AR Engine SDK",
                descriptionRes = R.string.oss_lib_arengine,
                licenseName = "Huawei Proprietary",
                licenseText = LicenseTexts.HUAWEI_PROPRIETARY,
                version = "4.0.0.5",
                url = "https://developer.huawei.com/consumer/en/hms/huawei-arengine/",
            ),
            OpenSourceLibrary(
                name = "VTube Studio API",
                descriptionRes = R.string.oss_lib_vtubestudio,
                licenseName = "Third-party Notice",
                licenseText = LicenseTexts.VTUBE_STUDIO_INTEROP,
                version = "API 1.0",
                url = "https://denchisoft.com/",
            ),
        ),
    ),
    OpenSourceLibrarySection(
        titleRes = R.string.oss_section_open_source,
        libraries = listOf(
            OpenSourceLibrary(
                name = "AndroidX / Jetpack Compose",
                descriptionRes = R.string.oss_lib_androidx,
                licenseName = "Apache License 2.0",
                licenseText = LicenseTexts.APACHE_2_0,
                version = "Compose BOM 2026.09.00 (ui 1.12.1 / material3 1.4.0)",
                url = "https://developer.android.com/jetpack",
            ),
            OpenSourceLibrary(
                name = "Kotlin Coroutines & Standard Library",
                descriptionRes = R.string.oss_lib_kotlin,
                licenseName = "Apache License 2.0",
                licenseText = LicenseTexts.APACHE_2_0,
                version = "Coroutines 1.10.1 · Stdlib 2.2.10",
                url = "https://kotlinlang.org/",
            ),
            OpenSourceLibrary(
                name = "OkHttp & Okio",
                descriptionRes = R.string.oss_lib_okhttp,
                licenseName = "Apache License 2.0",
                licenseText = LicenseTexts.APACHE_2_0,
                version = "OkHttp 4.12.0 · Okio 3.6.0",
                url = "https://square.github.io/okhttp/",
            ),
            OpenSourceLibrary(
                name = "Gson",
                descriptionRes = R.string.oss_lib_gson,
                licenseName = "Apache License 2.0",
                licenseText = LicenseTexts.APACHE_2_0,
                version = "2.11.0",
                url = "https://github.com/google/gson",
            ),
        ),
    ),
)
