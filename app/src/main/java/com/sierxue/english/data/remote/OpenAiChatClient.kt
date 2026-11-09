package com.sierxue.english.data.remote

import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.Question
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiChatClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun complete(config: ChatConfig, key: String, messages: JSONArray, maxTokens: Int = 800): String =
        withContext(Dispatchers.IO) {
            require(config.enabled) { "对话接口未开启" }
            require(key.isNotBlank()) { "请先填写对话接口 Key" }
            val body = JSONObject()
                .put("model", config.model)
                .put("messages", messages)
                .put("temperature", 0.3)
                .put("max_tokens", maxTokens)
                .toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url(endpoint(config.baseUrl, "chat/completions"))
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("对话请求失败（HTTP ${response.code}）")
                val text = response.body?.string().orEmpty()
                val content = runCatching {
                    JSONObject(text).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }.getOrElse { throw IOException("对话接口返回格式无法解析") }
                if (content.isEmpty()) throw IOException("对话接口返回了空内容")
                content
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
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        fun endpoint(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/$path")) base else "$base/$path"
        }
    }
}
