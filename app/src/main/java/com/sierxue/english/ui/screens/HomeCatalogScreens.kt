package com.sierxue.english.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.sierxue.english.di.AppViewModelFactory
import com.sierxue.english.ui.components.AbilityRadar
import com.sierxue.english.ui.components.LessonProgress
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.components.PageTopBar
import com.sierxue.english.ui.viewmodel.CatalogViewModel
import com.sierxue.english.ui.viewmodel.HomeViewModel
import com.sierxue.english.ui.viewmodel.LessonViewModel
import com.sierxue.english.di.viewModelFactoryOf

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel(factory = container.viewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var reviewDismissed by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    val unit = state.continueUnit

    LaunchedEffect(state.book?.id, unit?.id) {
        container.assistantContext.update(
            AssistantContext(page = "首页", bookTitle = state.book?.title.orEmpty(), unitId = unit?.id.orEmpty(), unitTitle = unit?.title.orEmpty()),
        )
    }

    if (state.dueTotal > 0 && !reviewDismissed) {
        AlertDialog(
            onDismissRequest = { reviewDismissed = true },
            title = { Text("今日复习") },
            text = { Text("本课 ${state.dueWords} 个词 + ${state.duePatterns} 个句型，另有跟读句待复习。先复习再进入新课，记忆更连贯。") },
            confirmButton = { Button(onClick = { onNavigate("review") }) { Text("开始复习") } },
            dismissButton = { TextButton(onClick = { reviewDismissed = true }) { Text("稍后") } },
        )
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清除小思对话？") },
            text = { Text("只删除本地会话，不影响课程和成绩。") },
            confirmButton = { Button(onClick = { vm.clearConversations(); clearDialog = false }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar("思而学", actions = { TextButton(onClick = { onNavigate("settings") }) { Text("设置") } })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.book?.title ?: "课程加载中", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(unit?.let { "继续第 ${state.book?.code?.lastOrNull() ?: "1"} 册第 ${it.number} 课" } ?: "暂无课程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(unit?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text("${unit?.durationMinutes ?: 0} 分钟 · ${unit?.description.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        LessonProgress(state.completedSections)
                        Button(onClick = { unit?.let { if (state.dueTotal > 0) onNavigate("review") else onNavigate("lesson/${it.id}") } }, enabled = unit != null, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.completedSections > 0) "继续本课" else "开始本课")
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("今日复习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (state.dueTotal == 0) "暂无到期复习，完成一课后自动生成" else "${state.dueWords} 个词 + ${state.duePatterns} 个句型 · 共 ${state.dueTotal} 项")
                        if (state.dueTotal > 0) TextButton(onClick = { onNavigate("review") }) { Text("进入复习") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("五维能力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        AbilityRadar(state.ability)
                        val dimensions = listOf("发音" to state.ability.pronunciation, "语法/句型" to state.ability.grammar, "词汇" to state.ability.vocabulary, "口语" to state.ability.speaking, "阅读" to state.ability.reading)
                        val weakest = dimensions.minByOrNull { it.second }
                        Text("下一步建议：优先完成本课的${weakest?.first}环节，用真实练习积累分数。", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate("catalog") }, modifier = Modifier.weight(1f)) { Text("课程目录") }
                    OutlinedButton(onClick = { onNavigate("wrong") }, modifier = Modifier.weight(1f)) { Text("错题 ${state.wrongCount}") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onNavigate("exam_setup") }, modifier = Modifier.weight(1f)) { Text("阶段测评") }
                    OutlinedButton(onClick = { onNavigate("stats") }, modifier = Modifier.weight(1f)) { Text(state.latestScore?.let { "最近 $it 分" } ?: "成绩统计") }
                }
            }
            item { TextButton(onClick = { clearDialog = true }) { Text("清除小思对话记录") } }
        }
    }
}

@Composable
fun CatalogScreen(onBack: () -> Unit, onLesson: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: CatalogViewModel = viewModel(factory = container.viewModelFactory)
    val state by vm.state.collectAsStateWithLifecycle()
    var lockedLesson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.book?.id) {
        container.assistantContext.update(AssistantContext(page = "课程目录", bookTitle = state.book?.title.orEmpty()))
    }
    lockedLesson?.let { id ->
        AlertDialog(
            onDismissRequest = { lockedLesson = null },
            title = { Text("本课尚未解锁") },
            text = { Text("按顺序学习可保持内容连贯。你也可以查看本课并手动跳课。") },
            confirmButton = { Button(onClick = { onLesson(id); lockedLesson = null }) { Text("手动跳课") } },
            dismissButton = { TextButton(onClick = { lockedLesson = null }) { Text("返回") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageTopBar("课程目录", onBack)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.books) { book ->
                val selected = book.id == state.currentBookId
                if (selected) Button(onClick = {}) { Text(book.title) }
                else OutlinedButton(onClick = { vm.selectBook(book.id) }) { Text(book.title) }
            }
        }
        Text(state.book?.description.orEmpty(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.units) { lesson ->
                val completed = state.completedByUnit[lesson.id] ?: 0
                val previousDone = state.units.firstOrNull { it.number == lesson.number - 1 }?.let { (state.completedByUnit[it.id] ?: 0) >= 5 } ?: true
                val locked = state.book?.outlineOnly == false && lesson.number > 1 && !previousDone
                Card(onClick = { if (locked) lockedLesson = lesson.id else onLesson(lesson.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text("第 ${lesson.number} 课 · ${lesson.title}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (state.book?.outlineOnly == true) "大纲" else if (completed >= 5) "已完成" else if (locked) "未解锁" else "$completed/5")
                        }
                        Text(lesson.description, style = MaterialTheme.typography.bodyMedium)
                        Text("预计 ${lesson.durationMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun LessonScreen(unitId: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: LessonViewModel = viewModel(factory = viewModelFactoryOf { LessonViewModel(container.study, unitId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val bundle = state.bundle
    LaunchedEffect(bundle?.unit?.id) {
        bundle?.let {
            container.assistantContext.update(AssistantContext(page = "课程详情", bookTitle = it.book?.title.orEmpty(), unitId = it.unit.id, unitTitle = it.unit.title))
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(bundle?.unit?.let { "第 ${it.number} 课 ${it.title}" } ?: "课程详情", onBack)
        if (bundle == null) {
            Text(if (state.loading) "正在加载课程…" else "课程不存在", modifier = Modifier.padding(20.dp))
            return@Column
        }
        if (bundle.book?.outlineOnly == true) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(bundle.unit.description, style = MaterialTheme.typography.titleMedium)
                Text("本分册当前提供完整课程目录与学习目标，五段正文将在阶段 B 扩充。你仍可使用小思询问英语学习问题。")
                Button(onClick = { onNavigate("catalog") }) { Text("查看其他分册") }
            }
            return@Column
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(bundle.unit.description); LessonProgress(state.progress.count { it.completed }) }
            items(bundle.sections) { section ->
                val progress = state.progress.firstOrNull { it.sectionId == section.id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Text("${section.sortOrder + 1}. ${section.title}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (progress?.completed == true) if (progress.skipped) "已跳过" else "已完成" else "待学习")
                        }
                        if (progress?.score != null) Text("最近跟读 ${progress.score} 分", color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { onNavigate("section/${section.id}") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(if (progress?.completed == true) "复习本段" else "开始/继续")
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { onNavigate("practice/$unitId") }, modifier = Modifier.fillMaxWidth()) { Text("本课练习与解析") } }
        }
    }
}
