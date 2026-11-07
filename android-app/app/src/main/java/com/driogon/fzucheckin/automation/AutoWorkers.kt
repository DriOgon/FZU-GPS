package com.driogon.fzucheckin.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.driogon.fzucheckin.FzuApplication
import com.driogon.fzucheckin.core.TimeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime

class ScheduleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val services = (applicationContext as FzuApplication).services
            val task = inputData.autoTask()
            val settings = services.store.read()
            if (!AutoTimes.current(settings, task)) return@withContext Result.success()
            services.scheduler.reconcile()
            val now = ZonedDateTime.now(TimeRules.china)
            val latest = task.copy(due = AutoTimes.millis(AutoTimes.latest(now, settings.minute)))
            if (AutoTimes.current(services.store.read(), latest) && !services.store.finished(latest)) services.scheduler.dispatch(latest)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class AutoCheckinWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val services = (applicationContext as FzuApplication).services
        if (services.runner.attempt(inputData.autoTask())) Result.retry() else Result.success()
    }
}

class ConfirmWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString("key") ?: return@withContext Result.success()
        (applicationContext as FzuApplication).services.runner.confirm(inputData.autoTask(), key, inputData.getInt("step", 0))
        Result.success()
    }
}
