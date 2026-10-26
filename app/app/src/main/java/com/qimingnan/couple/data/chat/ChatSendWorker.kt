package com.qimingnan.couple.data.chat

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 聊天离线 outbox（contract "Chat" §App rules）：把一条 PENDING 消息 POST 出去。
 * 需要网络；IO 失败按指数退避重试（进程死亡后 WorkManager 会继续），4xx 由仓库标记 FAILED。
 */
@HiltWorker
class ChatSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: ChatRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val done = runCatching { repo.sendPending(messageId) }.getOrDefault(false)
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_MESSAGE_ID = "messageId"

        /** requireNetwork=false：演示后端在进程内，离线也能发。replace：手动重试不被退避中的旧任务吞掉。 */
        fun enqueue(context: Context, messageId: String, requireNetwork: Boolean = true, replace: Boolean = false) {
            val net = if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            val req = OneTimeWorkRequestBuilder<ChatSendWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            // KEEP：同一消息只保留一个待发任务；发送顺序由每条消息自己的 createdAt 决定。
            WorkManager.getInstance(context).enqueueUniqueWork("chat-send-$messageId", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
