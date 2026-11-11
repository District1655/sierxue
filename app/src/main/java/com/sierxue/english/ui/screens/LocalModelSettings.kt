package com.sierxue.english.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sierxue.english.data.local.ModelAssetStatus
import com.sierxue.english.data.local.ModelTransferState
import com.sierxue.english.domain.AiChannel
import com.sierxue.english.domain.AppSettings
import com.sierxue.english.ui.viewmodel.SettingsViewModel

@Composable
fun LocalModelSettings(values: AppSettings, vm: SettingsViewModel) {
    val models by vm.models.collectAsStateWithLifecycle()
    val loadedModelId by vm.loadedModelId.collectAsStateWithLifecycle()
    val usage by vm.modelUsageBytes.collectAsStateWithLifecycle()
    var importId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importId
        if (uri != null && id != null) vm.importModel(id, uri)
    }

    SettingsGroup("AI 通道与本地模型") {
        Text("AI 通道")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(
                AiChannel.CLOUD_FIRST to "云端优先（失败回落本地）",
                AiChannel.CLOUD_ONLY to "仅云端",
                AiChannel.LOCAL_ONLY to "仅本地",
            )) { (channel, label) ->
                FilterChip(selected = values.aiChannel == channel, onClick = { vm.setAiChannel(channel) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本地深度思考（开启后更慢）", modifier = Modifier.weight(1f))
            Switch(checked = values.localThinkingEnabled, onCheckedChange = vm::setLocalThinking)
        }
        Text("仅 arm64-v8a / CPU。加载前需至少 1.5 GB 可用内存；进入后台立即取消生成。", style = MaterialTheme.typography.bodySmall)
        Text("本地小模型只基于当前教材作答；没有相关内容时直接提示，不承担自由事实问答。", style = MaterialTheme.typography.bodySmall)
        Text("模型总占用：${formatModelBytes(usage)}")
        models.values.forEach { status ->
            ModelCard(
                status = status,
                selected = values.localModelId == status.variant.id,
                loaded = loadedModelId == status.variant.id,
                onSelect = { vm.selectLocalModel(status.variant.id) },
                onDownload = { vm.downloadModel(status.variant.id) },
                onImport = {
                    importId = status.variant.id
                    importLauncher.launch(arrayOf("application/octet-stream"))
                },
                onCancel = { vm.cancelModelTransfer(status.variant.id) },
                onDelete = { vm.deleteModel(status.variant.id) },
            )
        }
        Text(
            "应用内下载使用独立 GitHub models-v1 Release，支持 HTTP Range 断点续传；国内直连可用性因网络而异，失败时请选择本地 GGUF 导入。模型不打包进 APK。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModelCard(
    status: ModelAssetStatus,
    selected: Boolean,
    loaded: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val variant = status.variant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${variant.displayName} · ${variant.quantization}", style = MaterialTheme.typography.titleSmall)
        Text(variant.fileName, style = MaterialTheme.typography.bodySmall)
        Text("登记大小：${formatModelBytes(variant.expectedBytes)}；本机文件：${formatModelBytes(status.installedBytes)}")
        Text("SHA256：" + if (status.verified) "校验通过" else "未通过或尚未校验")
        if (selected) Text("当前选定模型", color = MaterialTheme.colorScheme.primary)
        if (loaded) Text("当前已加载", color = MaterialTheme.colorScheme.primary)
        val transferring = status.transferState == ModelTransferState.DOWNLOADING || status.transferState == ModelTransferState.IMPORTING
        if (transferring) {
            val progress = (status.transferredBytes.toFloat() / variant.expectedBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${formatModelBytes(status.transferredBytes)} / ${formatModelBytes(variant.expectedBytes)}")
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (transferring) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownload) { Text(if (status.installed) "重新下载" else "下载") }
                OutlinedButton(onClick = onImport) { Text("本地导入") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.verified) OutlinedButton(onClick = onSelect, enabled = !selected) { Text(if (selected) "已选定" else "设为当前") }
                if (status.installed) OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun formatModelBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    else -> "%.0f KiB".format(bytes / 1024.0)
}
