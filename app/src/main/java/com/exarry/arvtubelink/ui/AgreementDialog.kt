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

@Composable
fun AgreementDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onViewFullLicense: () -> Unit,
    onViewPolyForm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {  },
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
                        // weight(fill = false)：宽屏两端对齐，窄屏按内容宽
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
