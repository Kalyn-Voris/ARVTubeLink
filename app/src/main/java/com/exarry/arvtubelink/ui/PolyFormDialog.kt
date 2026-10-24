package com.exarry.arvtubelink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exarry.arvtubelink.R

/**
 * PolyForm 许可全文弹窗：展示 res/raw/license.txt（PolyForm Noncommercial 1.0.0
 * 官方英文全文，15 节 Acceptance → Definitions）。
 *
 * 本文件是官方全文的 App 展示版：剥离了 Markdown 标题前缀（节标题改大写行），
 * 其余格式（强调线、弯引号、节内段落）逐字保留；法律效力以项目根 LICENSE 文件
 * 为准（后者另行处理，不在本任务范围）。
 *
 * back 行为：默认 DialogProperties（可 back）——back 关闭本弹窗，露出下层
 * LicenseDialog（各弹窗均为独立窗口，后组合者在上）。
 */
@Composable
fun PolyFormDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 一次性读全文：use{} 保证流关闭、UTF-8 显式指定；remember 避免重组重复 IO
    val licenseText = remember {
        context.resources.openRawResource(R.raw.license)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.polyform_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = licenseText)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_close))
            }
        },
    )
}
