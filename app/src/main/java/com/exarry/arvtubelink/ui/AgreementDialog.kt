package com.exarry.arvtubelink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.exarry.arvtubelink.R

/**
 * 开屏协议弹窗（首次启动阻塞）：不同意/同意之外无任何关闭途径。
 *
 * back 行为：dismissOnBackPress = false 拦截返回键——back 不关弹窗、App 不退出；
 * dismissOnClickOutside = false 拦截点外部，onDismissRequest 因此恒为空实现。
 *
 * 正文末行两个入口：「查看完整条款」经 [onViewFullLicense] 打开 [LicenseDialog]，
 * 「PolyForm Noncommercial 1.0.0条款」经 [onViewPolyForm] 打开 [PolyFormDialog]
 * （均为独立 Dialog 窗口，后组合者在上，back 逐层回退）。
 */
@Composable
fun AgreementDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onViewFullLicense: () -> Unit,
    onViewPolyForm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* 拦截：唯一出口是同意/不同意按钮 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = stringResource(R.string.agreement_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column {
                Text(text = stringResource(R.string.agreement_summary))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.agreement_view_full),
                        color = MaterialTheme.colorScheme.primary,
                        // weight(fill=false)：宽屏两端对齐，窄屏不占满宽度防右链接被挤出换行
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable(onClick = onViewFullLicense),
                    )
                    Text(
                        text = stringResource(R.string.view_polyform),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable(onClick = onViewPolyForm),
                    )
                }
            }
        },
        confirmButton = {
            // 同意 = 实心 Button（醒目）；不同意 = 默认 TextButton
            Button(onClick = onAgree) {
                Text(text = stringResource(R.string.action_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(text = stringResource(R.string.action_disagree))
            }
        },
    )
}
