package com.exarry.arvtubelink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exarry.arvtubelink.R
import com.exarry.arvtubelink.utils.InAppLog

/** InAppLog.version 每次追加 +1，驱动重组并按 version 缓存 getAll() */
@Composable
fun LogContent() {
    val context = LocalContext.current
    val version by InAppLog.version.collectAsState()
    val logs = remember(version) { InAppLog.getAll() }
    val listState = rememberLazyListState()

    LaunchedEffect(version) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.log_count_format, logs.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                copyAllLogs(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_copied_logs, InAppLog.getAll().size),
                    Toast.LENGTH_SHORT
                ).show()
            }) { Text(stringResource(R.string.log_copy_all)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { InAppLog.clear() }) { Text(stringResource(R.string.log_clear)) }
        }
    }
}

private fun copyAllLogs(context: Context) {
    val text = InAppLog.getAll().joinToString("\n")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("arvtubelink-logs", text))
}
