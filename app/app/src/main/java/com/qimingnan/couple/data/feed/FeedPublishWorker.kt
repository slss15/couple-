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
        return when (runCatching { repo.sendPending(postId) }.getOrDefault(FeedRepository.SendOutcome.RETRY)) {
            FeedRepository.SendOutcome.DONE -> Result.success()
            FeedRepository.SendOutcome.RETRY -> Result.retry()
            // 已标记 FAILED：结束这条工作，等用户点「重试」（REPLACE 重新入队），不在后台无限退避
            FeedRepository.SendOutcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_POST_ID = "postId"

        fun enqueue(context: Context, postId: String, requireNetwork: Boolean = true, replace: Boolean = false) {
            val net = if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            val req = OneTimeWorkRequestBuilder<FeedPublishWorker>()
                .setInputData(workDataOf(KEY_POST_ID to postId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // 每帖一条唯一工作；手动重试用 REPLACE，避免被仍在退避中的旧工作吞掉
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork("feed-publish-$postId", policy, req)
        }
    }
}
