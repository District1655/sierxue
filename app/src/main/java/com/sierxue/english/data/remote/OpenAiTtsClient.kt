package com.sierxue.english.data.remote

import com.sierxue.english.domain.TtsConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OpenAiTtsClient(
    private val client: OkHttpClient = OpenAiChatClient.sharedClient(),
) {
    suspend fun synthesize(
        config: TtsConfig,
        key: String,
        text: String,
        rate: Float,
        cacheDir: File,
    ): File = withContext(Dispatchers.IO) {
        require(config.enabled) { "在线 TTS 未开启" }
        require(key.isNotBlank()) { "请先填写 TTS 接口 Key" }
        require(text.isNotBlank()) { "朗读内容不能为空" }
        cacheDir.mkdirs()
        val cacheKey = listOf(config.baseUrl, config.model, config.voice, config.format, rate.toString(), text)
            .joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        val target = File(cacheDir, "$name.${config.format}")
        if (target.isFile && target.length() > 0) return@withContext target

        val payload = JSONObject()
            .put("model", config.model)
            .put("voice", config.voice)
            .put("input", text)
            .put("response_format", config.format)
            .put("speed", rate.toDouble())
            .toString().toRequestBody(OpenAiChatClient.JSON_TYPE)
        val request = Request.Builder()
            .url(OpenAiChatClient.endpoint(config.baseUrl, "audio/speech"))
            .header("Authorization", "Bearer $key")
            .post(payload)
            .build()
        val temporary = File(cacheDir, "$name.tmp")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw OpenAiChatClient.responseException(response, errorBody)
                }
                val source = response.body?.byteStream() ?: throw IOException("TTS 返回了空音频")
                source.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (temporary.length() == 0L) throw IOException("TTS 返回了空音频")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }
}
