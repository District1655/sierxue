package com.sierxue.english.data.local

import android.app.ActivityManager
import android.content.Context
import com.sierxue.english.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LocalModelException(message: String) : Exception(message)

data class LocalGeneration(
    val output: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val stopReason: String,
    val modelLoadMs: Long,
    val thinkingEnabled: Boolean,
)

class LlamaCppEngine(
    context: Context,
    private val assets: LocalModelAssetStore,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val _loadedModelId = MutableStateFlow<String?>(null)
    val loadedModelId: StateFlow<String?> = _loadedModelId
    private var loadedPath: String? = null

    suspend fun generate(
        modelId: String,
        systemPrompt: String,
        contextJson: String,
        history: List<ChatMessage>,
        userMessage: String,
        thinking: Boolean,
        maxTokens: Int = 1024,
    ): LocalGeneration {
        nativeLoadError?.let { throw LocalModelException("本地推理库不可用：$it") }
        val file = assets.requireReady(modelId)
        return mutex.withLock {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (loadedPath != file.absolutePath) checkMemory()
            val historyJson = JSONArray().apply {
                history.takeLast(4).forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content.take(400)))
                }
            }.toString()
            val raw = try {
                withContext(Dispatchers.Default) {
                    nativeGenerate(
                        file.absolutePath,
                        systemPrompt,
                        contextJson,
                        historyJson,
                        userMessage,
                        thinking,
                        maxTokens,
                    )
                }
            } catch (error: OutOfMemoryError) {
                clearLoaded()
                throw LocalModelException("内存不足，本地模型会话已释放")
            }
            val json = JSONObject(raw)
            if (json.has("error")) {
                clearLoaded()
                throw LocalModelException(json.optString("error"))
            }
            val stop = json.optString("stopReason")
            if (stop == "cancelled") throw LocalModelException("本地生成已取消")
            loadedPath = file.absolutePath
            _loadedModelId.value = modelId
            LocalGeneration(
                output = json.optString("output"),
                promptTokens = json.optInt("promptTokens"),
                generatedTokens = json.optInt("generatedTokens"),
                stopReason = stop,
                modelLoadMs = json.optLong("modelLoadMs"),
                thinkingEnabled = json.optBoolean("thinkingEnabled"),
            )
        }
    }

    fun cancel() {
        runCatching { nativeCancel() }
    }

    suspend fun close() = mutex.withLock {
        runCatching { nativeCloseModel() }
        clearLoaded()
    }

    private fun checkMemory() {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.lowMemory || info.availMem < MIN_AVAILABLE_BYTES) {
            val available = "%.1f".format(info.availMem / 1024.0 / 1024.0 / 1024.0)
            throw LocalModelException("可用内存仅 ${available} GB，低于 1.5 GB，已拒绝加载本地模型")
        }
    }

    private fun clearLoaded() {
        loadedPath = null
        _loadedModelId.value = null
    }

    private external fun nativeGenerate(
        modelPath: String,
        systemPrompt: String,
        contextJson: String,
        historyJson: String,
        userMessage: String,
        thinking: Boolean,
        maxTokens: Int,
    ): String

    private external fun nativeCancel()
    private external fun nativeCloseModel()

    private companion object {
        const val MIN_AVAILABLE_BYTES = 1536L * 1024 * 1024

        val nativeLoadError: String? = try {
            System.loadLibrary("sierxue_llama")
            null
        } catch (error: Throwable) {
            "${error.javaClass.simpleName}: ${error.message}"
        }
    }
}
