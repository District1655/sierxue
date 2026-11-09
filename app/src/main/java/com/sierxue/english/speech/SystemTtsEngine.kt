package com.sierxue.english.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class SystemTtsStatus { INITIALIZING, READY, MISSING_DATA, UNSUPPORTED, FAILED }

class SystemTtsEngine(context: Context) : SpeechEngine {
    private val _status = MutableStateFlow(SystemTtsStatus.INITIALIZING)
    val status: StateFlow<SystemTtsStatus> = _status
    private var callback: SpeechCallback? = null
    private var activeId: String? = null

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) {
                _status.value = SystemTtsStatus.FAILED
                return@TextToSpeech
            }
            if (!::tts.isInitialized) return@TextToSpeech
            when (tts.setLanguage(Locale.US)) {
                TextToSpeech.LANG_MISSING_DATA -> _status.value = SystemTtsStatus.MISSING_DATA
                TextToSpeech.LANG_NOT_SUPPORTED -> _status.value = SystemTtsStatus.UNSUPPORTED
                else -> _status.value = SystemTtsStatus.READY
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { callback?.onStarted(id) }
            override fun onDone(id: String) {
                callback?.onFinished(id)
                activeId = null
            }
            @Deprecated("Android 旧版本回调")
            override fun onError(id: String) { callback?.onError(id, "系统发音失败") }
            override fun onError(id: String, errorCode: Int) { callback?.onError(id, "系统发音失败（$errorCode）") }
        })
    }

    override suspend fun start(request: SpeechRequest, callback: SpeechCallback) {
        val current = try {
            withTimeout(6_000) { status.filter { it != SystemTtsStatus.INITIALIZING }.first() }
        } catch (error: TimeoutException) {
            throw IllegalStateException("系统发音引擎初始化超时")
        }
        if (current != SystemTtsStatus.READY) {
            val message = when (current) {
                SystemTtsStatus.MISSING_DATA -> "未安装英语语音数据，请在设置中安装"
                SystemTtsStatus.UNSUPPORTED -> "当前系统不支持英语发音"
                else -> "系统发音引擎初始化失败"
            }
            throw IllegalStateException(message)
        }
        withContext(Dispatchers.Main) {
            this@SystemTtsEngine.callback = callback
            activeId = request.id
            tts.setSpeechRate(request.rate.coerceIn(0.7f, 1.3f))
            val result = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, Bundle(), request.id)
            if (result == TextToSpeech.ERROR) {
                activeId = null
                throw IllegalStateException("系统发音启动失败")
            }
        }
    }

    override fun stop() {
        tts.stop()
        activeId = null
    }

    fun installDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    companion object {
        private var engineReference: TextToSpeech? = null
    }
}
