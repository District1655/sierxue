package com.sierxue.english.data.remote

import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.Question
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class OpenAiChatClient(
    private val client: OkHttpClient = sharedClient(),
    private val streamClient: OkHttpClient = sharedClient().newBuilder().readTimeout(12, TimeUnit.SECONDS).build(),
) {
    suspend fun complete(config: ChatConfig, key: String, messages: JSONArray, maxTokens: Int = MAX_TOKENS): String =
        withContext(Dispatchers.IO) {
            require(config.enabled) { "对话接口未开启" }
            require(key.isNotBlank()) { "请先填写对话接口 Key" }
            val body = JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("temperature", 0.3)
                .put("max_tokens", maxTokens.coerceAtMost(MAX_TOKENS))
                .toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url(endpoint(config.baseUrl, "chat/completions"))
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw responseException(response, text)
                val content = runCatching {
                    JSONObject(text).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }.getOrElse { throw IOException("对话接口返回格式无法解析") }
                if (content.isEmpty()) throw IOException("对话接口返回了空内容")
                content
            }
        }

    suspend fun completeStream(
        config: ChatConfig,
        key: String,
        messages: JSONArray,
        onDelta: suspend (String) -> Unit,
        maxTokens: Int = MAX_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        require(config.enabled) { "对话接口未开启" }
        require(key.isNotBlank()) { "请先填写对话接口 Key" }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens.coerceAtMost(MAX_TOKENS))
            .put("stream", true)
            .toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(endpoint(config.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw responseException(response, errorBody)
            }
            val source = response.body?.byteStream() ?: throw IOException("对话接口返回空响应")
            val full = StringBuilder()
            try {
                source.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                        val payload = if (trimmed.startsWith("data:")) trimmed.substringAfter("data:").trim() else trimmed
                        if (payload == "[DONE]") break
                        if (!payload.startsWith("{")) continue
                        val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                        val delta = choice?.optJSONObject("delta")?.optString("content").orEmpty()
                            .ifEmpty { choice?.optJSONObject("message")?.optString("content").orEmpty() }
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onDelta(delta)
                        }
                    }
                }
                if (full.isEmpty()) throw IOException("对话接口返回了空内容")
                full.toString()
            } catch (error: IOException) {
                if (full.isNotEmpty()) throw PartialResponseException(full.toString(), error)
                throw error
            }
        }
    }

    suspend fun test(config: ChatConfig, key: String): String {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK only."))
        return complete(config, key, messages, maxTokens = 8)
    }

    suspend fun explain(config: ChatConfig, key: String, question: Question, selected: List<String>): String {
        val detail = JSONObject()
            .put("题干", question.prompt)
            .put("选项", JSONArray(question.options.map { "${it.id}. ${it.text}" }))
            .put("用户答案", JSONArray(selected))
            .put("正确答案", JSONArray(question.answers))
            .put("本地解析", question.explanation)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是英语助教，用简洁中文讲解考点和干扰项，不要索取隐私信息。"))
            .put(JSONObject().put("role", "user").put("content", "请深度解析这道题：\n$detail"))
        return complete(config, key, messages, maxTokens = 600)
    }

    internal companion object {
        const val MAX_TOKENS = 800
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private val sharedPool = okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES)
        private val shared by lazy {
            OkHttpClient.Builder()
                .connectionPool(sharedPool)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .callTimeout(25, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        fun sharedClient(): OkHttpClient = shared

        fun endpoint(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/$path")) base else "$base/$path"
        }

        fun responseException(response: Response, body: String): AiRequestException {
            val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"))?.times(1000)
                ?: listOf("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens")
                    .mapNotNull { parseRateLimitDuration(response.header(it)) }
                    .maxOrNull()?.times(1000)
            return AiRequestException(
                message = "请求失败（HTTP ${response.code}）",
                httpCode = response.code,
                retryAfterMillis = retryAfter,
                responseBody = body,
            )
        }
    }
}
