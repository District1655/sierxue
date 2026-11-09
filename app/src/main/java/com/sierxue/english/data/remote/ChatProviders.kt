package com.sierxue.english.data.remote

import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.domain.ChatProvider
import com.sierxue.english.domain.ChatRequest
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class RemoteChatProvider(
    private val settings: SettingsRepository,
    private val client: OpenAiChatClient,
) : ChatProvider {
    override suspend fun respond(request: ChatRequest): String {
        val config = settings.settings.first().chat
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", request.systemPrompt))
            .put(JSONObject().put("role", "system").put("content", "当前精简学习上下文：${request.contextJson}"))
        request.history.takeLast(8).forEach {
            if (it.role == "user" || it.role == "assistant") {
                messages.put(JSONObject().put("role", it.role).put("content", it.content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", request.userMessage))
        return client.complete(config, settings.chatKey(), messages)
    }
}

class OfflineFallbackProvider : ChatProvider {
    override suspend fun respond(request: ChatRequest): String = request.fallbackText
}

enum class ChatSource { REMOTE, OFFLINE }

data class ProviderReply(val text: String, val source: ChatSource, val errorMessage: String? = null)

class ChatProviderRouter(
    private val settings: SettingsRepository,
    private val remote: RemoteChatProvider,
    private val offline: OfflineFallbackProvider,
) {
    suspend fun respond(request: ChatRequest): ProviderReply {
        val config = settings.settings.first().chat
        if (config.enabled && config.hasKey) {
            return try {
                ProviderReply(remote.respond(request), ChatSource.REMOTE)
            } catch (error: Exception) {
                ProviderReply(
                    offline.respond(request),
                    ChatSource.OFFLINE,
                    "AI 暂时不可用：${error.message ?: "请求失败"}",
                )
            }
        }
        return ProviderReply(offline.respond(request), ChatSource.OFFLINE)
    }
}
