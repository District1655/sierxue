package com.sierxue.english.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sierxue.english.update.UpdateController

@Composable
fun UpdateDialog(controller: UpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val release = state.release
    if (!state.dialogVisible || release == null) return
    AlertDialog(
        onDismissRequest = { if (!state.downloading) controller.dismiss() },
        title = { Text(if (state.downloading) "正在下载 ${release.tagName}" else "发现新版本 ${release.tagName}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(if (release.body.isBlank()) "本次发布未填写更新说明。" else release.body)
                if (release.downloadSize > 0) Text("安装包大小：${formatBytes(release.downloadSize)}")
                if (state.downloading) {
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("已下载 ${formatBytes(state.downloadedBytes)}")
                    }
                }
                state.message?.let { Text(it) }
            }
        },
        confirmButton = {
            if (state.downloading) {
                TextButton(onClick = { controller.cancelDownload() }) { Text("取消下载") }
            } else {
                Button(onClick = { controller.startDownload() }) { Text("立即更新") }
            }
        },
        dismissButton = {
            if (!state.downloading) TextButton(onClick = { controller.dismiss() }) { Text("稍后") }
        },
    )
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / 1024.0 / 1024.0)
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}
