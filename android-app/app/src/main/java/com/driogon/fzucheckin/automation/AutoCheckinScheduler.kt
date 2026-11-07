package com.driogon.fzucheckin.automation

import android.content.Context
import androidx.work.*
import com.driogon.fzucheckin.core.TimeRules
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

interface ConfirmationQueue {
    fun confirmation(task: AutoTask, key: String, step: Int)
}

class AutoCheckinScheduler(context: Context, private val store: AutomationStore,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(TimeRules.china) }) : ConfirmationQueue {
    private val work = WorkManager.getInstance(context.applicationContext)
    private fun data(task: AutoTask) = workDataOf("version" to task.version, "generation" to task.generation, "due" to task.due)
    private fun tag(task: AutoTask) = "auto-version-${task.version}"

    fun cancel(version: Long) { work.cancelAllWorkByTag("auto-version-$version").result.get() }

    /** Always persist the next future task before dispatching anything that requires a network. */
    fun reconcile() {
        val settings = store.read()
        if (!settings.active) return
        val now = clock()
        val next = AutoTask(settings.version, settings.generation, maxOf(settings.firstDue, AutoTimes.millis(AutoTimes.next(now, settings.minute))))
        schedule(next, now)
        val latest = AutoTask(settings.version, settings.generation, AutoTimes.millis(AutoTimes.latest(now, settings.minute)))
        if (latest.due >= settings.firstDue && !store.finished(latest)) schedule(latest, now)
    }

    private fun schedule(task: AutoTask, now: ZonedDateTime) {
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>().setInputData(data(task))
            .setInitialDelay((task.due - AutoTimes.millis(now)).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(tag(task)).build()
        work.enqueueUniqueWork("schedule-${task.id}", ExistingWorkPolicy.KEEP, request).result.get()
    }

    fun dispatch(task: AutoTask) {
        val request = OneTimeWorkRequestBuilder<AutoCheckinWorker>().setInputData(data(task))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag(tag(task)).build()
        work.enqueueUniqueWork("attempt-${task.id}", ExistingWorkPolicy.KEEP, request).result.get()
    }

    override fun confirmation(task: AutoTask, key: String, step: Int) {
        val input = Data.Builder().putAll(data(task)).putString("key", key).putInt("step", step).build()
        val request = OneTimeWorkRequestBuilder<ConfirmWorker>().setInputData(input)
            .setInitialDelay(if (step == 0) 1 else 5, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(tag(task)).build()
        work.enqueueUniqueWork("confirm-${task.id}-$step", ExistingWorkPolicy.KEEP, request).result.get()
    }
}

internal fun Data.autoTask(): AutoTask = AutoTask(getLong("version", -1), getLong("generation", -1), getLong("due", -1))
