package com.sierxue.english.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakingEvaluatorTest {
    @Test
    fun exactSentenceGetsFullScore() {
        val result = SpeakingEvaluator.evaluate("Nice to meet you.", "Nice to meet you.", 2_000)
        assertEquals(100, result.score)
        assertTrue(result.issues.any { it.contains("良好") })
    }

    @Test
    fun missingWordIsReported() {
        val result = SpeakingEvaluator.evaluate("My name is Amy.", "My name Amy.", 2_000)
        assertTrue(result.issues.any { it.contains("漏词") && it.contains("is") })
        assertTrue(result.score < 100)
    }

    @Test
    fun wordOrderAndEmptySpeechAreHandled() {
        val reordered = SpeakingEvaluator.evaluate("I am happy.", "Am I happy.", 2_000)
        assertTrue(reordered.issues.any { it.contains("词序") })
        val empty = SpeakingEvaluator.evaluate("I am happy.", "", 2_000)
        assertEquals(0, empty.score)
    }
}
