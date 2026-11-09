package com.sierxue.english.speech

import kotlin.math.roundToInt

data class SpeakingScore(
    val score: Int,
    val recognizedText: String,
    val issues: List<String>,
    val wordsPerMinute: Int,
)

object SpeakingEvaluator {
    fun evaluate(standard: String, recognized: String, durationMillis: Long): SpeakingScore {
        val expected = words(standard)
        val actual = words(recognized)
        if (actual.isEmpty()) return SpeakingScore(0, recognized, listOf("未识别到有效英语语音"), 0)

        val n = expected.size
        val m = actual.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (expected[i - 1] == actual[j - 1]) dp[i - 1][j - 1]
                else minOf(dp[i - 1][j - 1] + 1, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
        }
        val missing = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        val extra = mutableListOf<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && expected[i - 1] == actual[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> { i--; j-- }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    wrong += "${expected[i - 1]} → ${actual[j - 1]}"; i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> { missing += expected[i - 1]; i-- }
                else -> { extra += actual[j - 1]; j-- }
            }
        }
        val issues = mutableListOf<String>()
        if (missing.isNotEmpty()) issues += "漏词：${missing.asReversed().joinToString("、")}"
        if (wrong.isNotEmpty()) issues += "错词：${wrong.asReversed().joinToString("、")}"
        if (extra.isNotEmpty()) issues += "多读：${extra.asReversed().joinToString("、")}"
        if (expected.toSet() == actual.toSet() && expected != actual) issues += "词序需要调整"
        if (issues.isEmpty()) issues += "单词与顺序识别良好"

        val safeDuration = durationMillis.coerceAtLeast(1_000)
        val wpm = (actual.size * 60_000f / safeDuration).roundToInt()
        val speedFactor = when {
            wpm in 80..170 -> 1f
            wpm < 80 -> (wpm / 80f).coerceIn(0f, 1f)
            else -> (1f - (wpm - 170) / 100f).coerceIn(0f, 1f)
        }
        if (speedFactor < 0.7f) issues += if (wpm < 80) "语速偏慢：$wpm 词/分钟" else "语速偏快：$wpm 词/分钟"
        val accuracy = (1f - dp[n][m].toFloat() / expected.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val score = (accuracy * 85 + speedFactor * 15).roundToInt().coerceIn(0, 100)
        return SpeakingScore(score, recognized, issues, wpm)
    }

    private fun words(value: String): List<String> =
        value.lowercase().replace(Regex("[^a-z0-9' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}
