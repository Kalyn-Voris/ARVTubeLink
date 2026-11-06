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

/** 当前是否深色，由 AppRoot 的 isDark 经 CompositionLocalProvider 下发 */
val LocalThemeIsDark = compositionLocalOf { true }

/** 按 LocalThemeIsDark 在 colors_compose.xml 的 *Dark / *Light 条目间二选一 */
@Composable
fun themedColorResource(@ColorRes dark: Int, @ColorRes light: Int): Color =
    colorResource(if (LocalThemeIsDark.current) dark else light)

/** 色板取 colors_compose.xml 的显式后缀条目；amoledBlack 仅深色分支把 background 换成纯黑 */
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
            primary = colorResource(R.color.colorPrimaryDark),
            onPrimary = colorResource(R.color.colorOnPrimaryDark),
            primaryContainer = colorResource(R.color.colorPrimaryContainerDark),
            onPrimaryContainer = colorResource(R.color.colorOnPrimaryContainerDark),
            secondary = colorResource(R.color.colorSecondaryDark),
            onSecondary = colorResource(R.color.colorOnSecondaryDark),
            secondaryContainer = colorResource(R.color.colorSecondaryContainerDark),
            onSecondaryContainer = colorResource(R.color.colorOnSecondaryContainerDark),
            // 板未提供 tertiary 本体，沿用 secondary 系
            tertiary = colorResource(R.color.colorSecondaryDark),
            onTertiary = colorResource(R.color.colorOnSecondaryDark),
            tertiaryContainer = colorResource(R.color.colorTertiaryContainerDark),
            onTertiaryContainer = colorResource(R.color.colorOnTertiaryContainerDark),
            // AMOLED 只换 background 槽，容器色仍取色板
            background =
                if (amoledBlack) colorResource(R.color.black)
                else colorResource(R.color.colorBackgroundDark),
            onBackground = colorResource(R.color.colorOnBackgroundDark),
            surface = colorResource(R.color.colorSurfaceDark),
            onSurface = colorResource(R.color.colorOnSurfaceDark),
            surfaceVariant = colorResource(R.color.colorSurfaceContainerHighDark),
            onSurfaceVariant = colorResource(R.color.colorOnSurfaceVariantDark),
            // 板未提供，按明度取 ContainerLow / ContainerHigh / surface
            surfaceDim = colorResource(R.color.colorSurfaceContainerLowDark),
            surfaceBright = colorResource(R.color.colorSurfaceContainerHighDark),
            surfaceContainerLowest = colorResource(R.color.colorSurfaceDark),
            surfaceContainerLow = colorResource(R.color.colorSurfaceContainerLowDark),
            surfaceContainer = colorResource(R.color.colorSurfaceContainerDark),
            surfaceContainerHigh = colorResource(R.color.colorSurfaceContainerHighDark),
            surfaceContainerHighest = colorResource(R.color.colorSurfaceContainerHighestDark),
            outline = colorResource(R.color.colorOutlineDark),
            outlineVariant = colorResource(R.color.colorOutlineVariantDark),
            error = colorResource(R.color.colorErrorDark),
            onError = colorResource(R.color.colorOnErrorDark),
            errorContainer = colorResource(R.color.colorErrorContainerDark),
            onErrorContainer = colorResource(R.color.colorOnErrorContainerDark),
            inverseSurface = colorResource(R.color.colorInverseSurfaceDark),
            inverseOnSurface = colorResource(R.color.colorInverseOnSurfaceDark),
            inversePrimary = colorResource(R.color.colorInversePrimaryDark),
            scrim = colorResource(R.color.black),
        )
    } else {
        lightColorScheme(
            primary = colorResource(R.color.colorPrimaryLight),
            onPrimary = colorResource(R.color.colorOnPrimaryLight),
            primaryContainer = colorResource(R.color.colorPrimaryContainerLight),
            onPrimaryContainer = colorResource(R.color.colorOnPrimaryContainerLight),
            secondary = colorResource(R.color.colorSecondaryLight),
            onSecondary = colorResource(R.color.colorOnSecondaryLight),
            secondaryContainer = colorResource(R.color.colorSecondaryContainerLight),
            onSecondaryContainer = colorResource(R.color.colorOnSecondaryContainerLight),
            // 板未提供 tertiary 本体，沿用 secondary 系
            tertiary = colorResource(R.color.colorSecondaryLight),
            onTertiary = colorResource(R.color.colorOnSecondaryLight),
            tertiaryContainer = colorResource(R.color.colorTertiaryContainerLight),
            onTertiaryContainer = colorResource(R.color.colorOnTertiaryContainerLight),
            background = colorResource(R.color.colorBackgroundLight),
            onBackground = colorResource(R.color.colorOnBackgroundLight),
            surface = colorResource(R.color.colorSurfaceLight),
            onSurface = colorResource(R.color.colorOnSurfaceLight),
            surfaceVariant = colorResource(R.color.colorSurfaceContainerHighLight),
            onSurfaceVariant = colorResource(R.color.colorOnSurfaceVariantLight),
            // 板未提供，按明度取 ContainerLow / ContainerHigh / surface
            surfaceDim = colorResource(R.color.colorSurfaceContainerLowLight),
            surfaceBright = colorResource(R.color.colorSurfaceContainerHighLight),
            surfaceContainerLowest = colorResource(R.color.colorSurfaceLight),
            surfaceContainerLow = colorResource(R.color.colorSurfaceContainerLowLight),
            surfaceContainer = colorResource(R.color.colorSurfaceContainerLight),
            surfaceContainerHigh = colorResource(R.color.colorSurfaceContainerHighLight),
            surfaceContainerHighest = colorResource(R.color.colorSurfaceContainerHighestLight),
            outline = colorResource(R.color.colorOutlineLight),
            outlineVariant = colorResource(R.color.colorOutlineVariantLight),
            error = colorResource(R.color.colorErrorLight),
            onError = colorResource(R.color.colorOnErrorLight),
            errorContainer = colorResource(R.color.colorErrorContainerLight),
            onErrorContainer = colorResource(R.color.colorOnErrorContainerLight),
            inverseSurface = colorResource(R.color.colorInverseSurfaceLight),
            inverseOnSurface = colorResource(R.color.colorInverseOnSurfaceLight),
            inversePrimary = colorResource(R.color.colorInversePrimaryLight),
            scrim = colorResource(R.color.black),
        )
    }
