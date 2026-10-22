package com.qimingnan.couple.data.bootstrap

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 兜底：新配置试用期内如果 App 被杀，这个任务会在窗口结束后完成“提交或回滚”。
 */
@HiltWorker
class ConfigVerifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: BootstrapManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        manager.verifyPending()
        return Result.success()
    }

    companion object {
        private const val NAME = "config-verify"

        fun scheduler(workManager: () -> WorkManager) = PendingVerificationScheduler { delayMillis ->
            val req = OneTimeWorkRequestBuilder<ConfigVerifyWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            workManager().enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
