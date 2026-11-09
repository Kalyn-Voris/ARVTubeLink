package com.exarry.arvtubelink.ui.licenses

import androidx.annotation.StringRes
import com.exarry.arvtubelink.R

/** name 保留上游英文原名，descriptionRes 走 @StringRes 供 values / values-en 切换 */
data class OpenSourceLibrary(
    val name: String,
    @param:StringRes val descriptionRes: Int,
    val licenseName: String,
    val licenseText: String,
    val version: String,
    val url: String,
)

/** libraries 按声明顺序展示，不重排 */
data class OpenSourceLibrarySection(
    @param:StringRes val titleRes: Int,
    val libraries: List<OpenSourceLibrary>,
)

/** 版本取 :app:dependencies 的 releaseRuntimeClasspath 实际解析结果，非声明版本 */
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
            OpenSourceLibrary(
                name = "AndroidX WorkManager",
                descriptionRes = R.string.oss_lib_workmanager,
                licenseName = "Apache License 2.0",
                licenseText = LicenseTexts.APACHE_2_0,
                version = "2.12.0",
                url = "https://developer.android.com/jetpack/androidx/releases/work",
            ),
            OpenSourceLibrary(
                name = "Sakura2D Purism Core",
                descriptionRes = R.string.oss_lib_purism,
                licenseName = "MIT License",
                licenseText = LicenseTexts.MIT_PURISM_CORE,
                version = "1.1.0 (PSM_TRUE_VERSION 0x01010000)",
                url = "https://sakura2d.org/",
            ),
            OpenSourceLibrary(
                name = "stb_image",
                descriptionRes = R.string.oss_lib_stb_image,
                licenseName = "Public Domain / MIT",
                licenseText = LicenseTexts.STB_IMAGE,
                version = "v2.30",
                url = "https://github.com/nothings/stb",
            ),
        ),
    ),
)
