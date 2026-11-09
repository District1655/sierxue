package com.sierxue.english.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sierxue.english.speech.SystemTtsStatus
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.components.PageTopBar
import com.sierxue.english.ui.viewmodel.CatalogViewModel
import com.sierxue.english.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(factory = container.viewModelFactory)
    val catalog: CatalogViewModel = viewModel(factory = container.viewModelFactory)
    val values by vm.values.collectAsStateWithLifecycle()
    val course by catalog.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val systemStatus by container.systemTts.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clearData by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    var chatEnabled by remember { mutableStateOf(false) }
    var chatUrl by remember { mutableStateOf("") }
    var chatModel by remember { mutableStateOf("") }
    var chatKey by remember { mutableStateOf("") }
    var ttsEnabled by remember { mutableStateOf(false) }
    var ttsUrl by remember { mutableStateOf("") }
    var ttsModel by remember { mutableStateOf("") }
    var ttsVoice by remember { mutableStateOf("") }
    var ttsFormat by remember { mutableStateOf("mp3") }
    var ttsKey by remember { mutableStateOf("") }

    LaunchedEffect(values) {
        if (!initialized) {
            chatEnabled = values.chat.enabled; chatUrl = values.chat.baseUrl; chatModel = values.chat.model
            ttsEnabled = values.tts.enabled; ttsUrl = values.tts.baseUrl; ttsModel = values.tts.model
            ttsVoice = values.tts.voice; ttsFormat = values.tts.format
            initialized = true
        }
    }

    if (clearData) {
        AlertDialog(
            onDismissRequest = { clearData = false }, title = { Text("清除全部学习数据？") },
            text = { Text("将删除课段进度、练习、错题、复习、跟读、测评、小思和音频缓存。教材与接口设置保留，此操作不可撤销。") },
            confirmButton = { Button(onClick = { vm.clearData(); clearData = false }) { Text("确认清除") } },
            dismissButton = { TextButton(onClick = { clearData = false }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar("设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsGroup("课程与发音") {
                Text("当前分册：${course.book?.title.orEmpty()}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(course.books) { book ->
                        FilterChip(selected = book.id == values.bookId, onClick = { vm.setBook(book.id) }, label = { Text(book.title) })
                    }
                }
                Text("系统语速 ${"%.1f".format(values.speechRate)}")
                Slider(value = values.speechRate, onValueChange = vm::setSpeechRate, valueRange = 0.7f..1.3f, steps = 5)
                Text("系统 TTS：" + when (systemStatus) {
                    SystemTtsStatus.INITIALIZING -> "正在初始化"
                    SystemTtsStatus.READY -> "英语发音可用"
                    SystemTtsStatus.MISSING_DATA -> "缺少英语语音数据"
                    SystemTtsStatus.UNSUPPORTED -> "不支持英语"
                    SystemTtsStatus.FAILED -> "初始化失败"
                })
                OutlinedButton(onClick = {
                    try { context.startActivity(container.systemTts.installDataIntent()) }
                    catch (_: ActivityNotFoundException) { }
                }) { Text("安装/检查英语语音数据") }
            }

            SettingsGroup("小思") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示全局悬浮球", modifier = Modifier.weight(1f))
                    Switch(checked = values.assistantEnabled, onCheckedChange = vm::setAssistantEnabled)
                }
                Text("小思语速 ${"%.1f".format(values.assistantSpeechRate)}")
                Slider(value = values.assistantSpeechRate, onValueChange = vm::setAssistantRate, valueRange = 0.7f..1.3f, steps = 5)
                OutlinedButton(onClick = { vm.clearConversations() }) { Text("清除对话记录") }
            }

            SettingsGroup("对话出题接口") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用对话 AI", modifier = Modifier.weight(1f)); Switch(checked = chatEnabled, onCheckedChange = { chatEnabled = it })
                }
                SecretField(chatKey, { chatKey = it }, if (values.chat.hasKey) "已保存，输入以替换" else "API Key（仅本机加密保存）")
                ConfigField(chatUrl, { chatUrl = it }, "BaseURL")
                ConfigField(chatModel, { chatModel = it }, "模型名")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveChat(chatEnabled, chatUrl, chatModel, chatKey) }) { Text("保存") }
                    OutlinedButton(onClick = { vm.testChat(chatEnabled, chatUrl, chatModel, chatKey) }) { Text("测试连接") }
                    TextButton(onClick = { vm.clearChatKey(); chatKey = "" }) { Text("清除 Key") }
                }
            }

            SettingsGroup("在线 TTS 接口") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("优先使用在线 TTS", modifier = Modifier.weight(1f)); Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                }
                SecretField(ttsKey, { ttsKey = it }, if (values.tts.hasKey) "已保存，输入以替换" else "独立 TTS API Key")
                ConfigField(ttsUrl, { ttsUrl = it }, "BaseURL")
                ConfigField(ttsModel, { ttsModel = it }, "TTS 模型名")
                Text("音色")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("alloy", "echo", "nova", "onyx")) { voice ->
                        FilterChip(selected = ttsVoice == voice, onClick = { ttsVoice = voice }, label = { Text(voice) })
                    }
                }
                Text("返回格式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "wav").forEach { format -> FilterChip(selected = ttsFormat == format, onClick = { ttsFormat = format }, label = { Text(format) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveTts(ttsEnabled, ttsUrl, ttsModel, ttsVoice, ttsFormat, ttsKey) }) { Text("保存") }
                    OutlinedButton(onClick = { vm.testTts(ttsEnabled, ttsUrl, ttsModel, ttsVoice, ttsFormat, ttsKey) }) { Text("测试连接") }
                    TextButton(onClick = { vm.clearTtsKey(); ttsKey = "" }) { Text("清除 Key") }
                }
                Text("在线失败自动回落系统 TTS；相同文本和参数使用本地缓存。", style = MaterialTheme.typography.bodySmall)
            }

            SettingsGroup("数据与关于") {
                OutlinedButton(onClick = { clearData = true }) { Text("清除全部学习数据") }
                Text("思而学 SiErXue · 1.0.0", fontWeight = FontWeight.Bold)
                Text("包名 com.sierxue.english。教材驱动、离线优先。当前本地对话模型与 sherpa-onnx 仅保留扩展接口，设置中不提供未实现开关。")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ConfigField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
}
