package com.sierxue.english.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sierxue.english.assistant.AssistantRepository
import com.sierxue.english.data.local.AbilityScoreEntity
import com.sierxue.english.data.local.BookEntity
import com.sierxue.english.data.local.LessonSectionEntity
import com.sierxue.english.data.local.ReviewItemEntity
import com.sierxue.english.data.local.SectionProgressEntity
import com.sierxue.english.data.local.UnitEntity
import com.sierxue.english.data.repository.LessonBundle
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.AbilityScores
import com.sierxue.english.domain.ReviewType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val study: StudyRepository,
    settings: SettingsRepository,
    private val assistant: AssistantRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            settings.settings.flatMapLatest { config ->
                val course = combine(
                    study.observeUnits(config.bookId),
                    study.observeAllProgress(),
                    study.observeDueReviews(),
                    study.observeAbility(config.bookId),
                ) { units, progress, reviews, ability ->
                    val continueUnit = study.continueUnit(config.bookId, config.lastUnitId)
                    val completed = continueUnit?.let { unit -> progress.count { it.unitId == unit.id && it.completed } } ?: 0
                    HomeState(
                        book = study.book(config.bookId),
                        continueUnit = continueUnit,
                        completedSections = completed,
                        dueWords = reviews.count { it.type == ReviewType.WORD.name },
                        duePatterns = reviews.count { it.type == ReviewType.PATTERN.name },
                        dueTotal = reviews.size,
                        ability = ability?.let { AbilityScores(it.pronunciation, it.grammar, it.vocabulary, it.speaking, it.reading) } ?: AbilityScores(),
                    )
                }
                combine(course, study.observeWrongCount(), study.observeLatestExam()) { base, wrong, exam ->
                    base.copy(wrongCount = wrong, latestScore = exam?.score)
                }
            }.collect { _state.value = it }
        }
    }

    fun clearConversations() = viewModelScope.launch { assistant.clear() }
}

data class HomeState(
    val book: BookEntity? = null,
    val continueUnit: UnitEntity? = null,
    val completedSections: Int = 0,
    val dueWords: Int = 0,
    val duePatterns: Int = 0,
    val dueTotal: Int = 0,
    val ability: AbilityScores = AbilityScores(),
    val wrongCount: Int = 0,
    val latestScore: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val study: StudyRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state

    init {
        viewModelScope.launch {
            settings.settings.flatMapLatest { config ->
                combine(study.observeUnits(config.bookId), study.observeAllProgress()) { units, progress ->
                    CatalogState(
                        book = study.book(config.bookId),
                        units = units,
                        completedByUnit = progress.filter { it.completed }.groupBy { it.unitId }.mapValues { it.value.size },
                        currentBookId = config.bookId,
                    )
                }
            }.collect { state ->
                _state.value = state.copy(books = _state.value.books)
            }
        }
        viewModelScope.launch { study.observeBooks().collect { _state.value = _state.value.copy(books = it) } }
    }

    fun selectBook(bookId: String) = viewModelScope.launch {
        val first = study.units(bookId).firstOrNull() ?: return@launch
        settings.setBook(bookId, first.id)
    }
}

data class CatalogState(
    val books: List<BookEntity> = emptyList(),
    val book: BookEntity? = null,
    val currentBookId: String = "BOOK1",
    val units: List<UnitEntity> = emptyList(),
    val completedByUnit: Map<String, Int> = emptyMap(),
)

class LessonViewModel(private val study: StudyRepository, private val unitId: String) : ViewModel() {
    private val _state = MutableStateFlow(LessonState())
    val state: StateFlow<LessonState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(bundle = study.lessonBundle(unitId), loading = false)
        }
        viewModelScope.launch {
            study.observeSectionProgress(unitId).collect { _state.value = _state.value.copy(progress = it) }
        }
    }
}

data class LessonState(
    val bundle: LessonBundle? = null,
    val progress: List<SectionProgressEntity> = emptyList(),
    val loading: Boolean = true,
)

class SectionViewModel(
    private val study: StudyRepository,
    private val settings: SettingsRepository,
    private val sectionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SectionState())
    val state: StateFlow<SectionState> = _state

    init {
        viewModelScope.launch {
            val section = study.section(sectionId)
            val bundle = section?.let { study.lessonBundle(it.unitId) }
            bundle?.let { settings.setLastUnit(it.unit.id) }
            _state.value = SectionState(bundle = bundle, section = section, loading = false)
        }
    }

    fun complete(skipped: Boolean = false) = viewModelScope.launch {
        val lessonDone = study.completeSection(sectionId, skipped = skipped)
        _state.value = _state.value.copy(message = if (lessonDone) "本课五段已完成，复习内容已加入队列" else "本段已完成")
    }

    fun recordSpeaking(
        standard: String,
        recognized: String,
        score: Int,
        issues: List<String>,
        duration: Long,
        wpm: Int,
    ) = viewModelScope.launch {
        val section = _state.value.section ?: return@launch
        study.saveSpeakingAttempt(
            unitId = section.unitId,
            sectionId = sectionId,
            standardSentence = standard,
            recognizedText = recognized,
            score = score,
            issuesJson = org.json.JSONArray(issues).toString(),
            durationMillis = duration,
            wordsPerMinute = wpm,
        )
        _state.value = _state.value.copy(message = "跟读评分已保存：$score 分")
    }
}

data class SectionState(
    val bundle: LessonBundle? = null,
    val section: LessonSectionEntity? = null,
    val loading: Boolean = true,
    val message: String? = null,
)
