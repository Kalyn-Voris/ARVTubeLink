package com.exarry.arvtubelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R

/** 底部半透明面板叠在 GlSurfaceHost 的 GL 网格之上；facePresent 为 null 表示首帧回调尚未到达 */
@Composable
fun CalibrationOverlay(
    visible: Boolean,
    facePresent: Boolean?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f))
                .padding(16.dp)
        ) {
            Text(
                text = when (facePresent) {
                    null -> stringResource(R.string.calib_detecting)
                    true -> stringResource(R.string.calib_status_detected)
                    false -> stringResource(R.string.calib_status_not_detected)
                },
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Text(
                text = stringResource(R.string.calib_hint),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_confirm_calibration))
            }
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
