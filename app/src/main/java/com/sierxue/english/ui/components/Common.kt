package com.sierxue.english.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sierxue.english.R
import com.sierxue.english.di.AppContainer
import com.sierxue.english.domain.AbilityScores
import com.sierxue.english.domain.Question
import com.sierxue.english.domain.QuestionType
import com.sierxue.english.speech.PlaybackStatus
import kotlin.math.cos
import kotlin.math.sin

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer 未提供") }

@Composable
fun PageTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Surface(shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("返回") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
fun SpeakerButton(id: String, text: String, rate: Float? = null, contentDescription: String = "朗读") {
    val container = LocalAppContainer.current
    val state by container.speech.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = null)
    val active = state.id == id && state.status != PlaybackStatus.IDLE
    DisposableEffect(id) { onDispose { container.speech.stop(id) } }
    IconButton(onClick = { container.speech.toggle(id, text, rate ?: settings?.speechRate ?: 1.0f) }) {
        if (active && state.status == PlaybackStatus.PREPARING) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(if (active) R.drawable.ic_stop else R.drawable.ic_volume_up),
                contentDescription = if (active) "停止朗读" else contentDescription,
            )
        }
    }
}

@Composable
fun SpeechStatus() {
    val speech = LocalAppContainer.current.speech
    val state by speech.state.collectAsState()
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LessonProgress(completed: Int, total: Int = 5) {
    Column {
        LinearProgressIndicator(progress = { (completed / total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("五段已完成 $completed / $total", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun QuestionContent(question: Question) {
    if (question.passageContent != null) {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(question.passageTitle ?: "阅读材料", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SpeakerButton("passage_${question.id}", question.passageContent)
                }
                Text(question.passageContent, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(question.prompt, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        SpeakerButton("question_${question.id}", question.prompt)
    }
    if (question.stems.isNotEmpty()) {
        Text("左侧顺序：${question.stems.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("；")}", modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun QuestionAnswerInput(
    question: Question,
    value: List<String>,
    onChange: (List<String>) -> Unit,
    enabled: Boolean = true,
) {
    when (question.type) {
        QuestionType.SINGLE, QuestionType.TRUE_FALSE -> {
            question.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = value == listOf(option.id), onClick = { if (enabled) onChange(listOf(option.id)) }, enabled = enabled)
                    Text("${option.id}. ${option.text}")
                }
            }
        }
        QuestionType.MULTIPLE -> {
            question.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = option.id in value,
                        onCheckedChange = { checked ->
                            if (enabled) onChange(if (checked) value + option.id else value - option.id)
                        },
                        enabled = enabled,
                    )
                    Text("${option.id}. ${option.text}")
                }
            }
        }
        QuestionType.FILL -> {
            OutlinedTextField(
                value = value.firstOrNull().orEmpty(),
                onValueChange = { if (enabled) onChange(listOf(it)) },
                enabled = enabled,
                label = { Text("填写答案") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        QuestionType.ORDER, QuestionType.MATCHING -> {
            Text(if (question.type == QuestionType.ORDER) "按正确语序依次点击" else "按左侧顺序依次点击对应项", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            question.options.forEach { option ->
                Button(
                    onClick = { if (enabled && option.id !in value) onChange(value + option.id) },
                    enabled = enabled && option.id !in value,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text("${option.id}. ${option.text}") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选：${value.joinToString(" → ").ifBlank { "无" }}", modifier = Modifier.weight(1f))
                TextButton(onClick = { if (value.isNotEmpty()) onChange(value.dropLast(1)) }, enabled = enabled && value.isNotEmpty()) { Text("撤销") }
            }
        }
    }
}

@Composable
fun AbilityRadar(scores: AbilityScores, modifier: Modifier = Modifier) {
    val values = listOf(scores.pronunciation, scores.grammar, scores.vocabulary, scores.speaking, scores.reading)
    val labels = listOf("发音", "句型", "词汇", "口语", "阅读")
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(250.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.29f
        fun point(index: Int, fraction: Float): Offset {
            val angle = Math.toRadians((-90 + index * 72).toDouble())
            return Offset(center.x + (radius * fraction * cos(angle)).toFloat(), center.y + (radius * fraction * sin(angle)).toFloat())
        }
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { fraction ->
            val path = Path()
            repeat(5) { index -> val p = point(index, fraction); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            path.close()
            drawPath(path, grid, style = Stroke(width = 1f))
        }
        repeat(5) { drawLine(grid, center, point(it, 1f), strokeWidth = 1f) }
        val dataPath = Path()
        values.forEachIndexed { index, value ->
            val p = point(index, (value / 100f).coerceIn(0.02f, 1f))
            if (index == 0) dataPath.moveTo(p.x, p.y) else dataPath.lineTo(p.x, p.y)
            drawCircle(color, radius = 4f, center = p)
        }
        dataPath.close()
        drawPath(dataPath, color.copy(alpha = 0.22f))
        drawPath(dataPath, color, style = Stroke(width = 3f))
        val paint = android.graphics.Paint().apply {
            this.color = textColor.toArgb()
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        labels.forEachIndexed { index, label ->
            val p = point(index, 1.32f)
            drawContext.canvas.nativeCanvas.drawText("$label ${values[index]}", p.x, p.y + 10f, paint)
        }
    }
}
