package com.exarry.arvtubelink.ui.theme

import androidx.annotation.ColorRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.exarry.arvtubelink.R

/**
 * 当前是否深色。真源 = AppRoot 顶层的 isDark state（跟随系统 → isSystemInDarkTheme()；
 * 强制深色 → true），经 CompositionLocalProvider 在 ThemeTransition content 内下发。
 * 消费者 = ColorScheme 槽外的自定义取色（如 SettingsCard 的 [themedColorResource]）——
 * 深浅不经资源限定符解析。
 */
val LocalThemeIsDark = compositionLocalOf { true }

/**
 * 双前缀取色：按 [LocalThemeIsDark] 在 colors_compose.xml 的
 * *Dark / *Light 条目间二选一。仅用于无法归入 ColorScheme 槽的自定义色
 * （当前唯一消费者 = SettingsCard 的 colorCardContainer，深浅跨档：Container / High）。
 */
@Composable
fun themedColorResource(@ColorRes dark: Int, @ColorRes light: Int): Color =
    colorResource(if (LocalThemeIsDark.current) dark else light)

/**
 * ARVTubeLink 主题（纯 Compose 双前缀色板）。
 *
 * 深浅由 [darkTheme] 参数显式驱动：arvColorScheme 在 colors_compose.xml 的 *Dark / *Light
 * 条目间二选一（values/ 无限定符、显式后缀）。values/values-night 的限定符条目只服务
 * XML 层窗口首帧/splash/statusBar，与 Compose 无关。
 *
 * 派生槽规则（两分支同构，色板未提供的槽位）：
 *  - tertiary/onTertiary 沿用 secondary 系（板只给 tertiaryContainer 系）；
 *  - surfaceVariant → ContainerHigh；surfaceDim → ContainerLow；
 *    surfaceBright → ContainerHigh；surfaceContainerLowest → surface 本体。
 *
 * internal：仅供本文件 [ARVTubeLinkTheme] 调用。
 *
 * [amoledBlack] 仅深色分支生效：开启后 background 槽用纯黑（AMOLED 防烧屏），其余槽位不变。
 */
@Composable
fun ARVTubeLinkTheme(darkTheme: Boolean, amoledBlack: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = arvColorScheme(darkTheme, amoledBlack),
        content = content,
    )
}

@Composable
internal fun arvColorScheme(dark: Boolean, amoledBlack: Boolean = false): ColorScheme =
    if (dark) {
        darkColorScheme(
            // ===== 主色 =====
            primary = colorResource(R.color.colorPrimaryDark),
            onPrimary = colorResource(R.color.colorOnPrimaryDark),
            primaryContainer = colorResource(R.color.colorPrimaryContainerDark),
            onPrimaryContainer = colorResource(R.color.colorOnPrimaryContainerDark),
            // ===== 次色 =====
            secondary = colorResource(R.color.colorSecondaryDark),
            onSecondary = colorResource(R.color.colorOnSecondaryDark),
            secondaryContainer = colorResource(R.color.colorSecondaryContainerDark),
            onSecondaryContainer = colorResource(R.color.colorOnSecondaryContainerDark),
            // tertiary 本体：板未提供，沿用 secondary 系
            tertiary = colorResource(R.color.colorSecondaryDark),
            onTertiary = colorResource(R.color.colorOnSecondaryDark),
            tertiaryContainer = colorResource(R.color.colorTertiaryContainerDark),
            onTertiaryContainer = colorResource(R.color.colorOnTertiaryContainerDark),
            // ===== 背景 / 面层 =====
            // AMOLED 开关只动 background 槽（其余槽位维持色板值，深灰容器在纯黑上分层）
            background =
                if (amoledBlack) colorResource(R.color.black)
                else colorResource(R.color.colorBackgroundDark),
            onBackground = colorResource(R.color.colorOnBackgroundDark),
            surface = colorResource(R.color.colorSurfaceDark),
            onSurface = colorResource(R.color.colorOnSurfaceDark),
            surfaceVariant = colorResource(R.color.colorSurfaceContainerHighDark),
            onSurfaceVariant = colorResource(R.color.colorOnSurfaceVariantDark),
            // surfaceDim/Bright/Lowest：板未提供，按明度派生
            surfaceDim = colorResource(R.color.colorSurfaceContainerLowDark),
            surfaceBright = colorResource(R.color.colorSurfaceContainerHighDark),
            surfaceContainerLowest = colorResource(R.color.colorSurfaceDark),
            surfaceContainerLow = colorResource(R.color.colorSurfaceContainerLowDark),
            surfaceContainer = colorResource(R.color.colorSurfaceContainerDark),
            surfaceContainerHigh = colorResource(R.color.colorSurfaceContainerHighDark),
            surfaceContainerHighest = colorResource(R.color.colorSurfaceContainerHighestDark),
            // ===== 描边 =====
            outline = colorResource(R.color.colorOutlineDark),
            outlineVariant = colorResource(R.color.colorOutlineVariantDark),
            // ===== 错误 =====
            error = colorResource(R.color.colorErrorDark),
            onError = colorResource(R.color.colorOnErrorDark),
            errorContainer = colorResource(R.color.colorErrorContainerDark),
            onErrorContainer = colorResource(R.color.colorOnErrorContainerDark),
            // ===== 反色 / 遮罩 =====
            inverseSurface = colorResource(R.color.colorInverseSurfaceDark),
            inverseOnSurface = colorResource(R.color.colorInverseOnSurfaceDark),
            inversePrimary = colorResource(R.color.colorInversePrimaryDark),
            scrim = colorResource(R.color.black),
        )
    } else {
        lightColorScheme(
            // ===== 主色 =====
            primary = colorResource(R.color.colorPrimaryLight),
            onPrimary = colorResource(R.color.colorOnPrimaryLight),
            primaryContainer = colorResource(R.color.colorPrimaryContainerLight),
            onPrimaryContainer = colorResource(R.color.colorOnPrimaryContainerLight),
            // ===== 次色 =====
            secondary = colorResource(R.color.colorSecondaryLight),
            onSecondary = colorResource(R.color.colorOnSecondaryLight),
            secondaryContainer = colorResource(R.color.colorSecondaryContainerLight),
            onSecondaryContainer = colorResource(R.color.colorOnSecondaryContainerLight),
            // tertiary 本体：板未提供，沿用 secondary 系
            tertiary = colorResource(R.color.colorSecondaryLight),
            onTertiary = colorResource(R.color.colorOnSecondaryLight),
            tertiaryContainer = colorResource(R.color.colorTertiaryContainerLight),
            onTertiaryContainer = colorResource(R.color.colorOnTertiaryContainerLight),
            // ===== 背景 / 面层 =====
            background = colorResource(R.color.colorBackgroundLight),
            onBackground = colorResource(R.color.colorOnBackgroundLight),
            surface = colorResource(R.color.colorSurfaceLight),
            onSurface = colorResource(R.color.colorOnSurfaceLight),
            surfaceVariant = colorResource(R.color.colorSurfaceContainerHighLight),
            onSurfaceVariant = colorResource(R.color.colorOnSurfaceVariantLight),
            // surfaceDim/Bright/Lowest：板未提供，按明度派生
            surfaceDim = colorResource(R.color.colorSurfaceContainerLowLight),
            surfaceBright = colorResource(R.color.colorSurfaceContainerHighLight),
            surfaceContainerLowest = colorResource(R.color.colorSurfaceLight),
            surfaceContainerLow = colorResource(R.color.colorSurfaceContainerLowLight),
            surfaceContainer = colorResource(R.color.colorSurfaceContainerLight),
            surfaceContainerHigh = colorResource(R.color.colorSurfaceContainerHighLight),
            surfaceContainerHighest = colorResource(R.color.colorSurfaceContainerHighestLight),
            // ===== 描边 =====
            outline = colorResource(R.color.colorOutlineLight),
            outlineVariant = colorResource(R.color.colorOutlineVariantLight),
            // ===== 错误 =====
            error = colorResource(R.color.colorErrorLight),
            onError = colorResource(R.color.colorOnErrorLight),
            errorContainer = colorResource(R.color.colorErrorContainerLight),
            onErrorContainer = colorResource(R.color.colorOnErrorContainerLight),
            // ===== 反色 / 遮罩 =====
            inverseSurface = colorResource(R.color.colorInverseSurfaceLight),
            inverseOnSurface = colorResource(R.color.colorInverseOnSurfaceLight),
            inversePrimary = colorResource(R.color.colorInversePrimaryLight),
            scrim = colorResource(R.color.black),
        )
    }
