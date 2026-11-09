package com.sierxue.english.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sierxue.english.assistant.AssistantContext
import com.sierxue.english.di.viewModelFactoryOf
import com.sierxue.english.domain.AnswerEvaluator
import com.sierxue.english.domain.AssessmentScope
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.components.PageTopBar
import com.sierxue.english.ui.components.QuestionAnswerInput
import com.sierxue.english.ui.components.QuestionContent
import com.sierxue.english.ui.components.SpeakerButton
import com.sierxue.english.ui.viewmodel.CatalogViewModel
import com.sierxue.english.ui.viewmodel.ExamViewModel
import com.sierxue.english.ui.viewmodel.PracticeViewModel
import com.sierxue.english.ui.viewmodel.ReportViewModel
import com.sierxue.english.ui.viewmodel.ReviewViewModel
import com.sierxue.english.ui.viewmodel.StatsViewModel
import com.sierxue.english.ui.viewmodel.WrongBookViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

@Composable
fun PracticeScreen(
    unitId: String? = null,
    sectionId: String? = null,
    wrongQuestionId: String? = null,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: PracticeViewModel = viewModel(factory = viewModelFactoryOf {
        PracticeViewModel(container.study, container.settings, container.chatClient, unitId, sectionId, wrongQuestionId)
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val question = state.questions.getOrNull(state.index)
    val selected = question?.let { state.answers[it.id].orEmpty() }.orEmpty()
    val submitted = question != null && question.id in state.submitted

    LaunchedEffect(question?.id, selected) {
        if (question != null) container.assistantContext.update(
            AssistantContext(
                page = if (wrongQuestionId != null) "错题重做" else "练习", unitId = question.unitId,
                questionId = question.id, questionIndex = state.index + 1, questionPrompt = question.prompt,
                options = question.options.map { "${it.id}. ${it.text}" }, selectedAnswer = selected,
                standardAnswer = question.answers.joinToString("/"), localExplanation = question.explanation,
            ),
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(if (wrongQuestionId != null) "错题重做" else "练习与解析", onBack)
        when {
            state.loading -> Text("正在加载题目…", modifier = Modifier.padding(20.dp))
            state.questions.isEmpty() -> Text("本范围暂无课内练习，测评题会在对应阶段测评中出现。", modifier = Modifier.padding(20.dp))
            state.finished -> {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("练习完成", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("答对 ${state.correctCount} / ${state.questions.size}，正确率 ${state.correctCount * 100 / state.questions.size}%", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onBack) { Text("返回课程") }
                }
            }
            question != null -> {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("第 ${state.index + 1} / ${state.questions.size} 题", color = MaterialTheme.colorScheme.primary) }
                    item { QuestionContent(question) }
                    item { QuestionAnswerInput(question, selected, vm::select, enabled = !submitted) }
                    if (!submitted) {
                        item { Button(onClick = { vm.submit() }, enabled = selected.isNotEmpty() && selected.firstOrNull()?.isNotBlank() == true, modifier = Modifier.fillMaxWidth()) { Text("提交答案") } }
                    } else {
                        val correct = state.correct[question.id] == true
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (correct) "回答正确" else "回答错误", color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    Text("你的答案：${AnswerEvaluator.readableAnswers(question, selected)}")
                                    Text("标准答案：${AnswerEvaluator.readableAnswers(question, question.answers)}")
                                    Text("解析：${question.explanation}")
                                    Button(onClick = { vm.explainWithAi() }, enabled = !state.aiLoading) { Text(if (state.aiLoading) "AI 正在解析…" else "AI 深度解析") }
                                    state.aiText?.let { Text(it) }
                                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        item { Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) { Text(if (state.index == state.questions.lastIndex) "查看练习结果" else "下一题") } }
                    }
                }
            }
        }
    }
}

@Composable
fun WrongBookScreen(onBack: () -> Unit, onQuestion: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: WrongBookViewModel = viewModel(factory = container.viewModelFactory)
    val items by vm.items.collectAsStateWithLifecycle()
    var lesson by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf<String?>(null) }
    val lessonOptions = items.map { it.lessonTitle }.distinct()
    val sectionOptions = items.filter { lesson == null || it.lessonTitle == lesson }.map { it.sectionTitle }.distinct()
    val filtered = items.filter { (lesson == null || it.lessonTitle == lesson) && (section == null || it.sectionTitle == section) }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("错题本", onBack)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            item { FilterChip(selected = lesson == null, onClick = { lesson = null; section = null }, label = { Text("全部课") }) }
            items(lessonOptions) { FilterChip(selected = lesson == it, onClick = { lesson = it; section = null }, label = { Text(it) }) }
        }
        if (lesson != null) LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            item { FilterChip(selected = section == null, onClick = { section = null }, label = { Text("全部段") }) }
            items(sectionOptions) { FilterChip(selected = section == it, onClick = { section = it }, label = { Text(it) }) }
        }
        if (filtered.isEmpty()) {
            Text(if (items.isEmpty()) "暂无错题。去课内练习后，答错题目会自动出现在这里。" else "当前筛选下没有错题。", modifier = Modifier.padding(20.dp))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.question.id }) { wrong ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(wrong.lessonTitle, color = MaterialTheme.colorScheme.primary)
                        Text(wrong.sectionTitle, style = MaterialTheme.typography.bodySmall)
                        Text(wrong.question.prompt, fontWeight = FontWeight.Bold)
                        Text("已错 ${wrong.wrongCount} 次 · 复习阶段 ${wrong.reviewStage + 1}/4 · 下次 ${SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(wrong.nextReviewAt))}")
                        Row {
                            Button(onClick = { onQuestion(wrong.question.id) }) { Text("单题重做") }
                            TextButton(onClick = { vm.remove(wrong.question.id) }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: ReviewViewModel = viewModel(factory = container.viewModelFactory)
    val items by vm.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull()
    val json = item?.let { runCatching { JSONObject(it.content) }.getOrNull() }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("今日复习", onBack)
        if (item == null) {
            Column(Modifier.padding(24.dp)) {
                Text("今日复习已完成", style = MaterialTheme.typography.headlineSmall)
                Text("新的复习会在完成课程后按 1/3/7/15 天到期。")
                Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("返回继续学习") }
            }
        } else if (json != null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("剩余 ${items.size} 项 · ${item.type}", color = MaterialTheme.colorScheme.primary)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Row {
                            Text(json.optString("text"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            SpeakerButton("review_${item.id}", json.optString("speak", json.optString("text")))
                        }
                        Text(json.optString("detail"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("先回忆，再选择真实掌握情况。选择遗忘会回到 1 天后复习。")
                Button(onClick = { vm.answer(item, true) }, modifier = Modifier.fillMaxWidth()) { Text("记得，推进复习") }
                OutlinedButton(onClick = { vm.answer(item, false) }, modifier = Modifier.fillMaxWidth()) { Text("遗忘，1 天后再复习") }
            }
        }
    }
}

@Composable
fun ExamSetupScreen(onBack: () -> Unit, onStart: (String) -> Unit) {
    val container = LocalAppContainer.current
    val catalog: CatalogViewModel = viewModel(factory = container.viewModelFactory)
    val vm: ExamViewModel = viewModel(factory = container.viewModelFactory)
    val course by catalog.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdExamId) { state.createdExamId?.let(onStart) }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("阶段测评", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(course.book?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Text("每 4 课一次单元测；每册末一次综合测。题目不会超出所选教材范围。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (course.book?.outlineOnly == true) {
                Text("本分册目前为大纲，完整题目将在阶段 B 开放。")
            } else {
                Button(onClick = { vm.create(course.currentBookId, course.units.take(4).map { it.id }, AssessmentScope.UNIT_TEST) }, enabled = course.units.size >= 4, modifier = Modifier.fillMaxWidth()) { Text("第 1 次单元测（第 1～4 课）") }
                Button(onClick = { vm.create(course.currentBookId, course.units.drop(4).take(4).map { it.id }, AssessmentScope.UNIT_TEST) }, enabled = course.units.size >= 8, modifier = Modifier.fillMaxWidth()) { Text("第 2 次单元测（第 5～8 课）") }
                OutlinedButton(onClick = { vm.create(course.currentBookId, course.units.map { it.id }, AssessmentScope.BOOK_TEST) }, enabled = course.units.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("册末综合测（含长阅读）") }
                Text("综合测的口语部分可在第 8 课读写段完成，成绩会计入五维口语能力。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ExamScreen(examId: String, onBack: () -> Unit, onReport: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: ExamViewModel = viewModel(factory = container.viewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var showCard by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val question = state.questions.getOrNull(state.index)
    LaunchedEffect(examId) { vm.load(examId) }
    LaunchedEffect(state.submittedExamId) { state.submittedExamId?.let(onReport) }
    LaunchedEffect(question?.id, state.answers[question?.id]) {
        if (question != null) container.assistantContext.update(AssistantContext(page = "测评", unitId = question.unitId, questionId = question.id, questionIndex = state.index + 1, questionPrompt = question.prompt, options = question.options.map { it.text }, selectedAnswer = state.answers[question.id].orEmpty()))
    }
    if (showCard) {
        AlertDialog(
            onDismissRequest = { showCard = false }, title = { Text("答题卡") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(5), modifier = Modifier.height(180.dp)) {
                    itemsIndexed(state.questions) { index, item ->
                        val answered = !state.answers[item.id].isNullOrEmpty()
                        TextButton(onClick = { vm.jump(index); showCard = false }) { Text("${index + 1}${if (answered) "✓" else ""}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCard = false }) { Text("关闭") } },
        )
    }
    if (confirm) {
        val unanswered = state.questions.count { state.answers[it.id].isNullOrEmpty() }
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("确认交卷？") },
            text = { Text(if (unanswered > 0) "还有 $unanswered 题未答，未答题将按错误计分。" else "已完成全部题目，交卷后显示成绩与解析。") },
            confirmButton = { Button(onClick = { confirm = false; vm.submit() }) { Text("交卷") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续作答") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("测评 ${state.remainingSeconds / 60}:${(state.remainingSeconds % 60).toString().padStart(2, '0')}", onBack, actions = { TextButton(onClick = { showCard = true }) { Text("答题卡") } })
        if (question == null) {
            Text("正在组卷…", modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("第 ${state.index + 1} / ${state.questions.size} 题 · 已答 ${state.answers.count { it.value.isNotEmpty() }}") }
                item { QuestionContent(question) }
                item { QuestionAnswerInput(question, state.answers[question.id].orEmpty(), vm::select) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.jump(state.index - 1) }, enabled = state.index > 0, modifier = Modifier.weight(1f)) { Text("上一题") }
                        Button(onClick = { vm.jump(state.index + 1) }, enabled = state.index < state.questions.lastIndex, modifier = Modifier.weight(1f)) { Text("下一题") }
                    }
                }
                item { Button(onClick = { confirm = true }, enabled = !state.submitting, modifier = Modifier.fillMaxWidth()) { Text("交卷") } }
            }
        }
    }
}

@Composable
fun ReportScreen(examId: String, onBackHome: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: ReportViewModel = viewModel(factory = viewModelFactoryOf { ReportViewModel(container.study, examId) })
    val report by vm.report.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        PageTopBar("成绩报告", onBackHome)
        val value = report
        if (value == null) Text("正在生成报告…", modifier = Modifier.padding(20.dp))
        else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${value.exam.score} 分", style = MaterialTheme.typography.displayMedium, color = if (value.exam.score >= 60) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("${if (value.exam.score >= 60) "及格" else "未及格"} · 答对 ${value.exam.correctCount}/${value.exam.totalCount}")
            }
            item { Text("薄弱课段", style = MaterialTheme.typography.titleLarge) }
            items(value.weaknesses) { Text("${it.areaTitle}：${it.correct}/${it.total}（${it.percent}%）") }
            item { Text("错题解析", style = MaterialTheme.typography.titleLarge) }
            items(value.answers.filter { !it.isCorrect }) { answer ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(answer.areaTitle, color = MaterialTheme.colorScheme.primary)
                        Text(answer.question.prompt, fontWeight = FontWeight.Bold)
                        Text("你的答案：${AnswerEvaluator.readableAnswers(answer.question, answer.userAnswers)}")
                        Text("标准答案：${AnswerEvaluator.readableAnswers(answer.question, answer.question.answers)}")
                        Text(answer.question.explanation)
                    }
                }
            }
            item { Button(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) { Text("返回首页") } }
        }
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, onReport: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: StatsViewModel = viewModel(factory = container.viewModelFactory)
    val exams by vm.exams.collectAsStateWithLifecycle()
    val submitted = exams.filter { it.status == "SUBMITTED" }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("成绩统计", onBack)
        Column(Modifier.padding(16.dp)) {
            Text("测评 ${submitted.size} 次 · 平均 ${if (submitted.isEmpty()) 0 else submitted.map { it.score }.average().toInt()} 分 · 最高 ${submitted.maxOfOrNull { it.score } ?: 0} 分", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(submitted) { exam ->
                Card(onClick = { onReport(exam.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${exam.score} 分 · ${exam.scope}", fontWeight = FontWeight.Bold)
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(exam.submittedAt ?: exam.startedAt)))
                    }
                }
            }
        }
    }
}
