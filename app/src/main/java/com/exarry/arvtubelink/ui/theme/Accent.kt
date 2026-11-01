package com.exarry.arvtubelink.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 赛博强调色（青）：v1.2.6 UI 升级新增，用于雷达扫描、实时图表、卡片描边发光。
 *
 * 为什么不进 colors_compose.xml：该文件的约定是「Material ColorScheme 槽位 + 卡片容器色」，
 * 条目由 Theme.kt 的 arvColorScheme 映射；本强调色不是任何 M3 槽位，而是纯装饰性的
 * 「科技感点缀」，故按 [LocalThemeIsDark] 在 Kotlin 侧二选一，保持 res/ 层零改动。
 *
 * 深浅两档的原因：`#00E5FF` 在浅色背景（#F8F9FF）上对比度不足（ΔL 过小），
 * 浅色档降到 `#00707F` 保证可辨识；深色档保持高饱和青色以撑住"赛博"调性。
 */
private val AccentCyanDark = Color(0xFF00E5FF)
private val AccentCyanLight = Color(0xFF00707F)

/** 当前主题下的强调色（青）。装饰性用途，语义信息不得只靠它表达。 */
@Composable
fun accentColor(): Color = if (LocalThemeIsDark.current) AccentCyanDark else AccentCyanLight

/**
 * 强调色的"熄灭"版本：用于图表底轨、网格线、未激活刻度等**低存在感**元素。
 *
 * @param alpha 0..1，调用方按元素层级自选（网格 0.10 / 底轨 0.16 / 描边 0.22）。
 */
@Composable
fun accentColor(alpha: Float): Color = accentColor().copy(alpha = alpha)
