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

/**
 * 校准对准视图：全屏遮罩 + 底部面板（人脸状态 + 提示 + 确认/取消）。
 *
 * 纯 UI 组件，零业务逻辑：
 *  - [visible] 由 MainActivity 的 calibrationActive 状态驱动（enter/close 校验与
 *    faceRenderer.setCalibrationMode 的 @Volatile 写入都在 Activity 侧）；
 *  - [facePresent] 来自渲染器 onFacePresenceChanged 回调（已 post 到主线程写入 Compose state）：
 *    null = 尚未收到首帧回调（显示「正在检测人脸…」过渡文案）；
 *  - 确认/取消全部上收 MainActivity（快照捕获/持久化/收尾）。
 *
 * 主题：面板色取 colorScheme.background 半透明。GL 网格/十字仍由 GlSurfaceHost 的
 * GLSurfaceView 在其下渲染（GlSurfaceHost 带 zIndex(-1f)，面板在其上）。
 */
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
