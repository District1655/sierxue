package com.sierxue.english.domain

object ExamAssembler {
    /** 只重排已按教材边界筛选的题目，不跨课或跨册抽题。 */
    fun assemble(questions: List<Question>): List<Question> = questions.shuffled()
}
