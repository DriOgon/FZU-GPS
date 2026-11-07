package com.driogon.fzucheckin.automation

import android.app.Application
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SchedulerTest {
    private lateinit var store: AutomationStore
    private lateinit var scheduler: AutoCheckinScheduler
    private lateinit var work: WorkManager
    private val now = ZonedDateTime.parse("2026-11-07T22:00:00+08:00")
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        work = WorkManager.getInstance(context); store = AutomationStore(context)
        store.attachAccount("synthetic")
        store.configure(true, 1265, AutoTimes.millis(AutoTimes.next(now, 1265)))
        scheduler = AutoCheckinScheduler(context, store) { now }
    }
    @After fun close() { work.cancelAllWork().result.get(); store.close() }
    private fun task(): AutoTask = store.read().let { AutoTask(it.version, it.generation, it.firstDue) }
    @Test fun reconcileIsIdempotentAndScheduleDoesNotRequireNetwork() {
        scheduler.reconcile(); scheduler.reconcile()
        val entries = work.getWorkInfosForUniqueWork("schedule-${task().id}").get()
        assertEquals(1, entries.size)
        assertEquals(NetworkType.NOT_REQUIRED, entries.single().constraints.requiredNetworkType)
        assertEquals(0, work.getWorkInfosForUniqueWork("attempt-${task().id}").get().size)
    }
    @Test fun attemptsWaitForNetworkAndChangingSettingsCancelsOldWork() {
        val old = task(); scheduler.dispatch(old)
        val entry = work.getWorkInfosForUniqueWork("attempt-${old.id}").get().single()
        assertEquals(NetworkType.CONNECTED, entry.constraints.requiredNetworkType)
        store.configure(false, 1265, 0); scheduler.cancel(old.version)
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoById(entry.id).get()!!.state)
    }
    @Test fun confirmationIsUniqueAndRequiresNetwork() {
        val task = task(); scheduler.confirmation(task, "synthetic-key", 0); scheduler.confirmation(task, "synthetic-key", 0)
        val entries = work.getWorkInfosForUniqueWork("confirm-${task.id}-0").get()
        assertEquals(1, entries.size); assertEquals(NetworkType.CONNECTED, entries.single().constraints.requiredNetworkType)
    }
}
