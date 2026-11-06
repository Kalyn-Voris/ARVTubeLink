package com.exarry.arvtubelink.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 强调青：深色 #00E5FF，浅色 #00707F（浅底上低亮度才可辨识） */
private val AccentCyanDark = Color(0xFF00E5FF)
private val AccentCyanLight = Color(0xFF00707F)

/** 当前主题下的强调青，仅装饰用途 */
@Composable
fun accentColor(): Color = if (LocalThemeIsDark.current) AccentCyanDark else AccentCyanLight

/** 同一强调色按 alpha 衰减，供图表底轨 / 网格线使用 */
@Composable
fun accentColor(alpha: Float): Color = accentColor().copy(alpha = alpha)
