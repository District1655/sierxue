package com.sierxue.english.data.local

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

class SeedImporter(private val context: Context, private val db: AppDatabase) {
    suspend fun importIfNeeded() {
        val root = JSONObject(context.assets.open("seed.json").bufferedReader().use { it.readText() })
        val version = root.getInt("version").toString()
        val dao = db.studyDao()
        if (dao.meta(SEED_VERSION) == version) return

        val books = mutableListOf<BookEntity>()
        val units = mutableListOf<UnitEntity>()
        val sections = mutableListOf<LessonSectionEntity>()
        val vocabulary = mutableListOf<VocabularyEntity>()
        val passages = mutableListOf<PassageEntity>()
        val questions = mutableListOf<QuestionEntity>()

        root.getJSONArray("books").forEach { bi, bookJson ->
            val bookId = bookJson.getString("id")
            books += BookEntity(
                id = bookId,
                code = bookJson.getString("code"),
                title = bookJson.getString("title"),
                description = bookJson.getString("description"),
                sortOrder = bi,
                outlineOnly = bookJson.optBoolean("outlineOnly", false),
            )
            bookJson.getJSONArray("lessons").forEach { li, lessonJson ->
                val unitId = lessonJson.getString("id")
                units += UnitEntity(
                    id = unitId,
                    bookId = bookId,
                    number = lessonJson.getInt("number"),
                    title = lessonJson.getString("title"),
                    description = lessonJson.getString("description"),
                    durationMinutes = lessonJson.optInt("durationMinutes", 25),
                    sortOrder = li,
                )
                lessonJson.optJSONArray("sections")?.forEach { si, sectionJson ->
                    sections += LessonSectionEntity(
                        id = "${unitId}S${si + 1}",
                        unitId = unitId,
                        type = sectionJson.getString("type"),
                        title = sectionJson.getString("title"),
                        contentJson = sectionJson.optJSONObject("content")?.toString() ?: "{}",
                        sortOrder = si,
                    )
                }
                lessonJson.optJSONArray("vocabulary")?.forEach { vi, wordJson ->
                    vocabulary += VocabularyEntity(
                        id = "${unitId}V${vi + 1}",
                        unitId = unitId,
                        sectionId = "${unitId}S4",
                        word = wordJson.getString("word"),
                        phonetic = wordJson.getString("phonetic"),
                        meaning = wordJson.getString("meaning"),
                        collocation = wordJson.getString("collocation"),
                        rootTip = wordJson.getString("rootTip"),
                        example = wordJson.getString("example"),
                    )
                }
                lessonJson.optJSONObject("passage")?.let { passageJson ->
                    passages += PassageEntity(
                        id = "${unitId}P",
                        unitId = unitId,
                        sectionId = "${unitId}S5",
                        title = passageJson.getString("title"),
                        content = passageJson.getString("content"),
                        mainIdea = passageJson.getString("mainIdea"),
                        sentenceJson = (lessonJson.optJSONArray("sections")?.getJSONObject(4)
                            ?.optJSONObject("content")?.optJSONArray("sentenceBreakdown") ?: JSONArray()).toString(),
                    )
                }
                lessonJson.optJSONArray("questions")?.forEach { qi, questionJson ->
                    val type = questionJson.getString("type")
                    val options = questionJson.optJSONArray("options") ?: JSONArray()
                    if (type == "TRUE_FALSE" && options.length() == 0) {
                        options.put(JSONObject().put("id", "TRUE").put("text", "正确"))
                        options.put(JSONObject().put("id", "FALSE").put("text", "错误"))
                    }
                    questions += QuestionEntity(
                        id = "${unitId}Q${qi + 1}",
                        unitId = unitId,
                        sectionId = "${unitId}S${questionJson.getInt("section")}",
                        passageId = if (questionJson.getInt("section") == 5) "${unitId}P" else null,
                        type = type,
                        assessmentScope = questionJson.optString("scope", "LESSON"),
                        prompt = questionJson.getString("prompt"),
                        optionsJson = options.toString(),
                        stemsJson = (questionJson.optJSONArray("stems") ?: JSONArray()).toString(),
                        answerJson = questionJson.getJSONArray("answer").toString(),
                        explanation = questionJson.getString("explanation"),
                        difficulty = questionJson.optInt("difficulty", 1),
                        sortOrder = qi,
                    )
                }
            }
        }

        db.withTransaction {
            dao.clearQuestions()
            dao.clearPassages()
            dao.clearVocabulary()
            dao.clearSections()
            dao.clearUnits()
            dao.clearBooks()
            dao.insertBooks(books)
            dao.insertUnits(units)
            dao.insertSections(sections)
            dao.insertVocabulary(vocabulary)
            dao.insertPassages(passages)
            dao.insertQuestions(questions)
            dao.putMeta(ContentMetaEntity(SEED_VERSION, version))
        }
    }

    private inline fun JSONArray.forEach(action: (Int, JSONObject) -> Unit) {
        for (index in 0 until length()) action(index, getJSONObject(index))
    }

    private companion object { const val SEED_VERSION = "seed_version" }
}
