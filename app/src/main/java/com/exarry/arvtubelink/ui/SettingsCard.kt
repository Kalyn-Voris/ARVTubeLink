package com.exarry.arvtubelink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.ui.theme.themedColorResource

/** 卡片圆角：m3 medium 档，与 12dp 卡距、大卡面协调（16dp 偏玩具感）。 */
private val CardCornerRadius = 12.dp

/**
 * 设置页卡片容器：标题 + 内容槽，Surface 承载（自带 clip + 语义）。
 * 容器色 colorCardContainer（colors.xml 单一来源，values/values-night 双套值）。
 * 阴影刻意不加：深色主题下不可见、浅色易显脏，深浅分层交给容器色档位。
 *
 * 扩展方式：新增卡片 = 在 SettingsScreen.kt 的 SettingsContent 里再写一个
 * `SettingsCard(stringResource(R.string.xxx)) { ... }` 调用，无需改本文件。
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        // colorCardContainer 深浅跨档（深=Container、浅=High）无法走单槽 → themedColorResource
        color = themedColorResource(R.color.colorCardContainerDark, R.color.colorCardContainerLight),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * 一行「标签 + 当前值 + 右箭头」的可点击行（非开关类设置的入口，如语言选择）。
 *
 * 与 SettingsScreen 内的 SwitchRow 视觉对齐（同一 4dp 纵向内距、label 用默认 bodyLarge），
 * 右侧 [value] 用 bodyMedium/onSurfaceVariant 弱化，箭头随语言方向自动镜像
 * （AutoMirrored 版本，RTL 下指左）。
 *
 * 点击区覆盖整行（fillMaxWidth + clickable），满足 Material 触控热区要求。
 */
@Composable
fun ClickableValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
