package com.sierxue.english.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBooks(items: List<BookEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertUnits(items: List<UnitEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSections(items: List<LessonSectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVocabulary(items: List<VocabularyEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPassages(items: List<PassageEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQuestions(items: List<QuestionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeta(item: ContentMetaEntity)
    @Query("SELECT value FROM content_meta WHERE `key` = :key") suspend fun meta(key: String): String?

    @Query("SELECT * FROM books ORDER BY sortOrder") fun observeBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM units WHERE bookId = :bookId ORDER BY sortOrder") fun observeUnits(bookId: String): Flow<List<UnitEntity>>
    @Query("SELECT * FROM units WHERE bookId = :bookId ORDER BY sortOrder") suspend fun units(bookId: String): List<UnitEntity>
    @Query("SELECT * FROM units WHERE id = :id") suspend fun unit(id: String): UnitEntity?
    @Query("SELECT * FROM lesson_sections WHERE unitId = :unitId ORDER BY sortOrder") suspend fun sections(unitId: String): List<LessonSectionEntity>
    @Query("SELECT * FROM lesson_sections WHERE id = :id") suspend fun section(id: String): LessonSectionEntity?
    @Query("SELECT * FROM section_progress WHERE unitId = :unitId") fun observeSectionProgress(unitId: String): Flow<List<SectionProgressEntity>>
    @Query("SELECT * FROM section_progress") suspend fun allSectionProgress(): List<SectionProgressEntity>
    @Query("SELECT * FROM section_progress") fun observeAllSectionProgress(): Flow<List<SectionProgressEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSectionProgress(item: SectionProgressEntity)
    @Query("SELECT * FROM vocabulary WHERE unitId = :unitId") suspend fun vocabularyForUnit(unitId: String): List<VocabularyEntity>
    @Query("SELECT * FROM passages WHERE unitId = :unitId LIMIT 1") suspend fun passageForUnit(unitId: String): PassageEntity?
    @Query("SELECT * FROM passages WHERE id = :id") suspend fun passage(id: String): PassageEntity?

    @Query("SELECT * FROM questions WHERE unitId = :unitId AND assessmentScope = 'LESSON' ORDER BY sortOrder") suspend fun lessonQuestions(unitId: String): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE sectionId = :sectionId AND assessmentScope = 'LESSON' ORDER BY sortOrder") suspend fun sectionQuestions(sectionId: String): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE id = :id") suspend fun question(id: String): QuestionEntity?
    @Query("SELECT * FROM questions WHERE unitId IN (:unitIds)") suspend fun questionsForLessons(unitIds: List<String>): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE unitId IN (:unitIds) AND assessmentScope = :scope") suspend fun questionsForAssessment(unitIds: List<String>, scope: String): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE sectionId = :sectionId") suspend fun allQuestionsForSection(sectionId: String): List<QuestionEntity>

    @Insert suspend fun insertPractice(item: PracticeRecordEntity)
    @Query("SELECT p.* FROM practice_records p INNER JOIN questions q ON p.questionId = q.id INNER JOIN units u ON q.unitId = u.id WHERE u.bookId = :bookId") suspend fun practiceForBook(bookId: String): List<PracticeRecordEntity>
    @Query("SELECT * FROM wrong_questions ORDER BY updatedAt DESC") fun observeWrong(): Flow<List<WrongQuestionEntity>>
    @Query("SELECT * FROM wrong_questions WHERE questionId = :questionId") suspend fun wrong(questionId: String): WrongQuestionEntity?
    @Query("SELECT COUNT(*) FROM wrong_questions") fun observeWrongCount(): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWrong(item: WrongQuestionEntity)
    @Query("DELETE FROM wrong_questions WHERE questionId = :questionId") suspend fun removeWrong(questionId: String)

    @Query("SELECT * FROM review_items WHERE dueAt <= :now ORDER BY dueAt") fun observeDueReviews(now: Long): Flow<List<ReviewItemEntity>>
    @Query("SELECT * FROM review_items WHERE dueAt <= :now ORDER BY dueAt") suspend fun dueReviews(now: Long): List<ReviewItemEntity>
    @Query("SELECT * FROM review_items WHERE unitId = :unitId") suspend fun reviewsForUnit(unitId: String): List<ReviewItemEntity>
    @Query("SELECT r.* FROM review_items r INNER JOIN units u ON r.unitId = u.id WHERE u.bookId = :bookId") suspend fun reviewsForBook(bookId: String): List<ReviewItemEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReviews(items: List<ReviewItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putReview(item: ReviewItemEntity)
    @Query("DELETE FROM review_items WHERE id = :id") suspend fun deleteReview(id: String)

    @Insert suspend fun insertSpeakingAttempt(item: SpeakingAttemptEntity)
    @Query("SELECT a.* FROM speaking_attempts a INNER JOIN units u ON a.unitId = u.id WHERE u.bookId = :bookId") suspend fun speakingForBook(bookId: String): List<SpeakingAttemptEntity>
    @Insert suspend fun insertAbilityScore(item: AbilityScoreEntity)
    @Query("SELECT * FROM ability_scores WHERE bookId = :bookId ORDER BY createdAt DESC LIMIT 1") fun observeAbility(bookId: String): Flow<AbilityScoreEntity?>

    @Insert suspend fun insertExam(item: ExamEntity)
    @Query("SELECT * FROM exams WHERE id = :id") suspend fun exam(id: String): ExamEntity?
    @Query("SELECT * FROM exams ORDER BY startedAt DESC") fun observeExams(): Flow<List<ExamEntity>>
    @Query("SELECT * FROM exams WHERE status = 'SUBMITTED' ORDER BY startedAt DESC LIMIT 1") fun observeLatestSubmittedExam(): Flow<ExamEntity?>
    @Query("UPDATE exams SET submittedAt = :submittedAt, score = :score, correctCount = :correctCount, status = 'SUBMITTED' WHERE id = :id AND status = 'IN_PROGRESS'") suspend fun submitExam(id: String, submittedAt: Long, score: Int, correctCount: Int): Int
    @Insert suspend fun insertExamAnswers(items: List<ExamAnswerEntity>)
    @Query("SELECT * FROM exam_answers WHERE examId = :examId") suspend fun examAnswers(examId: String): List<ExamAnswerEntity>

    @Insert suspend fun insertConversation(item: AssistantConversationEntity)
    @Query("SELECT * FROM assistant_conversations ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<AssistantConversationEntity>>
    @Query("SELECT * FROM assistant_conversations ORDER BY updatedAt DESC LIMIT 1") suspend fun latestConversation(): AssistantConversationEntity?
    @Query("SELECT * FROM assistant_messages WHERE conversationId = :conversationId ORDER BY createdAt") fun observeMessages(conversationId: String): Flow<List<AssistantMessageEntity>>
    @Query("SELECT * FROM assistant_messages WHERE conversationId = :conversationId ORDER BY createdAt") suspend fun messages(conversationId: String): List<AssistantMessageEntity>
    @Insert suspend fun insertMessage(item: AssistantMessageEntity): Long
    @Query("UPDATE assistant_conversations SET updatedAt = :updatedAt WHERE id = :id") suspend fun touchConversation(id: String, updatedAt: Long)

    @Query("DELETE FROM practice_records") suspend fun clearPractice()
    @Query("DELETE FROM wrong_questions") suspend fun clearWrong()
    @Query("DELETE FROM review_items") suspend fun clearReviews()
    @Query("DELETE FROM speaking_attempts") suspend fun clearSpeakingAttempts()
    @Query("DELETE FROM ability_scores") suspend fun clearAbilityScores()
    @Query("DELETE FROM section_progress") suspend fun clearSectionProgress()
    @Query("DELETE FROM exam_answers") suspend fun clearExamAnswers()
    @Query("DELETE FROM exams") suspend fun clearExams()
    @Query("DELETE FROM assistant_messages") suspend fun clearMessages()
    @Query("DELETE FROM assistant_conversations") suspend fun clearConversations()

    @Query("DELETE FROM questions") suspend fun clearQuestions()
    @Query("DELETE FROM passages") suspend fun clearPassages()
    @Query("DELETE FROM vocabulary") suspend fun clearVocabulary()
    @Query("DELETE FROM lesson_sections") suspend fun clearSections()
    @Query("DELETE FROM units") suspend fun clearUnits()
    @Query("DELETE FROM books") suspend fun clearBooks()
}
