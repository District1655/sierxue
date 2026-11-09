package com.sierxue.english.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sierxue.english.assistant.AssistantContextHolder
import com.sierxue.english.assistant.AssistantRepository
import com.sierxue.english.data.local.AssistantConversationEntity
import com.sierxue.english.data.local.AssistantMessageEntity
import com.sierxue.english.data.remote.ChatSource
import com.sierxue.english.data.remote.OpenAiChatClient
import com.sierxue.english.data.remote.OpenAiTtsClient
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.AppSettings
import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.TtsConfig
import com.sierxue.english.speech.SpeechManager
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val repository: AssistantRepository,
    private val contextHolder: AssistantContextHolder,
    private val settings: SettingsRepository,
    private val speech: SpeechManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state
    val context = contextHolder.context
    val preferences = settings.settings.map { it.assistantEnabled to it.assistantSpeechRate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true to 0.9f)
    private var messagesJob: Job? = null
    private var lastText = ""

    init {
        viewModelScope.launch {
            repository.observeConversations().collect { _state.value = _state.value.copy(conversations = it) }
        }
    }

    fun open() {
        _state.value = _state.value.copy(panelOpen = true, unread = false)
        viewModelScope.launch { activate(repository.ensureConversation()) }
    }

    fun close() { _state.value = _state.value.copy(panelOpen = false) }
    fun showHistory(show: Boolean) { _state.value = _state.value.copy(showHistory = show) }

    fun selectConversation(id: String) = viewModelScope.launch {
        activate(id)
        _state.value = _state.value.copy(showHistory = false)
    }

    fun newConversation() = viewModelScope.launch {
        activate(repository.newConversation())
        _state.value = _state.value.copy(showHistory = false)
    }

    fun send(text: String, autoSpeak: Boolean = false) = viewModelScope.launch {
        if (text.isBlank() || _state.value.loading) return@launch
        lastText = text
        val id = _state.value.activeConversationId ?: repository.ensureConversation().also { activate(it) }
        _state.value = _state.value.copy(loading = true, status = null)
        try {
            val reply = repository.send(id, contextHolder.context.value, text)
            _state.value = _state.value.copy(
                loading = false,
                unread = !_state.value.panelOpen,
                status = reply.errorMessage ?: if (reply.source == ChatSource.OFFLINE) "当前为本地讲解；配置 AI 后可深度回答" else null,
                canRetry = reply.errorMessage != null,
            )
            if (autoSpeak) speech.toggle("assistant_${System.currentTimeMillis()}", reply.text, preferences.value.second)
        } catch (error: Exception) {
            _state.value = _state.value.copy(loading = false, status = "发送失败：${error.message ?: "未知错误"}", canRetry = true)
        }
    }

    fun retry() { if (lastText.isNotBlank()) send(lastText) }

    fun clear() = viewModelScope.launch {
        repository.clear()
        messagesJob?.cancel()
        _state.value = _state.value.copy(activeConversationId = null, messages = emptyList(), status = "对话记录已清除")
        activate(repository.ensureConversation())
    }

    fun hide() = viewModelScope.launch {
        settings.setAssistantEnabled(false)
        _state.value = _state.value.copy(panelOpen = false)
    }

    private suspend fun activate(id: String) {
        messagesJob?.cancel()
        _state.value = _state.value.copy(activeConversationId = id)
        messagesJob = viewModelScope.launch { repository.observeMessages(id).collect { _state.value = _state.value.copy(messages = it) } }
    }
}

data class AssistantUiState(
    val panelOpen: Boolean = false,
    val showHistory: Boolean = false,
    val conversations: List<AssistantConversationEntity> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<AssistantMessageEntity> = emptyList(),
    val loading: Boolean = false,
    val unread: Boolean = false,
    val status: String? = null,
    val canRetry: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val study: StudyRepository,
    private val assistant: AssistantRepository,
    private val chatClient: OpenAiChatClient,
    private val ttsClient: OpenAiTtsClient,
    private val ttsCacheDir: File,
    private val speech: SpeechManager,
) : ViewModel() {
    val values: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setBook(bookId: String) = viewModelScope.launch {
        val first = study.units(bookId).firstOrNull() ?: return@launch
        settings.setBook(bookId, first.id)
    }
    fun setSpeechRate(value: Float) = viewModelScope.launch { settings.setSpeechRate(value) }
    fun setAssistantRate(value: Float) = viewModelScope.launch { settings.setAssistantRate(value) }
    fun setAssistantEnabled(value: Boolean) = viewModelScope.launch { settings.setAssistantEnabled(value) }
    fun setTtsEngine(packageName: String) = viewModelScope.launch {
        settings.setTtsEnginePackage(packageName)
        speech.system.reinitialize()
        _message.value = "已选择 TTS 引擎，正在重新初始化"
    }
    fun reinitializeTts() {
        speech.system.reinitialize()
        _message.value = "正在重新检测系统 TTS"
    }

    fun saveChat(enabled: Boolean, url: String, model: String, key: String) = viewModelScope.launch {
        runCatching { settings.saveChat(enabled, url, model, key) }
            .onSuccess { _message.value = "对话接口配置已保存" }
            .onFailure { _message.value = it.message }
    }

    fun testChat(enabled: Boolean, url: String, model: String, key: String) = viewModelScope.launch {
        _message.value = "正在测试对话接口…"
        runCatching {
            settings.saveChat(enabled, url, model, key)
            val config = ChatConfig(enabled = true, baseUrl = url.trim().trimEnd('/'), model = model.trim())
            chatClient.test(config, key.ifBlank { settings.chatKey() })
        }.onSuccess { _message.value = "对话接口连接成功" }
            .onFailure { _message.value = "对话测试失败：${it.message}" }
    }

    fun clearChatKey() { settings.clearChatKey(); _message.value = "对话 Key 已清除" }

    fun saveTts(enabled: Boolean, url: String, model: String, voice: String, format: String, key: String) = viewModelScope.launch {
        runCatching { settings.saveTts(enabled, url, model, voice, format, key) }
            .onSuccess { _message.value = "TTS 接口配置已保存" }
            .onFailure { _message.value = it.message }
    }

    fun testTts(enabled: Boolean, url: String, model: String, voice: String, format: String, key: String) = viewModelScope.launch {
        _message.value = "正在测试 TTS 接口…"
        runCatching {
            settings.saveTts(enabled, url, model, voice, format, key)
            val config = TtsConfig(enabled = true, baseUrl = url.trim().trimEnd('/'), model = model.trim(), voice = voice.trim(), format = format)
            ttsClient.synthesize(config, key.ifBlank { settings.ttsKey() }, "Hello from SiErXue.", 1.0f, ttsCacheDir)
        }.onSuccess { _message.value = "TTS 接口连接成功，测试音频已缓存" }
            .onFailure { _message.value = "TTS 测试失败：${it.message}" }
    }

    fun clearTtsKey() { settings.clearTtsKey(); _message.value = "TTS Key 已清除" }

    fun clearConversations() = viewModelScope.launch { assistant.clear(); _message.value = "小思对话已清除" }
    fun clearData() = viewModelScope.launch {
        study.clearLearningData()
        speech.clearOnlineCache()
        _message.value = "学习数据、对话与语音缓存已清除"
    }
}
