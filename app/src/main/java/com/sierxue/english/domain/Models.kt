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
    val id: String = "chat_default",
    val name: String = "主力模型",
    val enabled: Boolean = false,
    val primary: Boolean = true,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val hasKey: Boolean = false,
    val requestsPerMinute: Int = 20,
)

data class TtsConfig(
    val id: String = "tts_default",
    val name: String = "主力语音",
    val enabled: Boolean = false,
    val primary: Boolean = true,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "tts-1",
    val voice: String = "alloy",
    val format: String = "mp3",
    val hasKey: Boolean = false,
    val requestsPerMinute: Int = 20,
)

data class AppSettings(
    val bookId: String = "BOOK1",
    val lastUnitId: String = "B1L01",
    val speechRate: Float = 1.0f,
    val assistantSpeechRate: Float = 0.9f,
    val assistantEnabled: Boolean = true,
    val preferredTtsEngine: String = "",
    val ttsServiceQueryCount: Int = -1,
    val chat: ChatConfig = ChatConfig(),
    val tts: TtsConfig = TtsConfig(),
    val chatEndpoints: List<ChatConfig> = listOf(ChatConfig()),
    val ttsEndpoints: List<TtsConfig> = listOf(TtsConfig()),
    val aiChannel: AiChannel = AiChannel.CLOUD_FIRST,
    val localModelId: String = "qwen3_q8_0",
    val localThinkingEnabled: Boolean = false,
)

enum class AiChannel { CLOUD_FIRST, CLOUD_ONLY, LOCAL_ONLY }

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val systemPrompt: String,
    val contextJson: String,
    val history: List<ChatMessage>,
    val userMessage: String,
    val fallbackText: String,
)

interface ChatProvider { suspend fun respond(request: ChatRequest): String }
