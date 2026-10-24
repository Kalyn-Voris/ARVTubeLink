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
 * 完整条款弹窗：从 res/raw/agreement.txt 读中文协议全文展示（本地 App 不跳浏览器）。
 *
 * back 行为：默认 DialogProperties（可 back）——back 关闭本弹窗，露出下层的
 * AgreementDialog（各弹窗均为独立窗口，后组合者在上）。
 */
@Composable
fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 一次性读全文：use{} 保证流关闭、UTF-8 显式指定；remember 避免重组重复 IO
    val licenseText = remember {
        context.resources.openRawResource(R.raw.agreement)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.license_title)) },
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
