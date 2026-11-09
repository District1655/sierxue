package com.sierxue.english.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sierxue.english.domain.AppSettings
import com.sierxue.english.domain.ChatConfig
import com.sierxue.english.domain.TtsConfig
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val securePrefs = EncryptedSharedPreferences.create(
        context, "secure_api_keys", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val keyVersion = MutableStateFlow(0)

    init {
        migrateLegacyKey(CHAT_KEY, chatKeyName("chat_default"))
        migrateLegacyKey(TTS_KEY, ttsKeyName("tts_default"))
    }

    val settings: Flow<AppSettings> = combine(context.settingsStore.data, keyVersion) { p, _ ->
        val chatEndpoints = parseChatEndpoints(p[CHAT_ENDPOINTS]) ?: listOf(
            ChatConfig(
                enabled = p[CHAT_ENABLED] ?: false,
                baseUrl = p[CHAT_URL] ?: "https://api.openai.com/v1",
                model = p[CHAT_MODEL] ?: "gpt-4o-mini",
                hasKey = hasChatKey("chat_default"),
            ),
        ).map { it.copy(hasKey = hasChatKey(it.id)) }
        val ttsEndpoints = parseTtsEndpoints(p[TTS_ENDPOINTS]) ?: listOf(
            TtsConfig(
                enabled = p[TTS_ENABLED] ?: false,
                baseUrl = p[TTS_URL] ?: "https://api.openai.com/v1",
                model = p[TTS_MODEL] ?: "tts-1",
                voice = p[TTS_VOICE] ?: "alloy",
                format = p[TTS_FORMAT] ?: "mp3",
                hasKey = hasTtsKey("tts_default"),
            ),
        ).map { it.copy(hasKey = hasTtsKey(it.id)) }
        AppSettings(
            bookId = p[BOOK] ?: "BOOK1",
            lastUnitId = p[LAST_UNIT] ?: "B1L01",
            speechRate = (p[SPEECH_RATE] ?: 1.0f).coerceIn(0.7f, 1.3f),
            assistantSpeechRate = (p[ASSISTANT_RATE] ?: 0.9f).coerceIn(0.7f, 1.3f),
            assistantEnabled = p[ASSISTANT_ENABLED] ?: true,
            preferredTtsEngine = p[TTS_ENGINE_PACKAGE].orEmpty(),
            ttsServiceQueryCount = p[TTS_SERVICE_QUERY_COUNT] ?: -1,
            chat = selectChat(chatEndpoints),
            tts = selectTts(ttsEndpoints),
            chatEndpoints = chatEndpoints,
            ttsEndpoints = ttsEndpoints,
        )
    }

    suspend fun setBook(bookId: String, firstUnitId: String) = context.settingsStore.edit {
        it[BOOK] = bookId
        it[LAST_UNIT] = firstUnitId
    }
    suspend fun setLastUnit(unitId: String) = context.settingsStore.edit { it[LAST_UNIT] = unitId }
    suspend fun setSpeechRate(value: Float) = context.settingsStore.edit { it[SPEECH_RATE] = value.coerceIn(0.7f, 1.3f) }
    suspend fun setAssistantRate(value: Float) = context.settingsStore.edit { it[ASSISTANT_RATE] = value.coerceIn(0.7f, 1.3f) }
    suspend fun setAssistantEnabled(value: Boolean) = context.settingsStore.edit { it[ASSISTANT_ENABLED] = value }
    suspend fun setTtsEnginePackage(value: String) = context.settingsStore.edit { it[TTS_ENGINE_PACKAGE] = value.trim() }
    suspend fun setTtsServiceQueryCount(value: Int) = context.settingsStore.edit { it[TTS_SERVICE_QUERY_COUNT] = value }

    suspend fun saveChatEndpoints(endpoints: List<ChatConfig>, keyInputs: Map<String, String> = emptyMap()) {
        require(endpoints.size in 1..5) { "对话端点数量为 1～5 个" }
        endpoints.forEach { validateEndpoint(it.id, it.baseUrl, it.model) }
        require(endpoints.all { it.requestsPerMinute in 10..60 }) { "每分钟请求上限需在 10～60 之间" }
        val normalized = normalizeChatPrimary(endpoints)
        context.settingsStore.edit { it[CHAT_ENDPOINTS] = chatJson(normalized) }
        keyInputs.forEach { (id, key) -> if (key.isNotBlank()) securePrefs.edit().putString(chatKeyName(id), key.trim()).apply() }
        keyVersion.value += 1
    }

    suspend fun saveTtsEndpoints(endpoints: List<TtsConfig>, keyInputs: Map<String, String> = emptyMap()) {
        require(endpoints.size in 1..3) { "TTS 端点数量为 1～3 个" }
        endpoints.forEach {
            validateEndpoint(it.id, it.baseUrl, it.model)
            require(it.voice.isNotBlank()) { "音色不能为空" }
            require(it.format in setOf("mp3", "wav")) { "音频格式仅支持 mp3 或 wav" }
            require(it.requestsPerMinute in 10..60) { "每分钟请求上限需在 10～60 之间" }
        }
        val normalized = normalizeTtsPrimary(endpoints)
        context.settingsStore.edit { it[TTS_ENDPOINTS] = ttsJson(normalized) }
        keyInputs.forEach { (id, key) -> if (key.isNotBlank()) securePrefs.edit().putString(ttsKeyName(id), key.trim()).apply() }
        keyVersion.value += 1
    }

    /** 旧设置 API 保留给深度解析等现有页面。 */
    suspend fun saveChat(enabled: Boolean, baseUrl: String, model: String, keyInput: String) {
        val current = settings.first().chatEndpoints.firstOrNull() ?: ChatConfig()
        val updated = current.copy(enabled = enabled, baseUrl = baseUrl.trim().trimEnd('/'), model = model.trim())
        saveChatEndpoints(listOf(updated), mapOf(updated.id to keyInput))
    }

    suspend fun saveTts(enabled: Boolean, baseUrl: String, model: String, voice: String, format: String, keyInput: String) {
        val current = settings.first().ttsEndpoints.firstOrNull() ?: TtsConfig()
        val updated = current.copy(enabled = enabled, baseUrl = baseUrl.trim().trimEnd('/'), model = model.trim(), voice = voice.trim(), format = format)
        saveTtsEndpoints(listOf(updated), mapOf(updated.id to keyInput))
    }

    fun chatKey(endpointId: String = "chat_default"): String = securePrefs.getString(chatKeyName(endpointId), null).orEmpty()
    fun ttsKey(endpointId: String = "tts_default"): String = securePrefs.getString(ttsKeyName(endpointId), null).orEmpty()
    fun hasChatKey(endpointId: String): Boolean = securePrefs.contains(chatKeyName(endpointId))
    fun hasTtsKey(endpointId: String): Boolean = securePrefs.contains(ttsKeyName(endpointId))

    fun clearChatKey(endpointId: String = "chat_default") {
        securePrefs.edit().remove(chatKeyName(endpointId)).apply()
        keyVersion.value += 1
    }

    fun clearTtsKey(endpointId: String = "tts_default") {
        securePrefs.edit().remove(ttsKeyName(endpointId)).apply()
        keyVersion.value += 1
    }

    private fun migrateLegacyKey(oldKey: String, newKey: String) {
        val legacy = securePrefs.getString(oldKey, null)
        if (!legacy.isNullOrBlank() && !securePrefs.contains(newKey)) {
            securePrefs.edit().putString(newKey, legacy).remove(oldKey).apply()
        }
    }

    private fun selectChat(items: List<ChatConfig>): ChatConfig =
        items.firstOrNull { it.primary && it.enabled } ?: items.firstOrNull { it.enabled } ?: items.firstOrNull() ?: ChatConfig()

    private fun selectTts(items: List<TtsConfig>): TtsConfig =
        items.firstOrNull { it.primary && it.enabled } ?: items.firstOrNull { it.enabled } ?: items.firstOrNull() ?: TtsConfig()

    private fun normalizeChatPrimary(items: List<ChatConfig>): List<ChatConfig> {
        val primaryIndex = items.indexOfFirst { it.primary && it.enabled }.let { if (it >= 0) it else items.indexOfFirst { it.enabled } }
        return items.mapIndexed { index, item -> item.copy(primary = index == primaryIndex) }
    }

    private fun normalizeTtsPrimary(items: List<TtsConfig>): List<TtsConfig> {
        val primaryIndex = items.indexOfFirst { it.primary && it.enabled }.let { if (it >= 0) it else items.indexOfFirst { it.enabled } }
        return items.mapIndexed { index, item -> item.copy(primary = index == primaryIndex) }
    }

    private fun parseChatEndpoints(raw: String?): List<ChatConfig>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ChatConfig(
                    id = item.getString("id"), name = item.optString("name"),
                    enabled = item.optBoolean("enabled"), primary = item.optBoolean("primary", index == 0),
                    baseUrl = item.getString("baseUrl"), model = item.getString("model"),
                    hasKey = hasChatKey(item.getString("id")), requestsPerMinute = item.optInt("rpm", 20),
                )
            }
        }.getOrNull()
    }

    private fun parseTtsEndpoints(raw: String?): List<TtsConfig>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TtsConfig(
                    id = item.getString("id"), name = item.optString("name"),
                    enabled = item.optBoolean("enabled"), primary = item.optBoolean("primary", index == 0),
                    baseUrl = item.getString("baseUrl"), model = item.getString("model"),
                    voice = item.optString("voice", "alloy"), format = item.optString("format", "mp3"),
                    hasKey = hasTtsKey(item.getString("id")), requestsPerMinute = item.optInt("rpm", 20),
                )
            }
        }.getOrNull()
    }

    private fun chatJson(items: List<ChatConfig>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().put("id", item.id).put("name", item.name).put("enabled", item.enabled)
                .put("primary", item.primary).put("baseUrl", item.baseUrl).put("model", item.model).put("rpm", item.requestsPerMinute))
        }
    }.toString()

    private fun ttsJson(items: List<TtsConfig>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().put("id", item.id).put("name", item.name).put("enabled", item.enabled)
                .put("primary", item.primary).put("baseUrl", item.baseUrl).put("model", item.model)
                .put("voice", item.voice).put("format", item.format).put("rpm", item.requestsPerMinute))
        }
    }.toString()

    private fun validateEndpoint(id: String, baseUrl: String, model: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "端点 ID 格式不正确" }
        require(model.isNotBlank()) { "模型名不能为空" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "BaseURL 必须是有效的 HTTPS 地址" }
    }

    private fun chatKeyName(id: String) = "openai_chat_key_$id"
    private fun ttsKeyName(id: String) = "openai_tts_key_$id"

    private companion object {
        val BOOK = stringPreferencesKey("book")
        val LAST_UNIT = stringPreferencesKey("last_unit")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val ASSISTANT_RATE = floatPreferencesKey("assistant_rate")
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val TTS_SERVICE_QUERY_COUNT = intPreferencesKey("tts_service_query_count")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_URL = stringPreferencesKey("chat_url")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_URL = stringPreferencesKey("tts_url")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_FORMAT = stringPreferencesKey("tts_format")
        val CHAT_ENDPOINTS = stringPreferencesKey("chat_endpoints")
        val TTS_ENDPOINTS = stringPreferencesKey("tts_endpoints")
        const val CHAT_KEY = "openai_chat_key"
        const val TTS_KEY = "openai_tts_key"
    }
}
