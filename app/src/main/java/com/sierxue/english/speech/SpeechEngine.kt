package com.sierxue.english.speech

data class SpeechRequest(val id: String, val text: String, val rate: Float)

interface SpeechCallback {
    fun onStarted(id: String)
    fun onFinished(id: String)
    fun onError(id: String, message: String)
}

interface SpeechEngine {
    suspend fun start(request: SpeechRequest, callback: SpeechCallback)
    fun stop()
}

/** sherpa-onnx 的后续扩展位，阶段 A 不进入发音路由。 */
class OfflineTtsEngine : SpeechEngine {
    override suspend fun start(request: SpeechRequest, callback: SpeechCallback) {
        throw UnsupportedOperationException("离线 TTS 未启用")
    }

    override fun stop() = Unit
}
