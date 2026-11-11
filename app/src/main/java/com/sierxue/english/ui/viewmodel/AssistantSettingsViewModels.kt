package com.sierxue.english.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sierxue.english.assistant.AssistantContextHolder
import com.sierxue.english.assistant.AssistantRepository
import com.sierxue.english.data.local.AssistantConversationEntity
import com.sierxue.english.data.local.AssistantMessageEntity
import com.sierxue.english.data.local.LlamaCppEngine
import com.sierxue.english.data.local.LocalModelAssetStore
import com.sierxue.english.data.remote.ChatSource
import com.sierxue.english.data.remote.EndpointHealthStore
import com.sierxue.english.data.remote.ErrorClassifier
import com.sierxue.english.data.remote.OpenAiChatClient
import com.sierxue.english.data.remote.OpenAiTtsClient
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.AiChannel
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
    private val localEngine: LlamaCppEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state
    val context = contextHolder.context
    val channelMonitor = repository.channelMonitor
    val preferences = settings.settings.map { it.assistantEnabled to it.assistantSpeechRate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true to 0.9f)
    private var messagesJob: Job? = null
    private var sendJob: Job? = null
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

    fun send(text: String, autoSpeak: Boolean = false) {
        sendJob = viewModelScope.launch {
        if (text.isBlank() || _state.value.loading) return@launch
        lastText = text
        val id = _state.value.activeConversationId ?: repository.ensureConversation().also { activate(it) }
        _state.value = _state.value.copy(loading = true, status = null, streamingText = "", canContinue = false, canRetry = false)
        try {
            val reply = repository.send(id, contextHolder.context.value, text) { delta ->
                _state.value = _state.value.copy(streamingText = (_state.value.streamingText ?: "") + delta)
            }
            _state.value = _state.value.copy(
                loading = false,
                unread = !_state.value.panelOpen,
                status = reply.errorMessage ?: if (reply.source == ChatSource.OFFLINE) "当前为本地讲解；配置 AI 后可深度回答" else null,
                canRetry = reply.errorMessage != null && !reply.interrupted,
                canContinue = reply.interrupted,
                streamingText = null,
            )
            if (autoSpeak) speech.toggle("assistant_${System.currentTimeMillis()}", reply.text, preferences.value.second)
        } catch (error: kotlinx.coroutines.CancellationException) {
            _state.value = _state.value.copy(loading = false, status = "已取消", streamingText = null)
        } catch (error: Exception) {
            _state.value = _state.value.copy(loading = false, status = "发送失败：${error.message ?: "未知错误"}", canRetry = true)
        }
        }
    }

    fun cancelGeneration() {
        localEngine.cancel()
        sendJob?.cancel()
        _state.value = _state.value.copy(loading = false, status = "已取消", streamingText = null)
    }

    fun retry() { if (lastText.isNotBlank()) send(lastText) }
    fun continueAnswer() { send("请从上次中断处继续，不要重复已输出内容。") }

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
    val canContinue: Boolean = false,
    val streamingText: String? = null,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val study: StudyRepository,
    private val assistant: AssistantRepository,
    private val chatClient: OpenAiChatClient,
    private val ttsClient: OpenAiTtsClient,
    private val ttsCacheDir: File,
    private val speech: SpeechManager,
    val healthStore: EndpointHealthStore,
    private val modelAssets: LocalModelAssetStore,
    private val localEngine: LlamaCppEngine,
) : ViewModel() {
    val values: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    val models = modelAssets.status
    val loadedModelId = localEngine.loadedModelId
    val modelUsageBytes = MutableStateFlow(modelAssets.usageBytes())

    fun setAiChannel(value: AiChannel) = viewModelScope.launch { settings.setAiChannel(value) }
    fun setLocalThinking(value: Boolean) = viewModelScope.launch { settings.setLocalThinking(value) }
    fun selectLocalModel(id: String) = viewModelScope.launch { settings.setLocalModel(id) }
    fun downloadModel(id: String) = viewModelScope.launch {
        _message.value = "开始下载模型，可取消或稍后续传"
        runCatching { modelAssets.download(id) }
            .onSuccess { _message.value = "模型下载并通过 SHA256 校验" }
            .onFailure { _message.value = modelAssets.statusOf(id).error ?: "下载失败" }
        modelUsageBytes.value = modelAssets.usageBytes()
    }
    fun importModel(id: String, uri: Uri) = viewModelScope.launch {
        _message.value = "正在导入并校验模型…"
        runCatching { modelAssets.import(id, uri) }
            .onSuccess { _message.value = "模型导入并通过 SHA256 校验" }
            .onFailure { _message.value = modelAssets.statusOf(id).error ?: "导入失败" }
        modelUsageBytes.value = modelAssets.usageBytes()
    }
    fun cancelModelTransfer(id: String) {
        modelAssets.cancel(id)
        _message.value = "正在取消，下载会保留断点"
    }
    fun deleteModel(id: String) = viewModelScope.launch {
        localEngine.cancel()
        localEngine.close()
        modelAssets.delete(id)
        modelUsageBytes.value = modelAssets.usageBytes()
        _message.value = "模型已删除"
    }

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

    fun saveChatEndpoints(endpoints: List<ChatConfig>, keys: Map<String, String>) = viewModelScope.launch {
        runCatching { settings.saveChatEndpoints(endpoints, keys) }
            .onSuccess { _message.value = "对话端点已保存" }
            .onFailure { _message.value = it.message }
    }

    fun saveTtsEndpoints(endpoints: List<TtsConfig>, keys: Map<String, String>) = viewModelScope.launch {
        runCatching { settings.saveTtsEndpoints(endpoints, keys) }
            .onSuccess { _message.value = "TTS 端点已保存" }
            .onFailure { _message.value = it.message }
    }

    fun testChatEndpoint(config: ChatConfig, keyInput: String) = viewModelScope.launch {
        _message.value = "正在测试 ${config.name.ifBlank { config.id }}…"
        runCatching { chatClient.test(config.copy(enabled = true), keyInput.ifBlank { settings.chatKey(config.id) }) }
            .onSuccess {
                healthStore.onSuccess(config.id)
                _message.value = "对话端点连接成功"
            }.onFailure {
                healthStore.onFailure(config.id, it)
                _message.value = "对话测试失败：${ErrorClassifier.userMessage(it)}"
            }
    }

    fun testTtsEndpoint(config: TtsConfig, keyInput: String) = viewModelScope.launch {
        _message.value = "正在测试 ${config.name.ifBlank { config.id }}…"
        runCatching {
            ttsClient.synthesize(config.copy(enabled = true), keyInput.ifBlank { settings.ttsKey(config.id) }, "Hello from SiErXue.", 1.0f, ttsCacheDir)
        }.onSuccess {
            healthStore.onSuccess(config.id)
            _message.value = "TTS 端点连接成功，测试音频已缓存"
        }.onFailure {
            healthStore.onFailure(config.id, it)
            _message.value = "TTS 测试失败：${ErrorClassifier.userMessage(it)}"
        }
    }

    fun resetEndpoint(id: String) {
        healthStore.reset(id)
        _message.value = "端点已恢复并重置健康状态"
    }

    fun clearChatKey(id: String) { settings.clearChatKey(id); _message.value = "对话 Key 已清除" }
    fun clearTtsKey(id: String) { settings.clearTtsKey(id); _message.value = "TTS Key 已清除" }

    fun clearConversations() = viewModelScope.launch { assistant.clear(); _message.value = "小思对话已清除" }
    fun clearData() = viewModelScope.launch {
        study.clearLearningData()
        speech.clearOnlineCache()
        _message.value = "学习数据、对话与语音缓存已清除"
    }
}
