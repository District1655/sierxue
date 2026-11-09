package com.sierxue.english.di

import android.content.Context
import com.sierxue.english.assistant.AssistantContextHolder
import com.sierxue.english.assistant.AssistantRepository
import com.sierxue.english.data.local.AppDatabase
import com.sierxue.english.data.local.LocalModelAssetStore
import com.sierxue.english.data.local.ModelAssetStore
import com.sierxue.english.data.local.SeedImporter
import com.sierxue.english.data.remote.ChatProviderRouter
import com.sierxue.english.data.remote.EndpointHealthStore
import com.sierxue.english.data.remote.OfflineFallbackProvider
import com.sierxue.english.data.remote.OpenAiChatClient
import com.sierxue.english.data.remote.OpenAiTtsClient
import com.sierxue.english.data.remote.RemoteChatProvider
import com.sierxue.english.data.remote.StableRequestRunner
import com.sierxue.english.data.remote.TokenBucketRegistry
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.speech.OnlineTtsEngine
import com.sierxue.english.speech.SpeechManager
import com.sierxue.english.speech.SystemTtsEngine
import com.sierxue.english.update.UpdateController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)
    val settings = SettingsRepository(appContext)
    val study = StudyRepository(database)
    val chatClient = OpenAiChatClient()
    val ttsClient = OpenAiTtsClient()
    val healthStore = EndpointHealthStore(appContext)
    val tokenBuckets = TokenBucketRegistry()
    val requestRunner = StableRequestRunner(healthStore, tokenBuckets)
    val systemTts = SystemTtsEngine(appContext, settings)
    private val onlineTts = OnlineTtsEngine(appContext, settings, ttsClient, requestRunner)
    val speech = SpeechManager(settings, systemTts, onlineTts)
    val assistantContext = AssistantContextHolder()
    val updateController = UpdateController(appContext)
    val modelAssetStore: ModelAssetStore = LocalModelAssetStore(appContext)
    val ttsCacheDir: File = File(appContext.filesDir, "tts")

    val chatRouter = ChatProviderRouter(
        settings,
        RemoteChatProvider(settings, chatClient),
        OfflineFallbackProvider(),
        requestRunner,
    )
    val assistant = AssistantRepository(database, study, chatRouter)
    val viewModelFactory = AppViewModelFactory(this)

    private val _initialization = MutableStateFlow<Initialization>(Initialization.Loading)
    val initialization: StateFlow<Initialization> = _initialization

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SeedImporter(appContext, database).importIfNeeded()
                _initialization.value = Initialization.Ready
            } catch (error: Exception) {
                _initialization.value = Initialization.Failed(error.message ?: "课程导入失败")
            }
        }
    }
}

sealed interface Initialization {
    data object Loading : Initialization
    data object Ready : Initialization
    data class Failed(val message: String) : Initialization
}
