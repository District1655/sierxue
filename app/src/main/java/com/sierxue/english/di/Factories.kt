package com.sierxue.english.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sierxue.english.ui.viewmodel.AssistantViewModel
import com.sierxue.english.ui.viewmodel.CatalogViewModel
import com.sierxue.english.ui.viewmodel.ExamViewModel
import com.sierxue.english.ui.viewmodel.HomeViewModel
import com.sierxue.english.ui.viewmodel.ReviewViewModel
import com.sierxue.english.ui.viewmodel.SettingsViewModel
import com.sierxue.english.ui.viewmodel.StatsViewModel
import com.sierxue.english.ui.viewmodel.WrongBookViewModel

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val value = when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(container.study, container.settings, container.assistant)
            CatalogViewModel::class.java -> CatalogViewModel(container.study, container.settings)
            WrongBookViewModel::class.java -> WrongBookViewModel(container.study)
            ReviewViewModel::class.java -> ReviewViewModel(container.study)
            ExamViewModel::class.java -> ExamViewModel(container.study)
            StatsViewModel::class.java -> StatsViewModel(container.study)
            SettingsViewModel::class.java -> SettingsViewModel(
                container.settings, container.study, container.assistant,
                container.chatClient, container.ttsClient, container.ttsCacheDir, container.speech, container.healthStore,
            )
            AssistantViewModel::class.java -> AssistantViewModel(
                container.assistant, container.assistantContext, container.settings, container.speech,
            )
            else -> throw IllegalArgumentException("未知 ViewModel：${modelClass.name}")
        }
        return value as T
    }
}

fun viewModelFactoryOf(creator: () -> ViewModel): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
    }
