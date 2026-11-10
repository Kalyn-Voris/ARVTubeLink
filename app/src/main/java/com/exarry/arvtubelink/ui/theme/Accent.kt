package com.exarry.arvtubelink.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 强调青不进 colorScheme */
private val AccentCyanDark = Color(0xFF00E5FF)
private val AccentCyanLight = Color(0xFF00707F)

@Composable
fun accentColor(): Color = if (LocalThemeIsDark.current) AccentCyanDark else AccentCyanLight

/** 同一强调色按 alpha 衰减 */
@Composable
fun accentColor(alpha: Float): Color = accentColor().copy(alpha = alpha)
