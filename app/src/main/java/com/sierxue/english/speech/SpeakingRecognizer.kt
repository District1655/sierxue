package com.sierxue.english.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeakingRecognizer(
    context: Context,
    private val onResult: (String, Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        if (!isAvailable()) {
            onError("设备没有可用的语音识别引擎，可跳过跟读")
            return
        }
        releaseRecognizer()
        startedAt = System.currentTimeMillis()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    onResult(text, System.currentTimeMillis() - startedAt)
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风"
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请靠近麦克风再试"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音，请再试一次"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务不可用；可安装离线识别包或跳过"
                        else -> "语音识别失败（$error），可重试或跳过"
                    }
                    onError(message)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun stop() = recognizer?.stopListening()
    fun cancel() = recognizer?.cancel()
    fun destroy() {
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }
}
