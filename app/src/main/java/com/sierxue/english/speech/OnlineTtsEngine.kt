package com.sierxue.english.speech

import android.content.Context
import android.media.MediaPlayer
import com.sierxue.english.data.remote.OpenAiTtsClient
import com.sierxue.english.data.remote.StableRequestRunner
import com.sierxue.english.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class OnlineTtsEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val client: OpenAiTtsClient,
    private val runner: StableRequestRunner,
) : SpeechEngine {
    private val cacheDir = File(context.filesDir, "tts")
    private var player: MediaPlayer? = null

    override suspend fun start(request: SpeechRequest, callback: SpeechCallback) {
        val endpoints = settings.settings.first().ttsEndpoints
            .filter { it.enabled && it.hasKey }
            .sortedByDescending { it.primary }
        if (endpoints.isEmpty()) throw IllegalStateException("未配置在线 TTS 端点")
        val audio = runner.execute(
            endpoints = endpoints,
            idOf = { it.id },
            enabledOf = { it.enabled && it.hasKey },
            rpmOf = { it.requestsPerMinute },
            userInitiated = true,
            block = { endpoint -> client.synthesize(endpoint, settings.ttsKey(endpoint.id), request.text, request.rate, cacheDir) },
        )
        withContext(Dispatchers.IO) {
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setDataSource(audio.absolutePath)
                mediaPlayer.setOnCompletionListener {
                    callback.onFinished(request.id)
                    player?.release()
                    player = null
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    callback.onError(request.id, "在线音频播放失败")
                    player?.release()
                    player = null
                    true
                }
                mediaPlayer.prepare()
                player = mediaPlayer
                mediaPlayer.start()
                callback.onStarted(request.id)
            } catch (error: Exception) {
                mediaPlayer.release()
                audio.delete()
                throw error
            }
        }
    }

    override fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
    }

    fun clearCache() {
        stop()
        cacheDir.deleteRecursively()
    }
}
