package com.sierxue.english.data.local

import android.util.Log
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.ChatProvider
import com.sierxue.english.domain.ChatRequest
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class LocalModelProvider(
    private val settings: SettingsRepository,
    private val study: StudyRepository,
    private val engine: LlamaCppEngine,
) : ChatProvider {
    override suspend fun respond(request: ChatRequest): String {
        val config = settings.settings.first()
        val context = curriculumContext(request.contextJson)
        require(context.isNotBlank()) { "本课没有相关内容" }
        val userMessage = request.userMessage.take(1_000)
        val systemPrompt = systemPrompt(request.systemPrompt, userMessage)
        val result = engine.generate(
            modelId = config.localModelId,
            systemPrompt = systemPrompt,
            contextJson = context,
            history = request.history.takeLast(4),
            userMessage = userMessage,
            thinking = config.localThinkingEnabled,
            maxTokens = 1024,
        )
        Log.i(
            "LocalModel",
            "prompt=${result.promptTokens}, generated=${result.generatedTokens}, stop=${result.stopReason}, loadMs=${result.modelLoadMs}",
        )
        val answer = visibleAnswer(result.output, result.thinkingEnabled)
        require(answer.isNotBlank()) { "本地模型未生成正文，请关闭深度思考后重试" }
        return when (result.stopReason) {
            "max_tokens" -> "$answer\n\n（本地回答达到 1024 token 安全上限）"
            "context_full" -> "$answer\n\n（本地回答达到 4096 token 上下文上限）"
            else -> answer
        }
    }

    private suspend fun curriculumContext(raw: String): String {
        val context = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val unitId = context.optString("unitId")
        val bundle = if (unitId.isNotBlank()) study.lessonBundle(unitId) else null
        val questionId = context.optString("questionId")
        if (questionId.isNotBlank() && context.optString("question").isBlank()) {
            study.question(questionId)?.let { question ->
                context.put("question", question.prompt)
                context.put("options", JSONArray(question.options.map { "${it.id}. ${it.text}" }))
                context.put("standardAnswer", question.answers.joinToString("/"))
                context.put("localExplanation", question.explanation)
            }
        }
        val hasCurrentContent = listOf("sectionContent", "word", "wordDetail", "example", "question")
            .any { context.optString(it).isNotBlank() }
        if (bundle == null && !hasCurrentContent) return ""
        val current = prioritized(context).toString().take(1_200)
        val lesson = bundle?.let { lessonSummary(it).take(700) }.orEmpty()
        val combined = buildString {
            append("当前教材与题目：").append(current)
            if (lesson.isNotBlank()) append("\n本课教材摘要：").append(lesson)
        }
        if (combined.length > 1_900 || raw.length > current.length) {
            Log.i("LocalModel", "教材上下文已裁剪：原始 ${raw.length} 字符，注入 ${combined.length} 字符")
        }
        return combined
    }

    private fun prioritized(context: JSONObject): JSONObject {
        val result = JSONObject()
        listOf(
            "book", "unit", "section", "question", "options", "selectedAnswer", "standardAnswer",
            "localExplanation", "word", "wordDetail", "example", "sectionContent", "page",
        ).forEach { key -> if (context.has(key)) result.put(key, context.get(key)) }
        return result
    }

    private fun lessonSummary(bundle: com.sierxue.english.data.repository.LessonBundle): String = buildString {
        bundle.book?.let { append("分册：").append(it.title).append('；') }
        append("课：").append(bundle.unit.title).append('\n')
        bundle.sections.forEach { section ->
            append(section.title).append('：').append(section.contentJson.take(90)).append('\n')
        }
        if (bundle.vocabulary.isNotEmpty()) {
            append("词汇：")
            bundle.vocabulary.take(8).forEach {
                append(it.word).append(' ').append(it.meaning).append('；')
                append(it.collocation).append('；').append(it.example.take(80)).append('\n')
            }
        }
        bundle.passage?.let { append("阅读：").append(it.content.take(300)) }
    }

    private fun systemPrompt(base: String, userMessage: String): String {
        val task = when {
            userMessage.contains("同类") || (userMessage.contains("出") && userMessage.contains("题")) ->
                "任务类型：出题。只根据本课词汇、句型或阅读出 3 道单选题；每题给 A～D，最后集中给答案与一句解析，不引入课外事实。"
            userMessage.contains("造句") || userMessage.contains("句子") ->
                "任务类型：造句。只使用本课词汇和句型造 5 句；每句依次给英文、中文意思、所用搭配，不添加课外知识。"
            userMessage.contains("讲") || userMessage.contains("解释") || userMessage.contains("为什么") || userMessage.contains("错") ->
                "任务类型：讲解。依次说明正确答案、用户所选答案、考点、易错点，并引用一个本课例句；不得编造题目之外的事实。"
            else -> "仅回答能由所给课程内容直接支持的问题；与课程无关或资料不足时只回复“本课没有相关内容”。"
        }
        return "你是思而学 App 内的英语助教小思，当前运行本地小模型。只基于提供的课程内容作答；不确定时说明无法回答，不要编造。禁止自由事实问答，禁止声称查阅了外部资料。$task 默认用简洁中文，英文例句必须自然。$base".take(900)
    }

    private fun visibleAnswer(raw: String, thinking: Boolean): String {
        val markers = listOf("</think>", "<｜end▁of▁thinking｜>", "<|end▁of▁thinking|>", "<|end_of_thinking|>")
        val end = markers.mapNotNull { marker -> raw.indexOf(marker).takeIf { it >= 0 }?.let { it to marker } }
            .minByOrNull { it.first }
        if (end != null) {
            val reasoning = raw.substring(0, end.first).removePrefix("<think>").trim()
            val content = raw.substring(end.first + end.second.length).cleanSpecialTokens()
            return if (thinking && reasoning.isNotBlank()) "思考过程\n$reasoning\n\n正文\n$content" else content
        }
        if (raw.contains("<think>")) {
            val reasoning = raw.substringAfter("<think>").cleanSpecialTokens()
            return if (thinking) "思考过程\n$reasoning\n\n正文\n" else ""
        }
        return raw.cleanSpecialTokens()
    }

    private fun String.cleanSpecialTokens(): String =
        replace("<|im_start|>assistant", "").replace("<|im_end|>", "").replace("<|endoftext|>", "").trim()
}
