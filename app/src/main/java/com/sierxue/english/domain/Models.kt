package com.sierxue.english.domain

enum class QuestionType { SINGLE, MULTIPLE, FILL, TRUE_FALSE, ORDER, MATCHING }
enum class PracticeMode { PRACTICE, WRONG_REVIEW }
enum class LessonSectionType { PRONUNCIATION, PATTERN, EXPRESSIONS, VOCABULARY, READING }
enum class AssessmentScope { LESSON, UNIT_TEST, BOOK_TEST }
enum class ReviewType { WORD, PATTERN, SHADOWING }

data class AnswerOption(val id: String, val text: String)

data class Question(
    val id: String,
    val unitId: String,
    val sectionId: String,
    val assessmentScope: AssessmentScope,
    val passageId: String?,
    val type: QuestionType,
    val prompt: String,
    val options: List<AnswerOption>,
    val stems: List<String>,
    val answers: List<String>,
    val explanation: String,
    val difficulty: Int,
    val passageTitle: String? = null,
    val passageContent: String? = null,
)

data class Vocabulary(
    val id: String,
    val unitId: String,
    val sectionId: String,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val collocation: String,
    val rootTip: String,
    val example: String,
)

data class WrongItem(
    val question: Question,
    val lessonTitle: String,
    val sectionTitle: String,
    val wrongCount: Int,
    val reviewStage: Int,
    val nextReviewAt: Long,
)

data class ExamAnswerItem(
    val question: Question,
    val areaTitle: String,
    val userAnswers: List<String>,
    val isCorrect: Boolean,
)

data class AreaWeakness(val areaTitle: String, val correct: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else (correct * 100f / total).toInt()
}

data class AbilityScores(
    val pronunciation: Int = 0,
    val grammar: Int = 0,
    val vocabulary: Int = 0,
    val speaking: Int = 0,
    val reading: Int = 0,
)

data class ChatConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val hasKey: Boolean = false,
)

data class TtsConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "tts-1",
    val voice: String = "alloy",
    val format: String = "mp3",
    val hasKey: Boolean = false,
)

data class AppSettings(
    val bookId: String = "BOOK1",
    val lastUnitId: String = "B1L01",
    val speechRate: Float = 1.0f,
    val assistantSpeechRate: Float = 0.9f,
    val assistantEnabled: Boolean = true,
    val preferredTtsEngine: String = "",
    val chat: ChatConfig = ChatConfig(),
    val tts: TtsConfig = TtsConfig(),
)

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val systemPrompt: String,
    val contextJson: String,
    val history: List<ChatMessage>,
    val userMessage: String,
    val fallbackText: String,
)

interface ChatProvider { suspend fun respond(request: ChatRequest): String }

/** 仅保留未来本地模型扩展点，首版不进入 Provider 路由。 */
class LocalModelProvider : ChatProvider {
    override suspend fun respond(request: ChatRequest): String = "本地模型未启用"
}
