package com.sierxue.english.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sierxue.english.data.local.ExamEntity
import com.sierxue.english.data.local.ReviewItemEntity
import com.sierxue.english.data.remote.ErrorClassifier
import com.sierxue.english.data.remote.OpenAiChatClient
import com.sierxue.english.data.remote.StableRequestRunner
import com.sierxue.english.data.repository.ExamReport
import com.sierxue.english.data.repository.SettingsRepository
import com.sierxue.english.data.repository.StudyRepository
import com.sierxue.english.domain.AssessmentScope
import com.sierxue.english.domain.PracticeMode
import com.sierxue.english.domain.Question
import com.sierxue.english.domain.WrongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PracticeViewModel(
    private val study: StudyRepository,
    private val settings: SettingsRepository,
    private val chatClient: OpenAiChatClient,
    private val runner: StableRequestRunner,
    private val unitId: String?,
    private val sectionId: String?,
    private val wrongQuestionId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = _state

    init {
        viewModelScope.launch {
            val questions = when {
                wrongQuestionId != null -> listOfNotNull(study.question(wrongQuestionId))
                sectionId != null -> study.sectionQuestions(sectionId)
                unitId != null -> study.lessonQuestions(unitId)
                else -> emptyList()
            }
            unitId?.let { settings.setLastUnit(it) }
            _state.value = PracticeState(questions = questions, loading = false)
        }
    }

    fun select(answers: List<String>) {
        val id = currentQuestion()?.id ?: return
        if (_state.value.submitted.contains(id)) return
        _state.value = _state.value.copy(answers = _state.value.answers + (id to answers))
    }

    fun submit() = viewModelScope.launch {
        val question = currentQuestion() ?: return@launch
        val current = _state.value
        if (question.id in current.submitted) return@launch
        val answers = current.answers[question.id].orEmpty()
        val correct = study.recordPractice(question, answers, if (wrongQuestionId != null) PracticeMode.WRONG_REVIEW else PracticeMode.PRACTICE)
        _state.value = _state.value.copy(
            submitted = _state.value.submitted + question.id,
            correct = _state.value.correct + (question.id to correct),
            correctCount = _state.value.correctCount + if (correct) 1 else 0,
        )
    }

    fun next() {
        val current = _state.value
        if (current.index >= current.questions.lastIndex) {
            _state.value = current.copy(finished = true, aiText = null, error = null)
        } else {
            _state.value = current.copy(index = current.index + 1, aiText = null, error = null)
        }
    }

    fun explainWithAi() = viewModelScope.launch {
        val question = currentQuestion() ?: return@launch
        val endpoints = settings.settings.first().chatEndpoints.filter { it.enabled && it.hasKey }.sortedByDescending { it.primary }
        if (endpoints.isEmpty()) {
            _state.value = _state.value.copy(error = "请先在设置中开启并配置对话端点")
            return@launch
        }
        _state.value = _state.value.copy(aiLoading = true, error = null)
        try {
            val text = runner.execute(
                endpoints = endpoints,
                idOf = { it.id }, enabledOf = { it.enabled && it.hasKey }, rpmOf = { it.requestsPerMinute },
                userInitiated = true,
                block = { endpoint -> chatClient.explain(endpoint, settings.chatKey(endpoint.id), question, _state.value.answers[question.id].orEmpty()) },
            )
            _state.value = _state.value.copy(aiLoading = false, aiText = text)
        } catch (error: Exception) {
            _state.value = _state.value.copy(aiLoading = false, error = "AI 不可用，已保留本地解析：${ErrorClassifier.userMessage(error)}")
        }
    }

    private fun currentQuestion(): Question? = _state.value.questions.getOrNull(_state.value.index)
}

data class PracticeState(
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val answers: Map<String, List<String>> = emptyMap(),
    val submitted: Set<String> = emptySet(),
    val correct: Map<String, Boolean> = emptyMap(),
    val correctCount: Int = 0,
    val finished: Boolean = false,
    val loading: Boolean = true,
    val aiLoading: Boolean = false,
    val aiText: String? = null,
    val error: String? = null,
)

class WrongBookViewModel(private val study: StudyRepository) : ViewModel() {
    val items: StateFlow<List<WrongItem>> = study.observeWrongItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun remove(questionId: String) = viewModelScope.launch { study.removeWrong(questionId) }
}

class ReviewViewModel(private val study: StudyRepository) : ViewModel() {
    val items: StateFlow<List<ReviewItemEntity>> = study.observeDueReviews().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun answer(item: ReviewItemEntity, remembered: Boolean) = viewModelScope.launch { study.answerReview(item, remembered) }
}

class ExamViewModel(private val study: StudyRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExamState())
    val state: StateFlow<ExamState> = _state
    private var timerStarted = false

    fun create(bookId: String, lessonIds: List<String>, scope: AssessmentScope) = viewModelScope.launch {
        try {
            val id = study.createExam(bookId, lessonIds, scope)
            _state.value = _state.value.copy(createdExamId = id, error = null)
        } catch (error: Exception) {
            _state.value = _state.value.copy(error = error.message)
        }
    }

    fun load(examId: String) {
        if (_state.value.exam?.id == examId) return
        viewModelScope.launch {
            val exam = study.exam(examId) ?: return@launch
            val questions = study.examQuestions(examId)
            _state.value = ExamState(exam = exam, questions = questions, remainingSeconds = remaining(exam))
            if (exam.status == "SUBMITTED") _state.value = _state.value.copy(submittedExamId = examId)
            startTimer()
        }
    }

    fun select(answers: List<String>) {
        val question = _state.value.questions.getOrNull(_state.value.index) ?: return
        _state.value = _state.value.copy(answers = _state.value.answers + (question.id to answers))
    }

    fun jump(index: Int) {
        if (index in _state.value.questions.indices) _state.value = _state.value.copy(index = index)
    }

    fun submit() = viewModelScope.launch {
        val exam = _state.value.exam ?: return@launch
        if (_state.value.submitting || exam.status != "IN_PROGRESS") return@launch
        _state.value = _state.value.copy(submitting = true)
        study.submitExam(exam.id, _state.value.answers)
        _state.value = _state.value.copy(submitting = false, submittedExamId = exam.id)
    }

    private fun startTimer() {
        if (timerStarted) return
        timerStarted = true
        viewModelScope.launch {
            while (true) {
                val exam = _state.value.exam ?: break
                if (exam.status != "IN_PROGRESS" || _state.value.submittedExamId != null) break
                val left = remaining(exam)
                _state.value = _state.value.copy(remainingSeconds = left)
                if (left <= 0) {
                    submit()
                    break
                }
                delay(1_000)
            }
        }
    }

    private fun remaining(exam: ExamEntity): Int =
        ((exam.startedAt + exam.durationSeconds * 1_000L - System.currentTimeMillis()) / 1_000L).toInt().coerceAtLeast(0)
}

data class ExamState(
    val exam: ExamEntity? = null,
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val answers: Map<String, List<String>> = emptyMap(),
    val remainingSeconds: Int = 0,
    val submitting: Boolean = false,
    val createdExamId: String? = null,
    val submittedExamId: String? = null,
    val error: String? = null,
)

class StatsViewModel(study: StudyRepository) : ViewModel() {
    val exams: StateFlow<List<ExamEntity>> = study.observeExams().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

class ReportViewModel(study: StudyRepository, examId: String) : ViewModel() {
    private val _report = MutableStateFlow<ExamReport?>(null)
    val report: StateFlow<ExamReport?> = _report
    init { viewModelScope.launch { _report.value = study.examReport(examId) } }
}
