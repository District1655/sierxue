package com.sierxue.english.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sierxue.english.domain.AppSettings
import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.TtsConfig
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class EndpointEditorState(
    val id: String,
    val name: String,
    val url: String,
    val model: String,
    val key: String = "",
    val hasKey: Boolean,
    val enabled: Boolean,
    val primary: Boolean,
    val rpm: Int,
    val voice: String = "alloy",
    val format: String = "mp3",
)

@Composable
fun ChatEndpointSettings(values: AppSettings, vm: SettingsViewModel) {
    EndpointListEditor(
        title = "AI 对话端点（1～5 个，按顺序切换）",
        initial = values.chatEndpoints.map {
            EndpointEditorState(it.id, it.name, it.baseUrl, it.model, hasKey = it.hasKey, enabled = it.enabled, primary = it.primary, rpm = it.requestsPerMinute)
        },
        maxCount = 5,
        isChat = true,
        onSave = { items, keys -> vm.saveChatEndpoints(items.map { it.toChat() }, keys) },
        onTest = { item -> vm.testChatEndpoint(item.toChat(), item.key) },
        onClearKey = { id -> vm.clearChatKey(id) },
    )
}

@Composable
fun TtsEndpointSettings(values: AppSettings, vm: SettingsViewModel) {
    EndpointListEditor(
        title = "在线 TTS 端点（1～3 个，独立通道）",
        initial = values.ttsEndpoints.map {
            EndpointEditorState(it.id, it.name, it.baseUrl, it.model, hasKey = it.hasKey, enabled = it.enabled, primary = it.primary, rpm = it.requestsPerMinute, voice = it.voice, format = it.format)
        },
        maxCount = 3,
        isChat = false,
        onSave = { items, keys -> vm.saveTtsEndpoints(items.map { it.toTts() }, keys) },
        onTest = { item -> vm.testTtsEndpoint(item.toTts(), item.key) },
        onClearKey = { id -> vm.clearTtsKey(id) },
    )
}

@Composable
private fun EndpointListEditor(
    title: String,
    initial: List<EndpointEditorState>,
    maxCount: Int,
    isChat: Boolean,
    onSave: (List<EndpointEditorState>, Map<String, String>) -> Unit,
    onTest: (EndpointEditorState) -> Unit,
    onClearKey: (String) -> Unit,
) {
    var items by remember { mutableStateOf(initial) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(initial) { if (!loaded) { items = initial; loaded = true } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("端点 ${index + 1}${if (item.primary) " · 主力" else ""}", fontWeight = FontWeight.Bold)
                        EditorField(item.name, "名称（可选）") { value -> items = items.update(index) { it.copy(name = value) } }
                        EditorField(item.url, "Base URL") { value -> items = items.update(index) { it.copy(url = value) } }
                        EditorField(item.model, "模型名") { value -> items = items.update(index) { it.copy(model = value) } }
                        OutlinedTextField(
                            value = item.key,
                            onValueChange = { value -> items = items.update(index) { it.copy(key = value) } },
                            label = { Text(if (item.hasKey) "已保存，输入以替换" else "API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = item.primary,
                                onClick = { items = items.mapIndexed { i, value -> value.copy(primary = i == index) } },
                            )
                            Text("主力", modifier = Modifier.weight(1f))
                            Text("启用")
                            Switch(checked = item.enabled, onCheckedChange = { checked -> items = items.update(index) { it.copy(enabled = checked) } })
                        }
                        Text("每分钟请求上限：${item.rpm} 次")
                        Slider(
                            value = item.rpm.toFloat(),
                            onValueChange = { value -> items = items.update(index) { it.copy(rpm = value.toInt().coerceIn(10, 60)) } },
                            valueRange = 10f..60f,
                            steps = 49,
                        )
                        if (!isChat) {
                            Text("音色")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(listOf("alloy", "echo", "nova", "onyx")) { voice ->
                                    FilterChip(selected = item.voice == voice, onClick = { items = items.update(index) { it.copy(voice = voice) } }, label = { Text(voice) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("mp3", "wav").forEach { format ->
                                    FilterChip(selected = item.format == format, onClick = { items = items.update(index) { it.copy(format = format) } }, label = { Text(format) })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { items = items.move(index, index - 1) }, enabled = index > 0) { Text("上移") }
                            TextButton(onClick = { items = items.move(index, index + 1) }, enabled = index < items.lastIndex) { Text("下移") }
                            TextButton(onClick = { onTest(item) }) { Text("测试") }
                            TextButton(onClick = {
                                onClearKey(item.id)
                                items = items.update(index) { it.copy(key = "", hasKey = false) }
                            }) { Text("清除 Key") }
                            TextButton(onClick = { items = items.filterIndexed { i, _ -> i != index } }, enabled = items.size > 1) { Text("删除") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(items, items.mapNotNull { if (it.key.isBlank()) null else it.id to it.key }.toMap()) }) { Text("保存端点") }
                OutlinedButton(
                    onClick = {
                        val prefix = if (isChat) "chat" else "tts"
                        items = items + EndpointEditorState(
                            id = "${prefix}_${System.currentTimeMillis()}",
                            name = "备用 ${items.size + 1}", url = "https://api.openai.com/v1",
                            model = if (isChat) "gpt-4o-mini" else "tts-1",
                            hasKey = false, enabled = false, primary = false, rpm = 20,
                        )
                    },
                    enabled = items.size < maxCount,
                ) { Text("添加端点") }
            }
        }
    }
}

@Composable
fun ChannelHealthSettings(values: AppSettings, vm: SettingsViewModel) {
    val container = LocalAppContainer.current
    val version by vm.healthStore.version.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("通道健康", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("版本 $version · 仅显示错误摘要，不显示 Key", style = MaterialTheme.typography.bodySmall)
            values.chatEndpoints.forEach { config ->
                val health = vm.healthStore.snapshot(config.id)
                val tokens = container.tokenBuckets.tokens(config.id, config.requestsPerMinute)
                Column {
                    Text("[对话] ${config.name.ifBlank { config.model }}：${health.status(config.enabled)}", fontWeight = FontWeight.Bold)
                    Text("最近成功：${formatTime(health.lastSuccessAt)} · 最近错误：${health.lastError.ifBlank { "无" }}")
                    Text("连续失败 ${health.consecutiveFailures} 次 · 最近 429：${formatTime(health.last429At)} · 桶余量 $tokens/${config.requestsPerMinute}")
                    Row {
                        TextButton(onClick = { vm.testChatEndpoint(config, "") }) { Text("测试") }
                        TextButton(onClick = { vm.resetEndpoint(config.id) }) { Text("恢复并重置") }
                    }
                }
            }
            values.ttsEndpoints.forEach { config ->
                val health = vm.healthStore.snapshot(config.id)
                val tokens = container.tokenBuckets.tokens(config.id, config.requestsPerMinute)
                Column {
                    Text("[TTS] ${config.name.ifBlank { config.model }}：${health.status(config.enabled)}", fontWeight = FontWeight.Bold)
                    Text("最近成功：${formatTime(health.lastSuccessAt)} · 最近错误：${health.lastError.ifBlank { "无" }}")
                    Text("连续失败 ${health.consecutiveFailures} 次 · 最近 429：${formatTime(health.last429At)} · 桶余量 $tokens/${config.requestsPerMinute}")
                    Row {
                        TextButton(onClick = { vm.testTtsEndpoint(config, "") }) { Text("测试") }
                        TextButton(onClick = { vm.resetEndpoint(config.id) }) { Text("恢复并重置") }
                    }
                }
            }
        }
    }
}

private fun List<EndpointEditorState>.update(index: Int, transform: (EndpointEditorState) -> EndpointEditorState): List<EndpointEditorState> =
    mapIndexed { i, item -> if (i == index) transform(item) else item }

private fun List<EndpointEditorState>.move(from: Int, to: Int): List<EndpointEditorState> {
    if (to !in indices) return this
    val result = toMutableList()
    val item = result.removeAt(from)
    result.add(to, item)
    return result
}

private fun EndpointEditorState.toChat() = ChatConfig(
    id = id, name = name, enabled = enabled, primary = primary,
    baseUrl = url.trim().trimEnd('/'), model = model.trim(), hasKey = hasKey, requestsPerMinute = rpm,
)

private fun EndpointEditorState.toTts() = TtsConfig(
    id = id, name = name, enabled = enabled, primary = primary,
    baseUrl = url.trim().trimEnd('/'), model = model.trim(), voice = voice,
    format = format, hasKey = hasKey, requestsPerMinute = rpm,
)

private fun formatTime(value: Long): String =
    if (value <= 0) "无" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))

@Composable
private fun EditorField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
