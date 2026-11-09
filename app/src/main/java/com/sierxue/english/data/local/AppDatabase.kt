package com.sierxue.english.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class, UnitEntity::class, LessonSectionEntity::class,
        SectionProgressEntity::class, VocabularyEntity::class, PassageEntity::class,
        QuestionEntity::class, PracticeRecordEntity::class, WrongQuestionEntity::class,
        ReviewItemEntity::class, SpeakingAttemptEntity::class, AbilityScoreEntity::class,
        ExamEntity::class, ExamAnswerEntity::class,
        AssistantConversationEntity::class, AssistantMessageEntity::class,
        ContentMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sierxue.db").build()
    }
}
