package com.sierxue.english.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sierxue.english.data.remote.AssistantChannelPhase
import com.sierxue.english.ui.viewmodel.AssistantViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantOverlay(vm: AssistantViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context by vm.context.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    if (!preferences.first) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val ballPx = with(density) { 56.dp.toPx() }
        val marginPx = with(density) { 8.dp.toPx() }
        var x by remember { mutableStateOf(Float.NaN) }
        var y by remember { mutableStateOf(Float.NaN) }
        LaunchedEffect(widthPx, heightPx) {
            if (x.isNaN()) x = widthPx - ballPx - marginPx * 2
            if (y.isNaN()) y = heightPx * 0.32f
            x = x.coerceIn(marginPx, (widthPx - ballPx - marginPx).coerceAtLeast(marginPx))
            y = y.coerceIn(marginPx, (heightPx - ballPx - marginPx).coerceAtLeast(marginPx))
        }
        if (!x.isNaN() && !y.isNaN()) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(56.dp)
                    .pointerInput(widthPx, heightPx) {
                        detectDragGestures(
                            onDragEnd = {
                                x = if (x + ballPx / 2 < widthPx / 2) marginPx else widthPx - ballPx - marginPx
                                y = y.coerceIn(marginPx, heightPx - ballPx - marginPx)
                            },
                        ) { change, drag ->
                            change.consume()
                            x = (x + drag.x).coerceIn(marginPx, widthPx - ballPx - marginPx)
                            y = (y + drag.y).coerceIn(marginPx, heightPx - ballPx - marginPx)
                        }
                    }
                    .clickable { vm.open() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("小思", color = Color.White, fontWeight = FontWeight.Bold)
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 2.dp, color = Color.White)
                    if (state.unread) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(10.dp).background(Color.Red, CircleShape))
                }
            }
        }
    }

    if (state.panelOpen) {
        ModalBottomSheet(onDismissRequest = vm::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            AssistantPanel(vm, context.label(), preferences.second)
        }
    }
}

@Composable
private fun AssistantPanel(vm: AssistantViewModel, contextLabel: String, speechRate: Float) {
    val state by vm.state.collectAsStateWithLifecycle()
    val monitor by vm.channelMonitor.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val androidContext = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) input = text else voiceError = "没有识别到内容，可直接输入文字"
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val phaseColor = when (monitor.phase) {
                AssistantChannelPhase.MAIN_HEALTHY -> Color(0xFF2E9E5B)
                AssistantChannelPhase.RETRYING, AssistantChannelPhase.FALLBACK_ACTIVE -> Color(0xFFE0A100)
                AssistantChannelPhase.OFFLINE -> Color.Red
            }
            Box(Modifier.padding(end = 8.dp).size(10.dp).background(phaseColor, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("学习助手 · 小思", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(contextLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.showHistory(!state.showHistory) }) { Text("历史") }
            TextButton(onClick = { vm.newConversation() }) { Text("新对话") }
            TextButton(onClick = { vm.hide() }) { Text("隐藏") }
        }
        monitor.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(onClick = { vm.send("解释当前知识点") }) { Text("解释本段") }
            TextButton(onClick = { vm.send("出 3 道同类题") }) { Text("出 3 道同类题") }
            TextButton(onClick = { vm.send("讲讲这道错题") }) { Text("讲错题") }
            TextButton(onClick = { vm.send("翻译并朗读", autoSpeak = true) }) { Text("翻译朗读") }
        }
        if (state.showHistory) {
            LazyColumn(Modifier.height(150.dp)) {
                items(state.conversations, key = { it.id }) { conversation ->
                    TextButton(onClick = { vm.selectConversation(conversation.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${conversation.title} · ${conversation.updatedAt}")
                    }
                }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.messages.isEmpty()) item {
                Text("可以问“讲讲这个词”“为什么选 C”或“出三道同类题”。未配置 AI 时会使用本课本地讲解。", modifier = Modifier.padding(vertical = 16.dp))
            }
            items(state.messages, key = { it.id }) { message ->
                val isUser = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                    Card(modifier = Modifier.fillMaxWidth(0.88f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (isUser) "我" else "小思", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(message.content)
                            if (!isUser) SpeakerButton("assistant_message_${message.id}", message.content, speechRate, "朗读回复")
                        }
                    }
                }
            }
            state.streamingText?.let { partial ->
                item { Card(Modifier.fillMaxWidth()) { Text(partial, modifier = Modifier.padding(12.dp)) } }
            }
            if (state.loading) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("  小思正在思考…", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.cancelGeneration() }) { Text("取消") }
                }
            }
        }
        state.status?.let {
            Text(it, color = if (state.canRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            if (state.canRetry) TextButton(onClick = { vm.retry() }) { Text("重试") }
        }
        if (state.canContinue) TextButton(onClick = { vm.continueAnswer() }) { Text("继续") }
        voiceError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), label = { Text("问小思") }, maxLines = 3)
            TextButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的问题")
                }
                try { voiceLauncher.launch(intent) } catch (_: ActivityNotFoundException) { voiceError = "系统语音输入不可用，请使用文字" }
            }) { Text("语音") }
            Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !state.loading) { Text("发送") }
        }
        Row {
            TextButton(onClick = { vm.clear() }) { Text("清空记录") }
            TextButton(onClick = { vm.close() }) { Text("返回学习") }
        }
    }
}
