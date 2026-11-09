package com.sierxue.english.data.remote

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

open class AiRequestException(
    message: String,
    val httpCode: Int? = null,
    val retryAfterMillis: Long? = null,
    val responseBody: String = "",
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isQuotaExhausted: Boolean get() {
        val text = responseBody.lowercase(Locale.ROOT)
        return listOf("insufficient_quota", "quota exceeded", "exceeded your current quota", "billing").any { it in text }
    }
}

class PartialResponseException(val partial: String, cause: Throwable) : IOException("回答中断", cause)
class AllEndpointsUnavailableException(message: String = "所有端点当前不可用") : IOException(message)
class CircuitOpenException : IOException("端点处于冷却期")

object ErrorClassifier {
    fun isRetryable(error: Throwable): Boolean {
        if (error is AiRequestException) {
            val code = error.httpCode
            if (code in setOf(400, 401, 403, 404, 422) || error.isQuotaExhausted) return false
            return code == 429 || code in setOf(500, 502, 503, 504) || code == null
        }
        return error is IOException
    }

    fun isNonRetryableConfig(error: Throwable): Boolean =
        error is AiRequestException && (error.httpCode in setOf(400, 401, 403, 404, 422) || error.isQuotaExhausted)

    fun summary(error: Throwable): String = when (error) {
        is AiRequestException -> error.httpCode?.let { "HTTP $it" } ?: error.javaClass.simpleName
        else -> error.javaClass.simpleName
    }

    fun userMessage(error: Throwable): String = when {
        error is AiRequestException && error.isQuotaExhausted -> "该模型额度已用完，请切换端点或更换 Key"
        error is AiRequestException && error.httpCode == 429 -> "该模型请求频繁，请稍后重试"
        error is AiRequestException && error.httpCode != null -> "请求配置有误（HTTP ${error.httpCode}），请检查端点、Key 和模型名"
        else -> "网络连接异常，请稍后重试"
    }
}

data class EndpointHealth(
    val endpointId: String,
    val lastSuccessAt: Long = 0,
    val lastError: String = "",
    val consecutiveFailures: Int = 0,
    val coolingUntil: Long = 0,
    val last429At: Long = 0,
    val quotaBlocked: Boolean = false,
    val halfOpenInFlight: Boolean = false,
) {
    fun status(enabled: Boolean, now: Long = System.currentTimeMillis()): String = when {
        !enabled -> "已禁用"
        quotaBlocked -> "额度耗尽（需手动恢复）"
        coolingUntil > now -> "冷却中 ${((coolingUntil - now + 999) / 1000)} 秒"
        halfOpenInFlight -> "半开试探中"
        else -> "正常"
    }
}

class EndpointHealthStore(context: Context) {
    private val prefs = context.getSharedPreferences("channel_health", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Synchronized
    fun tryAcquire(endpointId: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        val health = snapshot(endpointId)
        if (health.quotaBlocked) return false
        val now = System.currentTimeMillis()
        if (health.coolingUntil > now) return false
        if (health.coolingUntil > 0) {
            if (health.halfOpenInFlight) return false
            prefs.edit().putBoolean(key(endpointId, "half_open"), true).apply()
        }
        return true
    }

    @Synchronized
    fun onSuccess(endpointId: String) {
        prefs.edit()
            .putLong(key(endpointId, "success"), System.currentTimeMillis())
            .putInt(key(endpointId, "failures"), 0)
            .putLong(key(endpointId, "cooling"), 0)
            .putBoolean(key(endpointId, "half_open"), false)
            .putBoolean(key(endpointId, "quota_blocked"), false)
            .putString(key(endpointId, "error"), "")
            .apply()
        changed()
    }

    @Synchronized
    fun onFailure(endpointId: String, error: Throwable) {
        val failures = snapshot(endpointId).consecutiveFailures + 1
        val cooling = if (failures >= 3) System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10) else 0L
        prefs.edit()
            .putInt(key(endpointId, "failures"), failures)
            .putString(key(endpointId, "error"), ErrorClassifier.summary(error))
            .putLong(key(endpointId, "cooling"), cooling)
            .putBoolean(key(endpointId, "half_open"), false)
            .apply()
        changed()
    }

    @Synchronized
    fun on429(endpointId: String) {
        val failures = snapshot(endpointId).consecutiveFailures + 1
        prefs.edit()
            .putInt(key(endpointId, "failures"), failures)
            .putLong(key(endpointId, "last_429"), System.currentTimeMillis())
            .putString(key(endpointId, "error"), "HTTP 429")
            .apply()
        changed()
    }

    @Synchronized
    fun apply429Cooldown(endpointId: String) {
        prefs.edit()
            .putLong(key(endpointId, "cooling"), System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(90))
            .putBoolean(key(endpointId, "half_open"), false)
            .apply()
        changed()
    }

    @Synchronized
    fun blockQuota(endpointId: String, error: Throwable) {
        prefs.edit()
            .putBoolean(key(endpointId, "quota_blocked"), true)
            .putString(key(endpointId, "error"), "额度耗尽")
            .putBoolean(key(endpointId, "half_open"), false)
            .apply()
        changed()
    }

    @Synchronized
    fun reset(endpointId: String) {
        prefs.edit()
            .putInt(key(endpointId, "failures"), 0)
            .putLong(key(endpointId, "cooling"), 0)
            .putBoolean(key(endpointId, "half_open"), false)
            .putBoolean(key(endpointId, "quota_blocked"), false)
            .putString(key(endpointId, "error"), "")
            .apply()
        changed()
    }

    @Synchronized
    fun snapshot(endpointId: String): EndpointHealth = EndpointHealth(
        endpointId = endpointId,
        lastSuccessAt = prefs.getLong(key(endpointId, "success"), 0),
        lastError = prefs.getString(key(endpointId, "error"), "").orEmpty(),
        consecutiveFailures = prefs.getInt(key(endpointId, "failures"), 0),
        coolingUntil = prefs.getLong(key(endpointId, "cooling"), 0),
        last429At = prefs.getLong(key(endpointId, "last_429"), 0),
        quotaBlocked = prefs.getBoolean(key(endpointId, "quota_blocked"), false),
        halfOpenInFlight = prefs.getBoolean(key(endpointId, "half_open"), false),
    )

    private fun key(id: String, suffix: String) = "${id}_$suffix"
    private fun changed() { _version.value += 1 }
}

class TokenBucketRegistry(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private data class Bucket(var tokens: Double, var updatedAt: Long)
    private val buckets = mutableMapOf<String, Bucket>()

    @Synchronized
    fun beforeRequest(endpointId: String, rpm: Int, userInitiated: Boolean): Long {
        val bucket = refill(endpointId, rpm)
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return 0
        }
        // 用户主动发送不阻塞；批量/自动任务等待下一次补充。
        return if (userInitiated) 0 else ((1.0 - bucket.tokens) * 60_000 / rpm).toLong().coerceAtLeast(1)
    }

    @Synchronized
    fun consumeAfterWait(endpointId: String, rpm: Int) {
        val bucket = refill(endpointId, rpm)
        bucket.tokens = (bucket.tokens - 1.0).coerceAtLeast(0.0)
    }

    @Synchronized
    fun tokens(endpointId: String, rpm: Int): Int = floor(refill(endpointId, rpm).tokens).toInt()

    private fun refill(endpointId: String, rpm: Int): Bucket {
        val now = clock()
        val bucket = buckets.getOrPut(endpointId) { Bucket(rpm.toDouble(), now) }
        val elapsed = (now - bucket.updatedAt).coerceAtLeast(0)
        bucket.tokens = min(rpm.toDouble(), bucket.tokens + elapsed * rpm / 60_000.0)
        bucket.updatedAt = now
        return bucket
    }
}

data class RetryEvent(
    val endpointId: String,
    val attempt: Int,
    val waitSeconds: Int,
    val message: String,
)

class StableRequestRunner(
    private val health: EndpointHealthStore,
    private val buckets: TokenBucketRegistry,
) {
    suspend fun <T, C> execute(
        endpoints: List<C>,
        idOf: (C) -> String,
        enabledOf: (C) -> Boolean,
        rpmOf: (C) -> Int,
        userInitiated: Boolean,
        onEvent: suspend (RetryEvent) -> Unit = {},
        block: suspend (C) -> T,
    ): T {
        require(endpoints.isNotEmpty()) { throw AllEndpointsUnavailableException() }
        val attempts = mutableMapOf<String, Int>()
        val attempts429 = mutableMapOf<String, Int>()
        val genericAttempts = mutableMapOf<String, Int>()
        val lastErrors = mutableMapOf<String, Throwable>()
        var index = 0
        var globalAttempt = 0
        var streak429 = 0
        var rotateOn429 = false
        var lastError: Throwable? = null
        val startedAt = SystemClock.elapsedRealtime()
        var skippedRounds = 0

        while (globalAttempt < 8) {
            if (attempts429.isNotEmpty() && SystemClock.elapsedRealtime() - startedAt >= 20_000) break
            val endpoint = endpoints[index % endpoints.size]
            val id = idOf(endpoint)
            val enabled = enabledOf(endpoint)
            val totalAttempts = attempts[id] ?: 0
            if (!health.tryAcquire(id, enabled) || totalAttempts >= 5) {
                index = (index + 1) % endpoints.size
                skippedRounds++
                if (skippedRounds > endpoints.size * 2) break
                continue
            }
            skippedRounds = 0
            val previousError = lastErrors[id]
            val is429Retry = previousError is AiRequestException && previousError.httpCode == 429
            if (!is429Retry) {
                val wait = buckets.beforeRequest(id, rpmOf(endpoint), userInitiated)
                if (wait > 0) {
                    onEvent(RetryEvent(id, globalAttempt + 1, ((wait + 999) / 1000).toInt(), "令牌桶限流，约 ${((wait + 999) / 1000)} 秒后发送"))
                    delay(wait)
                    buckets.consumeAfterWait(id, rpmOf(endpoint))
                }
            }

            globalAttempt++
            attempts[id] = totalAttempts + 1
            try {
                val result = block(endpoint)
                applyCooldowns(attempts429)
                health.onSuccess(id)
                return result
            } catch (partial: PartialResponseException) {
                applyCooldowns(attempts429)
                throw partial
            } catch (error: Throwable) {
                lastError = error
                lastErrors[id] = error
                if (error is AiRequestException && error.isQuotaExhausted) {
                    health.blockQuota(id, error)
                    index = (index + 1) % endpoints.size
                    continue
                }
                if (ErrorClassifier.isNonRetryableConfig(error) || !ErrorClassifier.isRetryable(error)) {
                    health.onFailure(id, error)
                    applyCooldowns(attempts429)
                    throw error
                }
                if (error is AiRequestException && error.httpCode == 429) {
                    health.on429(id)
                    val count429 = (attempts429[id] ?: 0) + 1
                    attempts429[id] = count429
                    streak429++
                    if (streak429 >= 3) rotateOn429 = true
                    if (rotateOn429) index = (index + 1) % endpoints.size
                    if (count429 >= 5) {
                        health.apply429Cooldown(id)
                        index = (index + 1) % endpoints.size
                        continue
                    }
                    val shortDelay = when (count429) { 1 -> 1_000L; 2 -> 2_000L; 3 -> 3_000L; else -> 4_000L }
                    val headerDelay = error.retryAfterMillis
                    val wait = if (headerDelay != null && headerDelay <= 8_000) headerDelay else shortDelay
                    onEvent(RetryEvent(id, globalAttempt + 1, (wait / 1000).toInt(), "该模型请求频繁，正在重试（第 ${globalAttempt + 1} 次，约 ${wait / 1000} 秒）"))
                    delay(wait)
                } else {
                    health.onFailure(id, error)
                    val genericCount = (genericAttempts[id] ?: 0) + 1
                    genericAttempts[id] = genericCount
                    streak429 = 0
                    if (genericCount >= 3) {
                        index = (index + 1) % endpoints.size
                        continue
                    }
                    val base = (1_000L * (1L shl (genericCount - 1))).coerceAtMost(8_000L)
                    val wait = base + Random.nextLong(0, 251)
                    onEvent(RetryEvent(id, globalAttempt + 1, (wait / 1000).toInt(), "网络异常，正在重试（第 ${globalAttempt + 1} 次）"))
                    delay(wait)
                }
            }
        }
        applyCooldowns(attempts429)
        throw lastError ?: AllEndpointsUnavailableException()
    }

    private fun applyCooldowns(attempts429: Map<String, Int>) {
        attempts429.filterValues { it >= 3 }.keys.forEach(health::apply429Cooldown)
    }
}

fun parseRetryAfterSeconds(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it }
    return runCatching {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text) ?: return@runCatching null
        ((date.time - Date().time) / 1000).coerceAtLeast(0)
    }.getOrNull()
}

fun parseRateLimitDuration(value: String?): Long? {
    val text = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (text.isEmpty()) return null
    if (text.endsWith("ms")) return ((text.removeSuffix("ms").toDoubleOrNull() ?: return null) / 1000).toLong()
    var seconds = 0L
    val pattern = Regex("(\\d+(?:\\.\\d+)?)([hms])")
    var matched = false
    pattern.findAll(text).forEach {
        matched = true
        val amount = it.groupValues[1].toDouble()
        seconds += when (it.groupValues[2]) {
            "h" -> (amount * 3600).toLong()
            "m" -> (amount * 60).toLong()
            else -> amount.toLong()
        }
    }
    return if (matched) seconds else null
}
