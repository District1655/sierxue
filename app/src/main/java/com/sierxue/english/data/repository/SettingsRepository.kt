package com.sierxue.english.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "secure_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val keyState = MutableStateFlow(
        Pair(securePrefs.contains(CHAT_KEY), securePrefs.contains(TTS_KEY)),
    )

    val settings: Flow<AppSettings> = combine(context.settingsStore.data, keyState) { p, keys ->
        AppSettings(
            bookId = p[BOOK] ?: "BOOK1",
            lastUnitId = p[LAST_UNIT] ?: "B1L01",
            speechRate = (p[SPEECH_RATE] ?: 1.0f).coerceIn(0.7f, 1.3f),
            assistantSpeechRate = (p[ASSISTANT_RATE] ?: 0.9f).coerceIn(0.7f, 1.3f),
            assistantEnabled = p[ASSISTANT_ENABLED] ?: true,
            chat = ChatConfig(
                enabled = p[CHAT_ENABLED] ?: false,
                baseUrl = p[CHAT_URL] ?: "https://api.openai.com/v1",
                model = p[CHAT_MODEL] ?: "gpt-4o-mini",
                hasKey = keys.first,
            ),
            tts = TtsConfig(
                enabled = p[TTS_ENABLED] ?: false,
                baseUrl = p[TTS_URL] ?: "https://api.openai.com/v1",
                model = p[TTS_MODEL] ?: "tts-1",
                voice = p[TTS_VOICE] ?: "alloy",
                format = p[TTS_FORMAT] ?: "mp3",
                hasKey = keys.second,
            ),
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

    suspend fun saveChat(enabled: Boolean, baseUrl: String, model: String, keyInput: String) {
        validateHttps(baseUrl)
        require(model.isNotBlank()) { "模型名不能为空" }
        context.settingsStore.edit {
            it[CHAT_ENABLED] = enabled
            it[CHAT_URL] = baseUrl.trim().trimEnd('/')
            it[CHAT_MODEL] = model.trim()
        }
        if (keyInput.isNotBlank()) securePrefs.edit().putString(CHAT_KEY, keyInput.trim()).apply()
        refreshKeyState()
    }

    suspend fun saveTts(enabled: Boolean, baseUrl: String, model: String, voice: String, format: String, keyInput: String) {
        validateHttps(baseUrl)
        require(model.isNotBlank()) { "模型名不能为空" }
        require(voice.isNotBlank()) { "音色不能为空" }
        require(format in setOf("mp3", "wav")) { "音频格式仅支持 mp3 或 wav" }
        context.settingsStore.edit {
            it[TTS_ENABLED] = enabled
            it[TTS_URL] = baseUrl.trim().trimEnd('/')
            it[TTS_MODEL] = model.trim()
            it[TTS_VOICE] = voice.trim()
            it[TTS_FORMAT] = format
        }
        if (keyInput.isNotBlank()) securePrefs.edit().putString(TTS_KEY, keyInput.trim()).apply()
        refreshKeyState()
    }

    fun chatKey(): String = securePrefs.getString(CHAT_KEY, null).orEmpty()
    fun ttsKey(): String = securePrefs.getString(TTS_KEY, null).orEmpty()

    fun clearChatKey() {
        securePrefs.edit().remove(CHAT_KEY).apply()
        refreshKeyState()
    }

    fun clearTtsKey() {
        securePrefs.edit().remove(TTS_KEY).apply()
        refreshKeyState()
    }

    private fun refreshKeyState() {
        keyState.value = Pair(securePrefs.contains(CHAT_KEY), securePrefs.contains(TTS_KEY))
    }

    private fun validateHttps(value: String) {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "BaseURL 必须是有效的 HTTPS 地址" }
    }

    private companion object {
        val BOOK = stringPreferencesKey("book")
        val LAST_UNIT = stringPreferencesKey("last_unit")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val ASSISTANT_RATE = floatPreferencesKey("assistant_rate")
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_URL = stringPreferencesKey("chat_url")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_URL = stringPreferencesKey("tts_url")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_FORMAT = stringPreferencesKey("tts_format")
        const val CHAT_KEY = "openai_chat_key"
        const val TTS_KEY = "openai_tts_key"
    }
}
