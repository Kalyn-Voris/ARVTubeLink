pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // 华为 AR Engine SDK 仓库（https://developer.huawei.com/repo/）
        maven {
            url = uri("https://developer.huawei.com/repo/")
        }
        mavenCentral()
    }
}

rootProject.name = "ARVTubeLink"
include(":app")
