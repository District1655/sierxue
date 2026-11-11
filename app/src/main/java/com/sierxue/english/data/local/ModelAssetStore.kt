package com.sierxue.english.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val MODELS_RELEASE = "https://github.com/District1655/sierxue/releases/download/models-v1"

data class LocalModelVariant(
    val id: String,
    val displayName: String,
    val quantization: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val downloadUrl: String,
)

object LocalModels {
    val Q8 = LocalModelVariant(
        id = "qwen3_q8_0",
        displayName = "Qwen3-0.6B-Instruct",
        quantization = "Q8_0",
        fileName = "Qwen3-0.6B-Q8_0.gguf",
        expectedBytes = 639_446_688L,
        expectedSha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
        downloadUrl = "$MODELS_RELEASE/Qwen3-0.6B-Q8_0.gguf",
    )
    val Q4 = LocalModelVariant(
        id = "qwen3_q4_k_m",
        displayName = "Qwen3-0.6B-Instruct（省存储）",
        quantization = "Q4_K_M",
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        expectedBytes = 396_705_472L,
        expectedSha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        downloadUrl = "$MODELS_RELEASE/Qwen3-0.6B-Q4_K_M.gguf",
    )
    val all = listOf(Q8, Q4)
    fun byId(id: String): LocalModelVariant = all.firstOrNull { it.id == id } ?: Q8
}

enum class ModelTransferState { NOT_INSTALLED, DOWNLOADING, IMPORTING, READY, ERROR }

data class ModelAssetStatus(
    val variant: LocalModelVariant,
    val transferState: ModelTransferState = ModelTransferState.NOT_INSTALLED,
    val installedBytes: Long = 0,
    val verified: Boolean = false,
    val transferredBytes: Long = 0,
    val error: String? = null,
) {
    val installed: Boolean get() = installedBytes > 0
}

class LocalModelAssetStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = checkNotNull(appContext.getExternalFilesDir("models")) { "外部私有存储当前不可用" }.apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<Map<String, ModelAssetStatus>> = _status

    fun statusOf(id: String): ModelAssetStatus = _status.value[id] ?: ModelAssetStatus(LocalModels.byId(id))
    fun modelFile(id: String): File = File(assetDirectory(id), "model.gguf")
    fun usageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun assetDirectory(id: String): File = File(root, LocalModels.byId(id).id).apply { mkdirs() }

    suspend fun requireReady(id: String): File = withContext(Dispatchers.IO) {
        val variant = LocalModels.byId(id)
        val file = modelFile(id)
        if (!file.isFile) fail(variant, "尚未导入或下载 ${variant.quantization} 模型")
        if (file.length() != variant.expectedBytes) {
            fail(variant, "模型大小校验失败，请重新导入或下载")
        }
        if (sha256(file) != variant.expectedSha256) {
            fail(variant, "模型 SHA256 校验失败，已拒绝加载")
        }
        File(assetDirectory(id), ".verified").writeText(variant.expectedSha256)
        update(variant) {
            it.copy(transferState = ModelTransferState.READY, installedBytes = file.length(), verified = true, error = null)
        }
        file
    }

    suspend fun import(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val variant = LocalModels.byId(id)
        cancelled.remove(id)
        update(variant) { it.copy(transferState = ModelTransferState.IMPORTING, transferredBytes = 0, error = null) }
        val temp = File(assetDirectory(id), "model.importing")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp, false).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        checkNotCancelled(id)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        update(variant) { it.copy(transferredBytes = temp.length()) }
                    }
                }
            } ?: throw IllegalArgumentException("无法读取所选模型文件")
            finishInstall(variant, temp)
        } catch (error: Throwable) {
            temp.delete()
            fail(variant, if (id in cancelled) "导入已取消" else "导入失败：${error.message ?: error.javaClass.simpleName}")
            throw error
        }
    }

    suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val variant = LocalModels.byId(id)
        cancelled.remove(id)
        update(variant) { it.copy(transferState = ModelTransferState.DOWNLOADING, transferredBytes = 0, error = null) }
        val temp = File(assetDirectory(id), "model.download")
        try {
            if (temp.length() > variant.expectedBytes) temp.delete()
            if (temp.length() == variant.expectedBytes) {
                finishInstall(variant, temp)
                return@withContext
            }
            val resume = temp.length().coerceAtLeast(0)
            val request = Request.Builder().url(variant.downloadUrl).apply {
                if (resume > 0) header("Range", "bytes=$resume-")
            }.build()
            val call = client.newCall(request)
            calls[id] = call
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("下载 HTTP ${response.code}，请稍后重试或改用本地导入")
                val append = resume > 0 && response.code == 206
                val start = if (append) resume else 0L
                if (!append) temp.delete()
                update(variant) { it.copy(transferredBytes = start) }
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(temp, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var countSinceUpdate = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            checkNotCancelled(id)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            countSinceUpdate += count
                            if (countSinceUpdate >= 4 * 1024 * 1024) {
                                update(variant) { it.copy(transferredBytes = temp.length()) }
                                countSinceUpdate = 0
                            }
                        }
                    }
                } ?: throw IllegalStateException("下载响应为空")
            }
            finishInstall(variant, temp)
        } catch (error: Throwable) {
            fail(variant, if (id in cancelled) "下载已取消，已保留可续传文件" else "下载失败：${error.message ?: error.javaClass.simpleName}。可重试续传或改用本地导入")
            throw error
        } finally {
            calls.remove(id)
        }
    }

    fun cancel(id: String) {
        cancelled.add(id)
        calls.remove(id)?.cancel()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        cancel(id)
        assetDirectory(id).deleteRecursively()
        val variant = LocalModels.byId(id)
        update(variant) { ModelAssetStatus(variant) }
    }

    private fun finishInstall(variant: LocalModelVariant, temp: File) {
        checkNotCancelled(variant.id)
        if (!temp.isFile || temp.length() != variant.expectedBytes) {
            throw IllegalArgumentException("模型大小不符合 ${variant.quantization} 登记值")
        }
        if (sha256(temp) != variant.expectedSha256) throw IllegalArgumentException("SHA256 校验失败，已拒绝导入")
        val target = modelFile(variant.id)
        if (target.exists() && !target.delete()) throw IllegalStateException("无法替换旧模型")
        if (!temp.renameTo(target)) throw IllegalStateException("无法保存已校验模型")
        File(assetDirectory(variant.id), ".verified").writeText(variant.expectedSha256)
        update(variant) {
            it.copy(
                transferState = ModelTransferState.READY,
                installedBytes = target.length(),
                verified = true,
                transferredBytes = target.length(),
                error = null,
            )
        }
    }

    private fun initialStatus(): Map<String, ModelAssetStatus> = LocalModels.all.associate { variant ->
        val file = modelFile(variant.id)
        val marker = File(assetDirectory(variant.id), ".verified")
        val validMarker = marker.isFile && runCatching { marker.readText().trim() == variant.expectedSha256 }.getOrDefault(false)
        variant.id to ModelAssetStatus(
            variant = variant,
            transferState = if (file.isFile && validMarker) ModelTransferState.READY else ModelTransferState.NOT_INSTALLED,
            installedBytes = if (file.isFile) file.length() else 0,
            verified = file.isFile && validMarker && file.length() == variant.expectedBytes,
        )
    }

    private fun checkNotCancelled(id: String) {
        if (id in cancelled) throw kotlinx.coroutines.CancellationException("用户取消")
    }

    private fun fail(variant: LocalModelVariant, message: String) {
        update(variant) { it.copy(transferState = ModelTransferState.ERROR, error = message) }
        throw IllegalStateException(message)
    }

    private inline fun update(variant: LocalModelVariant, transform: (ModelAssetStatus) -> ModelAssetStatus) {
        val current = _status.value.toMutableMap()
        current[variant.id] = transform(current[variant.id] ?: ModelAssetStatus(variant))
        _status.value = current
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
