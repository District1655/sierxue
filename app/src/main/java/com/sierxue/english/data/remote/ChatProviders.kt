package com.sierxue.english.data.remote

import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.ChatProvider
import com.sierxue.english.domain.ChatRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class RemoteChatProvider(
    private val settings: SettingsRepository,
    private val client: OpenAiChatClient,
) : ChatProvider {
    override suspend fun respond(request: ChatRequest): String {
        val config = settings.settings.first().chat
        return client.complete(config, settings.chatKey(config.id), messages(request))
    }

    suspend fun respondStream(request: ChatRequest, config: ChatConfig, onDelta: suspend (String) -> Unit): String =
        client.completeStream(config, settings.chatKey(config.id), messages(request), onDelta)

    private fun messages(request: ChatRequest): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", request.systemPrompt))
            .put(JSONObject().put("role", "system").put("content", "当前精简学习上下文：${request.contextJson}"))
        request.history.takeLast(6).forEach {
            if (it.role == "user" || it.role == "assistant") {
                messages.put(JSONObject().put("role", it.role).put("content", it.content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", request.userMessage))
        return messages
    }
}

class OfflineFallbackProvider : ChatProvider {
    override suspend fun respond(request: ChatRequest): String = request.fallbackText
}

enum class ChatSource { REMOTE, OFFLINE }
enum class AssistantChannelPhase { MAIN_HEALTHY, RETRYING, FALLBACK_ACTIVE, OFFLINE }

data class ChannelMonitor(
    val phase: AssistantChannelPhase = AssistantChannelPhase.MAIN_HEALTHY,
    val message: String? = null,
)

data class ProviderReply(
    val text: String,
    val source: ChatSource,
    val errorMessage: String? = null,
    val interrupted: Boolean = false,
    val usedBackup: Boolean = false,
)

class ChatProviderRouter(
    private val settings: SettingsRepository,
    private val remote: RemoteChatProvider,
    private val offline: OfflineFallbackProvider,
    private val runner: StableRequestRunner,
) {
    private val _monitor = MutableStateFlow(ChannelMonitor())
    val monitor: StateFlow<ChannelMonitor> = _monitor
    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean = size > 32
    }

    suspend fun respond(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit = {},
        userInitiated: Boolean = true,
    ): ProviderReply {
        val cacheKey = if (isPureQuery(request.userMessage)) request.userMessage.trim() + "\n" + request.contextJson else null
        if (cacheKey != null) {
            synchronized(cache) {
                val entry = cache[cacheKey]
                if (entry != null && System.currentTimeMillis() - entry.createdAt < CACHE_TTL_MS) {
                    _monitor.value = ChannelMonitor(AssistantChannelPhase.MAIN_HEALTHY)
                    return ProviderReply(entry.text, ChatSource.REMOTE)
                }
                if (entry != null) cache.remove(cacheKey)
            }
        }

        val endpoints = settings.settings.first().chatEndpoints
            .filter { it.enabled && it.hasKey }
            .sortedByDescending { it.primary }
        if (endpoints.isEmpty()) return offlineReply(request, null)
        val primaryId = endpoints.firstOrNull()?.id
        var usedBackup = false
        try {
            val text = runner.execute(
                endpoints = endpoints,
                idOf = { it.id },
                enabledOf = { it.enabled && it.hasKey },
                rpmOf = { it.requestsPerMinute },
                userInitiated = userInitiated,
                onEvent = { event ->
                    usedBackup = usedBackup || event.endpointId != primaryId
                    _monitor.value = ChannelMonitor(AssistantChannelPhase.RETRYING, event.message)
                },
                block = { endpoint ->
                    usedBackup = usedBackup || endpoint.id != primaryId
                    remote.respondStream(request, endpoint, onDelta)
                },
            )
            if (cacheKey != null) synchronized(cache) { cache[cacheKey] = CacheEntry(text) }
            _monitor.value = ChannelMonitor(if (usedBackup) AssistantChannelPhase.FALLBACK_ACTIVE else AssistantChannelPhase.MAIN_HEALTHY)
            return ProviderReply(text, ChatSource.REMOTE, usedBackup = usedBackup)
        } catch (partial: PartialResponseException) {
            _monitor.value = ChannelMonitor(AssistantChannelPhase.RETRYING, "回答中断，可点击继续")
            return ProviderReply(
                text = partial.partial + "\n（回答中断）",
                source = ChatSource.REMOTE,
                errorMessage = "回答中断，可点击继续",
                interrupted = true,
                usedBackup = usedBackup,
            )
        } catch (error: Throwable) {
            return offlineReply(request, ErrorClassifier.userMessage(error), usedBackup)
        }
    }

    private suspend fun offlineReply(request: ChatRequest, error: String?, usedBackup: Boolean = false): ProviderReply {
        _monitor.value = ChannelMonitor(AssistantChannelPhase.OFFLINE, error)
        val local = offline.respond(request)
        val marked = if (local.startsWith("离线回答")) local else "离线回答\n$local"
        return ProviderReply(marked, ChatSource.OFFLINE, error, usedBackup = usedBackup)
    }

    private fun isPureQuery(text: String): Boolean {
        val generates = (text.contains("出") && text.contains("题")) || text.contains("生成")
        return !generates && listOf("解释", "翻译", "讲讲", "为什么", "什么意思").any { text.contains(it) }
    }

    private data class CacheEntry(val text: String, val createdAt: Long = System.currentTimeMillis())

    private companion object { const val CACHE_TTL_MS = 5 * 60 * 1000L }
}
