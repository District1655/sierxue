package com.sierxue.english.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val code: String,
    val title: String,
    val description: String,
    val sortOrder: Int,
    val outlineOnly: Boolean,
)

@Entity(tableName = "units", indices = [Index("bookId")])
data class UnitEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val number: Int,
    val title: String,
    val description: String,
    val durationMinutes: Int,
    val sortOrder: Int,
)

@Entity(tableName = "lesson_sections", indices = [Index("unitId")])
data class LessonSectionEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val type: String,
    val title: String,
    val contentJson: String,
    val sortOrder: Int,
)

@Entity(tableName = "section_progress", indices = [Index("unitId")])
data class SectionProgressEntity(
    @PrimaryKey val sectionId: String,
    val unitId: String,
    val completed: Boolean,
    val skipped: Boolean,
    val score: Int?,
    val updatedAt: Long,
)

@Entity(tableName = "vocabulary", indices = [Index("unitId"), Index("sectionId")])
data class VocabularyEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val sectionId: String,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val collocation: String,
    val rootTip: String,
    val example: String,
)

@Entity(tableName = "passages", indices = [Index("unitId"), Index("sectionId")])
data class PassageEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val sectionId: String,
    val title: String,
    val content: String,
    val mainIdea: String,
    val sentenceJson: String,
)

@Entity(tableName = "questions", indices = [Index("unitId"), Index("sectionId"), Index("passageId")])
data class QuestionEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val sectionId: String,
    val passageId: String?,
    val type: String,
    val assessmentScope: String,
    val prompt: String,
    val optionsJson: String,
    val stemsJson: String,
    val answerJson: String,
    val explanation: String,
    val difficulty: Int,
    val sortOrder: Int,
)

@Entity(tableName = "practice_records", indices = [Index("questionId"), Index("answeredAt")])
data class PracticeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: String,
    val answerJson: String,
    val isCorrect: Boolean,
    val mode: String,
    val answeredAt: Long,
)

@Entity(tableName = "wrong_questions", indices = [Index("nextReviewAt")])
data class WrongQuestionEntity(
    @PrimaryKey val questionId: String,
    val wrongCount: Int,
    val reviewStage: Int,
    val lastWrongAt: Long,
    val nextReviewAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "review_items", indices = [Index("unitId"), Index("dueAt")])
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val sectionId: String,
    val sourceId: String,
    val type: String,
    val content: String,
    val dueAt: Long,
    val stage: Int,
    val proficiency: Int,
    val updatedAt: Long,
)

@Entity(tableName = "speaking_attempts", indices = [Index("unitId"), Index("sectionId"), Index("createdAt")])
data class SpeakingAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unitId: String,
    val sectionId: String,
    val standardSentence: String,
    val recognizedText: String,
    val score: Int,
    val issuesJson: String,
    val durationMillis: Long,
    val wordsPerMinute: Int,
    val createdAt: Long,
)

@Entity(tableName = "ability_scores", indices = [Index("bookId"), Index("createdAt")])
data class AbilityScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val pronunciation: Int,
    val grammar: Int,
    val vocabulary: Int,
    val speaking: Int,
    val reading: Int,
    val createdAt: Long,
)

@Entity(tableName = "exams", indices = [Index("startedAt")])
data class ExamEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val lessonIdsJson: String,
    val scope: String,
    val questionIdsJson: String,
    val startedAt: Long,
    val durationSeconds: Int,
    val submittedAt: Long?,
    val score: Int,
    val correctCount: Int,
    val totalCount: Int,
    val status: String,
)

@Entity(
    tableName = "exam_answers",
    indices = [Index("examId"), Index("questionId"), Index(value = ["examId", "questionId"], unique = true)],
)
data class ExamAnswerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: String,
    val questionId: String,
    val answerJson: String,
    val isCorrect: Boolean,
)

@Entity(tableName = "assistant_conversations", indices = [Index("updatedAt")])
data class AssistantConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "assistant_messages", indices = [Index("conversationId"), Index("createdAt")])
data class AssistantMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val content: String,
    val contextJson: String,
    val createdAt: Long,
    val isError: Boolean,
)

@Entity(tableName = "content_meta")
data class ContentMetaEntity(@PrimaryKey val key: String, val value: String)
