package com.sierxue.english.speech

import com.sierxue.english.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class PlaybackStatus { IDLE, PREPARING, PLAYING }
data class SpeechState(
    val id: String = "",
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val message: String? = null,
)

class SpeechManager(
    private val settings: SettingsRepository,
    val system: SystemTtsEngine,
    private val online: OnlineTtsEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state
    private var currentId: String? = null
    private var currentEngine: SpeechEngine? = null
    private var currentJob: kotlinx.coroutines.Job? = null

    fun toggle(id: String, text: String, rate: Float, forceSystem: Boolean = false) {
        if (text.isBlank()) return
        if (currentId == id && _state.value.status != PlaybackStatus.IDLE) {
            stopAll()
            return
        }
        stopAll()
        currentId = id
        _state.value = SpeechState(id, PlaybackStatus.PREPARING)
        currentJob = scope.launch {
            try {
                val config = settings.settings.first()
                if (!forceSystem && config.tts.enabled && config.tts.hasKey) {
                    try {
                        currentEngine = online
                        online.start(SpeechRequest(id, text, rate), callback(id))
                        return@launch
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _state.value = _state.value.copy(message = "在线语音不可用，已使用系统发音")
                    }
                }
                currentEngine = system
                system.start(SpeechRequest(id, text, rate), callback(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentId == id) _state.value = SpeechState(id, PlaybackStatus.IDLE, error.message ?: "发音不可用")
            }
        }
    }

    fun stop(id: String) {
        if (currentId == id) stopAll()
    }

    fun stopAll() {
        currentJob?.cancel()
        currentJob = null
        currentEngine?.stop()
        currentEngine = null
        currentId = null
        _state.value = SpeechState()
    }

    fun clearOnlineCache() = online.clearCache()

    private fun callback(id: String) = object : SpeechCallback {
        override fun onStarted(id: String) {
            if (currentId == id) _state.value = SpeechState(id, PlaybackStatus.PLAYING, _state.value.message)
        }

        override fun onFinished(id: String) {
            if (currentId == id) {
                currentEngine = null
                currentId = null
                _state.value = SpeechState()
            }
        }

        override fun onError(id: String, message: String) {
            if (currentId == id) {
                currentEngine = null
                currentId = null
                _state.value = SpeechState(id, PlaybackStatus.IDLE, message)
            }
        }
    }
}
