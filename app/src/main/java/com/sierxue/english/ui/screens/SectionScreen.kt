package com.sierxue.english.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sierxue.english.assistant.AssistantContext
import com.sierxue.english.di.viewModelFactoryOf
import com.sierxue.english.domain.LessonSectionType
import com.sierxue.english.domain.Vocabulary
import com.sierxue.english.speech.SpeakingEvaluator
import com.sierxue.english.speech.SpeakingRecognizer
import com.sierxue.english.speech.SpeakingScore
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.components.PageTopBar
import com.sierxue.english.ui.components.SpeakerButton
import com.sierxue.english.ui.components.SpeechStatus
import com.sierxue.english.ui.viewmodel.SectionViewModel
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SectionScreen(sectionId: String, onBack: () -> Unit, onPractice: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SectionViewModel = viewModel(factory = viewModelFactoryOf { SectionViewModel(container.study, container.settings, sectionId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val section = state.section
    val bundle = state.bundle

    LaunchedEffect(sectionId, bundle?.unit?.id) {
        if (section != null && bundle != null) {
            container.assistantContext.update(
                AssistantContext(
                    page = "课段学习", bookTitle = bundle.book?.title.orEmpty(), unitId = bundle.unit.id,
                    unitTitle = bundle.unit.title, sectionTitle = section.title,
                    sectionContent = section.contentJson.take(1_200),
                ),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(section?.title ?: "课段学习", onBack)
        if (section == null || bundle == null) {
            Text(if (state.loading) "正在加载…" else "课段不存在", modifier = Modifier.padding(20.dp))
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("第 ${bundle.unit.number} 课 · ${bundle.unit.title}", color = MaterialTheme.colorScheme.primary)
            val content = JSONObject(section.contentJson)
            when (LessonSectionType.valueOf(section.type)) {
                LessonSectionType.PRONUNCIATION -> PronunciationContent(content, sectionId, bundle.unit.id, vm)
                LessonSectionType.PATTERN -> PatternContent(content)
                LessonSectionType.EXPRESSIONS -> ExpressionsContent(content)
                LessonSectionType.VOCABULARY -> VocabularyContent(bundle.vocabulary, bundle.unit.title, section.title)
                LessonSectionType.READING -> ReadingContent(content, bundle.passage?.content.orEmpty(), bundle.passage?.mainIdea.orEmpty(), bundle.passage?.title.orEmpty(), sectionId, bundle.unit.id, vm)
            }
            SpeechStatus()
            if (section.type != LessonSectionType.PRONUNCIATION.name) {
                Button(onClick = { vm.complete() }, modifier = Modifier.fillMaxWidth()) { Text("完成本段") }
            }
            OutlinedButton(onClick = { vm.complete(skipped = true) }, modifier = Modifier.fillMaxWidth()) { Text("跳过本段（稍后可回来复习）") }
            OutlinedButton(onClick = { onPractice(sectionId) }, modifier = Modifier.fillMaxWidth()) { Text("本段练习") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun PronunciationContent(content: JSONObject, sectionId: String, unitId: String, vm: SectionViewModel) {
    Text(content.optString("phoneme"), style = MaterialTheme.typography.bodyLarge)
    Text("最小对立对", fontWeight = FontWeight.Bold)
    val pairs = content.optJSONArray("minimalPairs") ?: JSONArray()
    for (index in 0 until pairs.length()) {
        val pair = pairs.getJSONObject(index)
        val text = "${pair.getString("left")} / ${pair.getString("right")}"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            SpeakerButton("pair_${sectionId}_$index", "${pair.getString("left")}. ${pair.getString("right")}.")
        }
    }
    Text("重音与节奏：${content.optString("rhythm")}")
    Text("跟读训练", fontWeight = FontWeight.Bold)
    val sentences = content.optJSONArray("shadowSentences") ?: JSONArray()
    for (index in 0 until sentences.length()) {
        val sentence = sentences.getString(index)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sentence, modifier = Modifier.weight(1f))
                    SpeakerButton("shadow_${sectionId}_$index", sentence)
                }
                SpeakingPractice(standard = sentence, onSave = { result, duration ->
                    vm.recordSpeaking(sentence, result.recognizedText, result.score, result.issues, duration, result.wordsPerMinute)
                })
            }
        }
    }
}

@Composable
private fun PatternContent(content: JSONObject) {
    Text("核心句型", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(content.optString("pattern"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SpeakerButton("pattern_${content.optString("pattern").hashCode()}", content.optString("pattern"))
    }
    Text("变体", fontWeight = FontWeight.Bold)
    val variations = content.optJSONArray("variations") ?: JSONArray()
    for (index in 0 until variations.length()) {
        val sentence = variations.getString(index)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sentence, modifier = Modifier.weight(1f))
            SpeakerButton("variation_$index${sentence.hashCode()}", sentence)
        }
    }
    var showAnswers by remember { mutableStateOf(false) }
    Text("替换造句", fontWeight = FontWeight.Bold)
    val substitutions = content.optJSONArray("substitutions") ?: JSONArray()
    for (index in 0 until substitutions.length()) {
        val item = substitutions.getJSONObject(index)
        Text(item.getString("prompt"))
        if (showAnswers) Text("参考答案：${item.getString("answer")}", color = MaterialTheme.colorScheme.primary)
    }
    TextButton(onClick = { showAnswers = !showAnswers }) { Text(if (showAnswers) "隐藏参考答案" else "查看参考答案") }
}

@Composable
private fun ExpressionsContent(content: JSONObject) {
    Text("场景：${content.optString("scenario")}", style = MaterialTheme.typography.titleMedium)
    val items = content.optJSONArray("items") ?: JSONArray()
    for (index in 0 until items.length()) {
        val item = items.getJSONObject(index)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.getString("phrase"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SpeakerButton("expression_${item.getString("phrase").hashCode()}", item.getString("phrase"))
                }
                Text(item.getString("meaning"))
            }
        }
    }
}

@Composable
private fun VocabularyContent(words: List<Vocabulary>, unitTitle: String, sectionTitle: String) {
    val holder = LocalAppContainer.current.assistantContext
    words.forEach { word ->
        Card(onClick = {
            holder.update(AssistantContext(
                page = "词汇学习", unitId = word.unitId, unitTitle = unitTitle, sectionTitle = sectionTitle,
                word = word.word, wordDetail = "${word.phonetic} ${word.meaning}\n搭配：${word.collocation}\n构词：${word.rootTip}",
                example = word.example,
            ))
        }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${word.word}  ${word.phonetic}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(word.meaning, color = MaterialTheme.colorScheme.primary)
                    }
                    SpeakerButton("word_${word.id}", word.word)
                }
                Text("搭配：${word.collocation}")
                Text("构词提示：${word.rootTip}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.example, modifier = Modifier.weight(1f))
                    SpeakerButton("example_${word.id}", word.example)
                }
            }
        }
    }
}

@Composable
private fun ReadingContent(
    content: JSONObject,
    passage: String,
    mainIdea: String,
    title: String,
    sectionId: String,
    unitId: String,
    vm: SectionViewModel,
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("精读短文", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        SpeakerButton("reading_$sectionId", passage)
    }
    Text(passage, style = MaterialTheme.typography.bodyLarge)
    Text("主旨：$mainIdea", color = MaterialTheme.colorScheme.secondary)
    Text("拆句", fontWeight = FontWeight.Bold)
    val sentences = content.optJSONArray("sentenceBreakdown") ?: JSONArray()
    for (index in 0 until sentences.length()) Text("• ${sentences.getString(index)}")
    Text("口语任务：${content.optString("speakingTask")}", fontWeight = FontWeight.Bold)
    val sample = content.optString("sampleAnswer")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("参考：$sample", modifier = Modifier.weight(1f))
        SpeakerButton("reading_sample_$sectionId", sample)
    }
    SpeakingPractice(standard = sample, onSave = { result, duration ->
        vm.recordSpeaking(sample, result.recognizedText, result.score, result.issues, duration, result.wordsPerMinute)
    })
}

@Composable
private fun SpeakingPractice(standard: String, onSave: (SpeakingScore, Long) -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SpeakingScore?>(null) }
    val recognizer = remember(standard) {
        SpeakingRecognizer(
            context,
            onResult = { text, duration ->
                recording = false
                val evaluated = SpeakingEvaluator.evaluate(standard, text, duration)
                result = evaluated
                onSave(evaluated, duration)
            },
            onError = { recording = false; error = it },
        )
    }
    DisposableEffect(standard) { onDispose { recognizer.destroy() } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            error = null
            recording = true
            recognizer.start()
        } else {
            error = "未获得录音权限。其他学习功能不受影响，可以跳过跟读。"
        }
    }

    Column {
        Button(onClick = {
            if (recording) {
                recognizer.stop()
                recording = false
            } else if (!recognizer.isAvailable()) {
                error = "设备没有可用的语音识别引擎，可跳过跟读"
            } else {
                result = null
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    error = null
                    recording = true
                    recognizer.start()
                } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (recording) "结束录音并评分" else "开始跟读")
        }
        if (recording) Text("正在录音，请朗读标准句；再次点击结束。", color = MaterialTheme.colorScheme.primary)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let {
            Text("跟读得分：${it.score} / 100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("识别：${it.recognizedText.ifBlank { "无" }}")
            Text("语速：${it.wordsPerMinute} 词/分钟")
            it.issues.forEach { issue -> Text(issue) }
        }
    }
}
