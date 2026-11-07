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

/** 读 res/raw/agreement.txt 全文展示，默认 DialogProperties 允许 back 关闭 */
@Composable
fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // remember 缓存正文，避免重组重复 IO
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
