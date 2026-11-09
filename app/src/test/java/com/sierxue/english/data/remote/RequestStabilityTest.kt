package com.sierxue.english.data.remote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestStabilityTest {
    @Test
    fun retryAfterSupportsSecondsAndHttpDate() {
        assertEquals(6L, parseRetryAfterSeconds("6"))
        val future = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).format(Date(System.currentTimeMillis() + 5_000))
        val seconds = parseRetryAfterSeconds(future)
        assertTrue(seconds != null && seconds in 3..5)
    }

    @Test
    fun openAiRateLimitDurationsAreParsed() {
        assertEquals(360L, parseRateLimitDuration("6m0s"))
        assertEquals(1L, parseRateLimitDuration("1s"))
        assertEquals(0L, parseRateLimitDuration("200ms"))
    }

    @Test
    fun quotaErrorsAreNotOrdinaryRateLimits() {
        val error = AiRequestException("HTTP 429", 429, responseBody = "insufficient_quota: billing required")
        assertTrue(error.isQuotaExhausted)
        assertTrue(!ErrorClassifier.isRetryable(error))
    }

    @Test
    fun tokenBucketLimitsBackgroundRequests() {
        val buckets = TokenBucketRegistry { 1L }
        repeat(20) { assertEquals(0, buckets.beforeRequest("chat_test", 20, userInitiated = false)) }
        assertTrue(buckets.beforeRequest("chat_test", 20, userInitiated = false) > 0)
        assertEquals(0, buckets.beforeRequest("chat_test", 20, userInitiated = true))
    }
}
