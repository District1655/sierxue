package com.sierxue.english.data.repository

import androidx.room.withTransaction
import com.sierxue.english.data.local.AbilityScoreEntity
import com.sierxue.english.data.local.AppDatabase
import com.sierxue.english.data.local.ExamAnswerEntity
import com.sierxue.english.data.local.ExamEntity
import com.sierxue.english.data.local.LessonSectionEntity
import com.sierxue.english.data.local.PassageEntity
import com.sierxue.english.data.local.PracticeRecordEntity
import com.sierxue.english.data.local.QuestionEntity
import com.sierxue.english.data.local.ReviewItemEntity
import com.sierxue.english.data.local.SectionProgressEntity
import com.sierxue.english.data.local.SpeakingAttemptEntity
import com.sierxue.english.data.local.UnitEntity
import com.sierxue.english.data.local.VocabularyEntity
import com.sierxue.english.data.local.WrongQuestionEntity
import com.sierxue.english.domain.AnswerEvaluator
import com.sierxue.english.domain.AnswerOption
import com.sierxue.english.domain.AreaWeakness
import com.sierxue.english.domain.AssessmentScope
import com.sierxue.english.domain.ExamAnswerItem
import com.sierxue.english.domain.ExamAssembler
import com.sierxue.english.domain.LessonSectionType
import com.sierxue.english.domain.PracticeMode
import com.sierxue.english.domain.Question
import com.sierxue.english.domain.QuestionType
import com.sierxue.english.domain.ReviewType
import com.sierxue.english.domain.Vocabulary
import com.sierxue.english.domain.WrongItem
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class StudyRepository(private val db: AppDatabase) {
    private val dao = db.studyDao()

    fun observeBooks() = dao.observeBooks()
    fun observeUnits(bookId: String) = dao.observeUnits(bookId)
    fun observeSectionProgress(unitId: String) = dao.observeSectionProgress(unitId)
    fun observeAllProgress() = dao.observeAllSectionProgress()
    fun observeDueReviews(now: Long = System.currentTimeMillis()) = dao.observeDueReviews(now)
    fun observeAbility(bookId: String) = dao.observeAbility(bookId)
    fun observeWrongCount() = dao.observeWrongCount()
    fun observeLatestExam() = dao.observeLatestSubmittedExam()
    fun observeExams() = dao.observeExams()
    suspend fun book(id: String) = dao.book(id)
    suspend fun unit(id: String) = dao.unit(id)
    suspend fun units(bookId: String) = dao.units(bookId)
    suspend fun section(id: String) = dao.section(id)
    suspend fun vocabularyForUnit(unitId: String) = dao.vocabularyForUnit(unitId)
    suspend fun passageForUnit(unitId: String) = dao.passageForUnit(unitId)

    suspend fun lessonBundle(unitId: String): LessonBundle? {
        val unit = dao.unit(unitId) ?: return null
        return LessonBundle(
            unit = unit,
            book = dao.book(unit.bookId),
            sections = dao.sections(unitId),
            vocabulary = dao.vocabularyForUnit(unitId).map { it.toDomain() },
            passage = dao.passageForUnit(unitId),
        )
    }

    suspend fun continueUnit(bookId: String, lastUnitId: String): UnitEntity? {
        val units = dao.units(bookId)
        if (units.isEmpty()) return null
        val progress = dao.allSectionProgress().filter { it.completed }.map { it.sectionId }.toSet()
        val sectionsByUnit = units.associate { it.id to dao.sections(it.id) }
        fun completed(unit: UnitEntity): Boolean {
            val sections = sectionsByUnit[unit.id].orEmpty()
            return sections.isNotEmpty() && sections.all { it.id in progress }
        }
        val last = units.firstOrNull { it.id == lastUnitId }
        if (last != null && !completed(last)) return last
        return units.firstOrNull { !completed(it) } ?: units.last()
    }

    suspend fun isUnitCompleted(unitId: String): Boolean {
        val sections = dao.sections(unitId)
        if (sections.isEmpty()) return false
        val completed = dao.allSectionProgress().filter { it.completed }.map { it.sectionId }.toSet()
        return sections.all { it.id in completed }
    }

    suspend fun canStartUnit(unitId: String): Boolean {
        val unit = dao.unit(unitId) ?: return false
        if (unit.number <= 1) return true
        val previous = dao.units(unit.bookId).firstOrNull { it.number == unit.number - 1 } ?: return true
        return isUnitCompleted(previous.id)
    }

    suspend fun completeSection(sectionId: String, score: Int? = null, skipped: Boolean = false): Boolean {
        val section = dao.section(sectionId) ?: return false
        dao.putSectionProgress(
            SectionProgressEntity(sectionId, section.unitId, true, skipped, score, System.currentTimeMillis()),
        )
        val unit = dao.unit(section.unitId) ?: return false
        val completed = isUnitCompleted(unit.id)
        if (completed) generateReviews(unit.id)
        updateAbilitySnapshot(unit.bookId)
        return completed
    }

    private suspend fun generateReviews(unitId: String) {
        val now = System.currentTimeMillis()
        val items = mutableListOf<ReviewItemEntity>()
        dao.vocabularyForUnit(unitId).forEach { word ->
            val content = JSONObject()
                .put("text", word.word)
                .put("detail", "${word.phonetic} ${word.meaning}\n${word.collocation}\n${word.example}")
                .put("speak", word.example)
            items += ReviewItemEntity(
                id = "R_${word.id}", unitId = unitId, sectionId = word.sectionId,
                sourceId = word.id, type = ReviewType.WORD.name, content = content.toString(),
                dueAt = now + DAY, stage = 0, proficiency = 0, updatedAt = now,
            )
        }
        val sections = dao.sections(unitId)
        sections.firstOrNull { it.type == LessonSectionType.PATTERN.name }?.let { pattern ->
            val json = JSONObject(pattern.contentJson)
            val text = json.optString("pattern")
            items += ReviewItemEntity(
                id = "R_PATTERN_$unitId", unitId = unitId, sectionId = pattern.id,
                sourceId = pattern.id, type = ReviewType.PATTERN.name,
                content = JSONObject().put("text", text).put("detail", "核心句型").put("speak", text).toString(),
                dueAt = now + DAY, stage = 0, proficiency = 0, updatedAt = now,
            )
        }
        sections.firstOrNull { it.type == LessonSectionType.PRONUNCIATION.name }?.let { pronunciation ->
            val sentences = JSONObject(pronunciation.contentJson).optJSONArray("shadowSentences") ?: JSONArray()
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getString(index)
                items += ReviewItemEntity(
                    id = "R_SHADOW_${unitId}_$index", unitId = unitId, sectionId = pronunciation.id,
                    sourceId = "${pronunciation.id}_$index", type = ReviewType.SHADOWING.name,
                    content = JSONObject().put("text", sentence).put("detail", "跟读句").put("speak", sentence).toString(),
                    dueAt = now + DAY, stage = 0, proficiency = 0, updatedAt = now,
                )
            }
        }
        dao.insertReviews(items)
    }

    suspend fun answerReview(item: ReviewItemEntity, remembered: Boolean) {
        val now = System.currentTimeMillis()
        if (!remembered) {
            dao.putReview(item.copy(stage = 0, proficiency = (item.proficiency - 1).coerceAtLeast(0), dueAt = now + DAY, updatedAt = now))
        } else if (item.stage >= 3) {
            dao.deleteReview(item.id)
        } else {
            val newStage = item.stage + 1
            dao.putReview(item.copy(stage = newStage, proficiency = (item.proficiency + 1).coerceAtMost(4), dueAt = now + REVIEW_DAYS[newStage] * DAY, updatedAt = now))
        }
        dao.unit(item.unitId)?.let { updateAbilitySnapshot(it.bookId) }
    }

    suspend fun saveSpeakingAttempt(
        unitId: String,
        sectionId: String,
        standardSentence: String,
        recognizedText: String,
        score: Int,
        issuesJson: String,
        durationMillis: Long,
        wordsPerMinute: Int,
    ) {
        dao.insertSpeakingAttempt(
            SpeakingAttemptEntity(
                unitId = unitId, sectionId = sectionId, standardSentence = standardSentence,
                recognizedText = recognizedText, score = score, issuesJson = issuesJson,
                durationMillis = durationMillis, wordsPerMinute = wordsPerMinute,
                createdAt = System.currentTimeMillis(),
            ),
        )
        completeSection(sectionId, score)
    }

    suspend fun lessonQuestions(unitId: String): List<Question> = dao.lessonQuestions(unitId).map { toDomain(it) }
    suspend fun sectionQuestions(sectionId: String): List<Question> = dao.sectionQuestions(sectionId).map { toDomain(it) }
    suspend fun question(id: String): Question? = dao.question(id)?.let { toDomain(it) }

    suspend fun similarQuestions(unitId: String, exceptId: String? = null): List<Question> =
        dao.lessonQuestions(unitId).filter { it.id != exceptId }.take(3).map { toDomain(it) }

    fun observeWrongItems(): Flow<List<WrongItem>> = dao.observeWrong().map { wrongList ->
        wrongList.mapNotNull { wrong ->
            val entity = dao.question(wrong.questionId) ?: return@mapNotNull null
            val unit = dao.unit(entity.unitId)
            val section = dao.section(entity.sectionId)
            WrongItem(
                question = toDomain(entity),
                lessonTitle = "第 ${unit?.number ?: "-"} 课 ${unit?.title.orEmpty()}",
                sectionTitle = section?.title.orEmpty(),
                wrongCount = wrong.wrongCount,
                reviewStage = wrong.reviewStage,
                nextReviewAt = wrong.nextReviewAt,
            )
        }
    }

    suspend fun removeWrong(questionId: String) = dao.removeWrong(questionId)

    suspend fun recordPractice(question: Question, answers: List<String>, mode: PracticeMode): Boolean {
        val correct = AnswerEvaluator.isCorrect(question, answers)
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.insertPractice(PracticeRecordEntity(questionId = question.id, answerJson = AnswerEvaluator.encode(answers), isCorrect = correct, mode = mode.name, answeredAt = now))
            val existing = dao.wrong(question.id)
            if (!correct) {
                dao.putWrong(WrongQuestionEntity(question.id, (existing?.wrongCount ?: 0) + 1, 0, now, now + DAY, now))
            } else if (mode == PracticeMode.WRONG_REVIEW && existing != null) {
                if (existing.reviewStage >= 3) dao.removeWrong(question.id)
                else {
                    val stage = existing.reviewStage + 1
                    dao.putWrong(existing.copy(reviewStage = stage, nextReviewAt = now + REVIEW_DAYS[stage] * DAY, updatedAt = now))
                }
            }
        }
        dao.unit(question.unitId)?.let { updateAbilitySnapshot(it.bookId) }
        return correct
    }

    suspend fun createExam(bookId: String, lessonIds: List<String>, scope: AssessmentScope): String {
        require(lessonIds.isNotEmpty()) { "测评范围不能为空" }
        val questions = dao.questionsForAssessment(lessonIds, scope.name).map { toDomain(it) }
        require(questions.isNotEmpty()) { "当前范围暂无测评题目" }
        val selected = ExamAssembler.assemble(questions)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertExam(
            ExamEntity(
                id = id, bookId = bookId, lessonIdsJson = JSONArray(lessonIds).toString(), scope = scope.name,
                questionIdsJson = JSONArray(selected.map { it.id }).toString(), startedAt = now,
                durationSeconds = (selected.size * 90).coerceAtLeast(300), submittedAt = null,
                score = 0, correctCount = 0, totalCount = selected.size, status = "IN_PROGRESS",
            ),
        )
        return id
    }

    suspend fun exam(id: String): ExamEntity? = dao.exam(id)

    suspend fun examQuestions(examId: String): List<Question> {
        val exam = dao.exam(examId) ?: return emptyList()
        return AnswerEvaluator.decode(exam.questionIdsJson).mapNotNull { dao.question(it)?.let { q -> toDomain(q) } }
    }

    suspend fun submitExam(examId: String, answers: Map<String, List<String>>) {
        db.withTransaction {
            val exam = dao.exam(examId) ?: return@withTransaction
            if (exam.status != "IN_PROGRESS") return@withTransaction
            val questions = AnswerEvaluator.decode(exam.questionIdsJson).mapNotNull { dao.question(it) }
            val details = questions.map { question ->
                val userAnswers = answers[question.id].orEmpty()
                val correct = AnswerEvaluator.isCorrect(toDomain(question), userAnswers)
                ExamAnswerEntity(examId = examId, questionId = question.id, answerJson = AnswerEvaluator.encode(userAnswers), isCorrect = correct)
            }
            val correctCount = details.count { it.isCorrect }
            val score = if (questions.isEmpty()) 0 else (correctCount * 100f / questions.size).roundToInt()
            if (dao.submitExam(examId, System.currentTimeMillis(), score, correctCount) == 1) dao.insertExamAnswers(details)
        }
    }

    suspend fun examReport(examId: String): ExamReport? {
        val exam = dao.exam(examId) ?: return null
        val details = dao.examAnswers(examId).mapNotNull { answer ->
            val entity = dao.question(answer.questionId) ?: return@mapNotNull null
            val unit = dao.unit(entity.unitId)
            val section = dao.section(entity.sectionId)
            ExamAnswerItem(toDomain(entity), "第 ${unit?.number ?: "-"} 课 · ${section?.title.orEmpty()}", AnswerEvaluator.decode(answer.answerJson), answer.isCorrect)
        }
        val weaknesses = details.groupBy { it.areaTitle }.map { (title, items) ->
            AreaWeakness(title, items.count { it.isCorrect }, items.size)
        }.sortedBy { it.percent }
        return ExamReport(exam, details, weaknesses)
    }

    suspend fun updateAbilitySnapshot(bookId: String) {
        val units = dao.units(bookId)
        if (units.isEmpty()) return
        val sections = units.flatMap { dao.sections(it.id) }
        val sectionTypes = sections.associate { it.id to it.type }
        val progress = dao.allSectionProgress().associateBy { it.sectionId }
        fun completion(type: LessonSectionType): Float {
            val target = sections.filter { it.type == type.name }
            return if (target.isEmpty()) 0f else target.count { progress[it.id]?.completed == true } * 100f / target.size
        }
        val questions = dao.questionsForLessons(units.map { it.id }).associateBy { it.id }
        val records = dao.practiceForBook(bookId)
        fun correctRate(type: LessonSectionType): Float {
            val target = records.filter { questions[it.questionId]?.let { q -> sectionTypes[q.sectionId] } == type.name }
            return if (target.isEmpty()) 0f else target.count { it.isCorrect } * 100f / target.size
        }
        val attempts = dao.speakingForBook(bookId)
        val pronunciationAttempts = attempts.filter { sectionTypes[it.sectionId] == LessonSectionType.PRONUNCIATION.name }
        val pronunciationAverage = pronunciationAttempts.map { it.score }.average().let { if (it.isNaN()) 0.0 else it }
        val speakingAverage = attempts.map { it.score }.average().let { if (it.isNaN()) 0.0 else it }
        val reviews = dao.reviewsForBook(bookId)
        val reviewProficiency = if (reviews.isEmpty()) 0f else reviews.map { it.proficiency.coerceIn(0, 4) * 25 }.average().toFloat()
        val oralCompletion = (completion(LessonSectionType.PRONUNCIATION) + completion(LessonSectionType.READING)) / 2
        val score = AbilityScoreEntity(
            bookId = bookId,
            pronunciation = (pronunciationAverage * 0.7 + completion(LessonSectionType.PRONUNCIATION) * 0.3).roundToInt(),
            grammar = (correctRate(LessonSectionType.PATTERN) * 0.7 + completion(LessonSectionType.PATTERN) * 0.3).roundToInt(),
            vocabulary = (correctRate(LessonSectionType.VOCABULARY) * 0.5 + reviewProficiency * 0.3 + completion(LessonSectionType.VOCABULARY) * 0.2).roundToInt(),
            speaking = (speakingAverage * 0.6 + oralCompletion * 0.4).roundToInt(),
            reading = (correctRate(LessonSectionType.READING) * 0.7 + completion(LessonSectionType.READING) * 0.3).roundToInt(),
            createdAt = System.currentTimeMillis(),
        )
        dao.insertAbilityScore(score)
    }

    suspend fun clearLearningData() {
        db.withTransaction {
            dao.clearPractice(); dao.clearWrong(); dao.clearReviews(); dao.clearSpeakingAttempts()
            dao.clearAbilityScores(); dao.clearSectionProgress(); dao.clearExamAnswers(); dao.clearExams()
            dao.clearMessages(); dao.clearConversations()
        }
    }

    private suspend fun toDomain(entity: QuestionEntity): Question {
        val passage = entity.passageId?.let { dao.passage(it) }
        val optionsJson = JSONArray(entity.optionsJson)
        val stemsJson = JSONArray(entity.stemsJson)
        return Question(
            id = entity.id, unitId = entity.unitId, sectionId = entity.sectionId,
            assessmentScope = AssessmentScope.valueOf(entity.assessmentScope), passageId = entity.passageId,
            type = QuestionType.valueOf(entity.type), prompt = entity.prompt,
            options = List(optionsJson.length()) { optionsJson.getJSONObject(it).let { o -> AnswerOption(o.getString("id"), o.getString("text")) } },
            stems = List(stemsJson.length()) { stemsJson.getString(it) },
            answers = AnswerEvaluator.decode(entity.answerJson), explanation = entity.explanation,
            difficulty = entity.difficulty, passageTitle = passage?.title, passageContent = passage?.content,
        )
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        val REVIEW_DAYS = longArrayOf(1, 3, 7, 15)
    }
}

data class LessonBundle(
    val unit: UnitEntity,
    val book: com.sierxue.english.data.local.BookEntity?,
    val sections: List<LessonSectionEntity>,
    val vocabulary: List<Vocabulary>,
    val passage: PassageEntity?,
)

data class ExamReport(val exam: ExamEntity, val answers: List<ExamAnswerItem>, val weaknesses: List<AreaWeakness>)

private fun VocabularyEntity.toDomain() = Vocabulary(id, unitId, sectionId, word, phonetic, meaning, collocation, rootTip, example)
