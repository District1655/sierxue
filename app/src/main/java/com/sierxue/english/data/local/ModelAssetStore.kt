package com.sierxue.english.data.local

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** 模型与语音资源的统一目录抽象；阶段 A 不执行联网下载。 */
interface ModelAssetStore {
    suspend fun download(id: String, url: String, expectedSha256: String): File
    suspend fun verify(file: File, expectedSha256: String): Boolean
    suspend fun delete(id: String)
    fun usageBytes(): Long
    fun assetDirectory(id: String): File
}

class LocalModelAssetStore(context: Context) : ModelAssetStore {
    private val root = File(context.filesDir, "model_assets").apply { mkdirs() }

    override suspend fun download(id: String, url: String, expectedSha256: String): File {
        throw UnsupportedOperationException("阶段 A 未启用模型下载")
    }

    override suspend fun verify(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || expectedSha256.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, true)
    }

    override suspend fun delete(id: String) {
        assetDirectory(id).deleteRecursively()
    }

    override fun usageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    override fun assetDirectory(id: String): File = File(root, id).apply { mkdirs() }
}
