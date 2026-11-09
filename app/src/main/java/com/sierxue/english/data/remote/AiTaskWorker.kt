package com.sierxue.english.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sierxue.english.SiErXueApplication
import com.sierxue.english.domain.ChatRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlin.random.Random

class AiTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        delay(300 + Random.nextLong(-100, 101))
        val question = inputData.getString("question").orEmpty()
        val context = inputData.getString("context").orEmpty()
        if (question.isBlank()) return Result.failure()
        val container = (applicationContext as SiErXueApplication).container
        val reply = container.chatRouter.respond(
            ChatRequest(
                systemPrompt = "你是英语助教。用简洁中文生成有确定答案的练习，不要编造当前课程信息。",
                contextJson = context,
                history = emptyList(),
                userMessage = question,
                fallbackText = "网络恢复后请重新生成练习。",
            ),
            userInitiated = false,
        )
        return if (reply.source == ChatSource.REMOTE) {
            Result.success(workDataOf("answer" to reply.text))
        } else if (runAttemptCount < 1) {
            Result.retry()
        } else {
            Result.failure()
        }
    }
}

object AiTaskScheduler {
    /** 只供批量生成等非实时任务使用；实时聊天不得调用。 */
    fun enqueue(context: Context, question: String, contextJson: String) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<AiTaskWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("question" to question, "context" to contextJson))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).beginUniqueWork("ai_batch_generation", ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
    }
}
