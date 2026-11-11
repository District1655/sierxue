package com.sierxue.english.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AssistantContext(
    val page: String = "首页",
    val bookTitle: String = "",
    val unitId: String = "",
    val unitTitle: String = "",
    val sectionTitle: String = "",
    val sectionContent: String = "",
    val word: String = "",
    val wordDetail: String = "",
    val example: String = "",
    val questionId: String = "",
    val questionIndex: Int = 0,
    val questionPrompt: String = "",
    val options: List<String> = emptyList(),
    val selectedAnswer: List<String> = emptyList(),
    val standardAnswer: String = "",
    val localExplanation: String = "",
    val wrongCount: Int = 0,
) {
    fun label(): String = when {
        word.isNotBlank() -> "正在看：$word"
        questionPrompt.isNotBlank() -> "正在做：第 ${questionIndex.coerceAtLeast(1)} 题"
        sectionTitle.isNotBlank() -> "正在学：$sectionTitle"
        unitTitle.isNotBlank() -> "当前课程：$unitTitle"
        else -> page
    }

    fun toJson(): String = JSONObject().apply {
        put("page", page)
        putOptional("book", bookTitle)
        putOptional("unitId", unitId)
        putOptional("unit", unitTitle)
        putOptional("section", sectionTitle)
        if (sectionContent.isNotBlank()) put("sectionContent", sectionContent.take(1_200))
        putOptional("word", word)
        putOptional("wordDetail", wordDetail)
        putOptional("example", example)
        if (questionPrompt.isNotBlank()) {
            put("questionIndex", questionIndex)
            put("question", questionPrompt)
            put("options", JSONArray(options))
            put("selectedAnswer", JSONArray(selectedAnswer))
            putOptional("standardAnswer", standardAnswer)
            putOptional("localExplanation", localExplanation)
            if (wrongCount > 0) put("wrongCount", wrongCount)
        }
    }.toString()

    private fun JSONObject.putOptional(key: String, value: String) {
        if (value.isNotBlank()) put(key, value)
    }
}

class AssistantContextHolder {
    private val _context = MutableStateFlow(AssistantContext())
    val context: StateFlow<AssistantContext> = _context
    fun update(value: AssistantContext) { _context.value = value }
}
