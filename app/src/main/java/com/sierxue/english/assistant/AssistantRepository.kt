package com.sierxue.english.assistant

import com.sierxue.english.data.local.AppDatabase
import com.sierxue.english.data.local.AssistantConversationEntity
import com.sierxue.english.data.local.AssistantMessageEntity
import com.sierxue.english.data.remote.ChatProviderRouter
import com.sierxue.english.data.remote.ProviderReply
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.ChatMessage
import com.sierxue.english.domain.ChatRequest
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class AssistantRepository(
    private val db: AppDatabase,
    private val study: StudyRepository,
    private val router: ChatProviderRouter,
) {
    private val dao = db.studyDao()

    fun observeConversations(): Flow<List<AssistantConversationEntity>> = dao.observeConversations()
    fun observeMessages(id: String) = dao.observeMessages(id)

    suspend fun ensureConversation(): String {
        dao.latestConversation()?.let { return it.id }
        return newConversation()
    }

    suspend fun newConversation(): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertConversation(AssistantConversationEntity(id, "新对话", now, now))
        return id
    }

    suspend fun clear() {
        dao.clearMessages()
        dao.clearConversations()
    }

    suspend fun send(conversationId: String, context: AssistantContext, text: String): ProviderReply {
        val previous = dao.messages(conversationId).map { ChatMessage(it.role, it.content) }
        val now = System.currentTimeMillis()
        dao.insertMessage(AssistantMessageEntity(conversationId = conversationId, role = "user", content = text, contextJson = context.toJson(), createdAt = now, isError = false))
        val request = ChatRequest(
            systemPrompt = "你是思而学 App 内的英语助教小思。默认用简洁中文讲解，必要时给音标和短例句。优先使用结构化上下文，不编造页面信息。讲题要解释考点与干扰项；出同类题时给三道有确定答案的题并集中列答案；控制篇幅，不索取隐私。",
            contextJson = context.toJson(),
            history = previous,
            userMessage = text,
            fallbackText = localFallback(context, text),
        )
        val reply = router.respond(request)
        dao.insertMessage(
            AssistantMessageEntity(
                conversationId = conversationId, role = "assistant", content = reply.text,
                contextJson = context.toJson(), createdAt = System.currentTimeMillis(),
                isError = reply.errorMessage != null,
            ),
        )
        dao.touchConversation(conversationId, System.currentTimeMillis())
        return reply
    }

    private suspend fun localFallback(context: AssistantContext, text: String): String {
        if (text.contains("同类")) {
            val questions = study.similarQuestions(context.unitId, context.questionId.ifBlank { null })
            if (questions.isNotEmpty()) {
                return buildString {
                    append("这是本课内置的同类练习：\n")
                    questions.forEachIndexed { index, question ->
                        append("${index + 1}. ${question.prompt}\n")
                        if (question.options.isNotEmpty()) append(question.options.joinToString("；") { "${it.id}. ${it.text}" } + "\n")
                    }
                    append("答案：")
                    append(questions.joinToString("；") { it.answers.joinToString("/") })
                }
            }
        }
        if (context.word.isNotBlank()) {
            return buildString {
                append(context.word).append("\n").append(context.wordDetail)
                if (context.example.isNotBlank()) append("\n例句：").append(context.example)
                if (text.contains("翻译")) append("\n以上释义来自本课词汇卡，可直接点击朗读。")
            }
        }
        if (context.localExplanation.isNotBlank()) {
            return "本地解析：${context.localExplanation}\n\n配置 AI 后可获得更深入的干扰项分析。"
        }
        if (context.sectionContent.isNotBlank()) {
            return "本段学习要点：${context.sectionContent.take(700)}\n\n配置 AI 后可继续追问。"
        }
        return "我可以结合当前课段、单词、例句和题目进行讲解。请打开一课或一道题后再问；配置 AI 后可获得自由对话。"
    }
}
