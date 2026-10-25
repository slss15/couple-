package com.qimingnan.couple.data.feed

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
 * 离线发布 outbox（contract "Feed" §App rules）：把一条 PENDING 帖子的图片压缩上传并 POST。
 * 需要网络；IO 失败按指数退避重试（进程死亡后 WorkManager 会继续），4xx 由仓库标记 FAILED。
 */
@HiltWorker
class FeedPublishWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: FeedRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val postId = inputData.getString(KEY_POST_ID) ?: return Result.failure()
        val done = runCatching { repo.sendPending(postId) }.getOrDefault(false)
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_POST_ID = "postId"

        fun enqueue(context: Context, postId: String) {
            val req = OneTimeWorkRequestBuilder<FeedPublishWorker>()
                .setInputData(workDataOf(KEY_POST_ID to postId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND：按入队顺序逐条发（帖子之间的时间顺序尽量保持）
            WorkManager.getInstance(context).enqueueUniqueWork("feed-publish-$postId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
