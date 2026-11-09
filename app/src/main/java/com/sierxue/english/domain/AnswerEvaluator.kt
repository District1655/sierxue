package com.sierxue.english.domain

import org.json.JSONArray

object AnswerEvaluator {
    fun isCorrect(question: Question, userAnswers: List<String>): Boolean {
        if (userAnswers.isEmpty()) return false
        return when (question.type) {
            QuestionType.SINGLE, QuestionType.TRUE_FALSE ->
                userAnswers.singleOrNull() == question.answers.singleOrNull()
            QuestionType.MULTIPLE ->
                userAnswers.toSet() == question.answers.toSet() && userAnswers.size == question.answers.size
            QuestionType.FILL -> {
                val actual = normalize(userAnswers.firstOrNull().orEmpty())
                actual.isNotEmpty() && question.answers.any { normalize(it) == actual }
            }
            QuestionType.ORDER, QuestionType.MATCHING -> userAnswers == question.answers
        }
    }

    fun encode(answers: List<String>): String = JSONArray(answers).toString()

    fun decode(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun readableAnswers(question: Question, answers: List<String>): String {
        if (answers.isEmpty()) return "未作答"
        if (question.type == QuestionType.FILL) return answers.first()
        return answers.mapNotNull { id -> question.options.firstOrNull { it.id == id }?.text ?: id }
            .joinToString(if (question.type == QuestionType.ORDER || question.type == QuestionType.MATCHING) " → " else "、")
    }
}
