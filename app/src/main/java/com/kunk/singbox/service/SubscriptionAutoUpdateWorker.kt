package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 注释已清理。
 * 注释已清理。
 */
class SubscriptionAutoUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SubscriptionAutoUpdate"
        private const val WORK_NAME_PREFIX = "subscription_auto_update_"

        /**
         * 注释已清理。
         * @param context Context
         * 注释已清理。
         * 注释已清理。
         */
        fun schedule(context: Context, profileId: String, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"

            if (intervalMinutes <= 0) {

                workManager.cancelUniqueWork(workName)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString("profile_id", profileId)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        }

        /**
         * 注释已清理。
         */
        fun cancel(context: Context, profileId: String) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"
            workManager.cancelUniqueWork(workName)
        }

        /**
         * 注释已清理。
         */
        fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(TAG)
        }

        /**
         * 注释已清理。
         * 注释已清理。
         */
        suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
            try {
                val configRepository = ConfigRepository.getInstance(context)
                val profiles = configRepository.profiles.first()

                profiles.forEach { profile ->
                    if (profile.type == ProfileType.Subscription &&
                        profile.enabled &&
                        profile.autoUpdateInterval > 0) {
                        schedule(context, profile.id, profile.autoUpdateInterval)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule auto-update tasks", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profileId = inputData.getString("profile_id")

        if (profileId.isNullOrBlank()) {
            Log.e(TAG, "Profile ID is missing")
            return@withContext Result.failure()
        }

        try {
            val configRepository = ConfigRepository.getInstance(applicationContext)

            val profile = configRepository.profiles.first().find { it.id == profileId }
            if (profile == null) {
                Log.w(TAG, "Profile not found: $profileId, cancelling auto-update")
                cancel(applicationContext, profileId)
                return@withContext Result.failure()
            }

            if (!profile.enabled) {
                return@withContext Result.success()
            }

            if (profile.autoUpdateInterval <= 0) {
                cancel(applicationContext, profileId)
                return@withContext Result.success()
            }

            // 注释已清理。
            val result = configRepository.updateProfile(profileId)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed for profile: $profileId", e)

            Result.retry()
        }
    }
}
