package com.driogon.fzucheckin.automation

import android.app.Application
import com.driogon.fzucheckin.core.*
import com.driogon.fzucheckin.data.CredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AutomationTest {
    private var now = ZonedDateTime.parse("2026-11-07T21:05:00+08:00")
    private lateinit var store: AutomationStore
    private lateinit var repo: FakeRepository
    private lateinit var credentials: FakeCredentials
    private lateinit var coordinator: CheckinCoordinator
    private val confirmations = mutableListOf<Triple<AutoTask, String, Int>>()
    private val notifications = mutableListOf<String>()
    private lateinit var runner: AutomationRunner
    private val queue = object : ConfirmationQueue {
        override fun confirmation(task: AutoTask, key: String, step: Int) { confirmations += Triple(task, key, step) }
    }
    private class FakeCredentials : CredentialStore {
        var value: Credentials? = Credentials("synthetic", "synthetic-password")
        var failSave = false
        override fun load() = value
        override fun save(credentials: Credentials) { if (failSave) error("save failed"); value = credentials }
        override fun clear() { value = null }
    }
    private inner class FakeRepository : CheckinRepository {
        var snapshot = Snapshot("测试同学", "测试学院", "p", 1, "21:00    ", "23:59   ", "22:00", now, true, CheckinStatus.READY, Campus("c", "校区"))
        var after = CheckinStatus.SUCCESS
        var afterPlan: String? = null
        var fail: ErrorKind? = null
        var queries = 0
        var submissions = 0
        var finalSnapshot: Snapshot? = null
        var beforeGuard: (() -> Unit)? = null
        override fun login(credentials: Credentials) { if (fail == ErrorKind.CAPTCHA || fail == ErrorKind.AUTH) throw CheckinException(fail!!, "登录不可用") }
        override fun refresh(): Snapshot {
            queries++
            fail?.let { throw CheckinException(it, "暂不可用") }
            return snapshot.copy(serverNow = now)
        }
        override fun submit(address: String, guard: SubmissionGuard, onConfirming: () -> Unit): SubmitOutcome {
            beforeGuard?.invoke()
            guard.beforeDispatch(finalSnapshot ?: snapshot.copy(serverNow = now))
            submissions++
            onConfirming()
            snapshot = snapshot.copy(status = after, serverNow = now, planId = afterPlan ?: snapshot.planId)
            return SubmitOutcome(snapshot.copy(serverNow = now))
        }
        override fun clearSession() = Unit
    }
    @Before fun setup() {
        store = AutomationStore(RuntimeEnvironment.getApplication())
        credentials = FakeCredentials(); repo = FakeRepository()
        coordinator = CheckinCoordinator(repo, credentials, store) { now }
        runner = AutomationRunner(coordinator, queue, { _, text, _ -> notifications += text }) { now }
    }
    @After fun close() { store.close() }
    private suspend fun enable(): AutoTask {
        coordinator.login(credentials.value!!, false)
        coordinator.configure(true, 21 * 60 + 5)
        // Saving at 21:05 schedules tomorrow; advance to that next future occurrence.
        now = AutoTimes.at(store.read().firstDue)
        val s = store.read()
        return AutoTask(s.version, s.generation, s.firstDue)
    }

    @Test fun defaultsAreDisabledAndSavingNeverBackdates() = runTest {
        assertFalse(store.read().enabled)
        assertEquals("21:05", store.read().timeLabel)
        val task = enable()
        assertEquals("2026-11-08", AutoTimes.at(task.due).toLocalDate().toString())
        assertEquals(0, repo.submissions)
    }
    @Test fun nextOccurrenceUsesChinaTimeAndDoesNotAccumulateDelay() {
        val late = ZonedDateTime.parse("2026-11-07T22:30:00+08:00")
        assertEquals("21:05", AutoTimes.next(late, 1265).toLocalTime().toString())
        assertEquals(AutoTimes.next(late, 1265), AutoTimes.next(late.withZoneSameInstant(java.time.ZoneId.of("UTC")), 1265))
    }
    @Test fun successRequiresConfirmedSchoolStatus() = runTest {
        val task = enable()
        assertFalse(runner.attempt(task))
        assertEquals(1, repo.submissions)
        assertEquals("SUCCESS", store.submission(submissionKey(store.read().accountId, repo.snapshot)!!))
        assertEquals(listOf("自动签到成功，学校已确认"), notifications)
        runner.attempt(task)
        assertEquals(1, repo.submissions)
    }
    @Test fun manuallySignedAlreadyIsSkippedByAutomation() = runTest {
        val task = enable()
        coordinator.submit("")
        runner.attempt(task)
        assertEquals(1, repo.submissions)
        assertEquals("已签到，已跳过", store.read().lastMessage)
    }
    @Test fun allNonEligibleStatesStopWithoutSubmission() = runTest {
        coordinator.login(credentials.value!!, false)
        for (status in listOf(CheckinStatus.NO_PLAN, CheckinStatus.NEED_NOT, CheckinStatus.OUT_OF_TIME, CheckinStatus.UNKNOWN_WAY, CheckinStatus.NEEDS_ADDRESS)) {
            coordinator.configure(true, 1265); now = AutoTimes.at(store.read().firstDue)
            val s = store.read(); val task = AutoTask(s.version, s.generation, s.firstDue)
            repo.snapshot = repo.snapshot.copy(status = status, way = if (status == CheckinStatus.NEEDS_ADDRESS) 2 else 1)
            assertFalse(runner.attempt(task)); assertTrue(store.finished(task))
        }
        assertEquals(0, repo.submissions)
    }
    @Test fun retriesBeforeSubmissionArePersistentlyLimitedToThree() = runTest {
        val task = enable(); repo.fail = ErrorKind.NETWORK
        assertTrue(runner.attempt(task)); assertTrue(runner.attempt(task)); assertFalse(runner.attempt(task))
        runner.attempt(task)
        assertEquals(3, repo.queries); assertEquals(0, repo.submissions)
    }
    @Test fun missingServerTimeNeverFallsBackToPhoneTimeForAutomation() = runTest {
        val task = enable(); repo.snapshot = repo.snapshot.copy(serverTimeAvailable = false)
        assertTrue(runner.attempt(task)); assertEquals(0, repo.submissions)
    }
    @Test fun wrongPasswordAndCaptchaPauseUntilExplicitResume() = runTest {
        val task = enable(); repo.fail = ErrorKind.CAPTCHA
        assertFalse(runner.attempt(task)); assertTrue(store.read().paused.isNotEmpty())
        repo.fail = null
        coordinator.login(credentials.value!!, false)
        assertTrue(store.read().paused.isNotEmpty())
        coordinator.configure(true, 1265)
        assertEquals("", store.read().paused)
        assertFalse(AutoTimes.current(store.read(), task))
    }
    @Test fun unsavedCredentialsCannotEnableAutomation() = runTest {
        credentials.failSave = true
        assertFalse(coordinator.login(Credentials("new-account", "new-password"), true))
        try { coordinator.configure(true, 1265); fail("must reject") } catch (e: CheckinException) { assertEquals(ErrorKind.VALIDATION, e.kind) }
        assertFalse(store.read().enabled)
    }
    @Test fun disablingOrEditingInvalidatesOldWorker() = runTest {
        val task = enable(); coordinator.configure(false, 1265)
        runner.attempt(task); assertEquals(0, repo.queries)
        coordinator.configure(true, 1270)
        runner.attempt(task); assertEquals(0, repo.queries)
    }
    @Test fun settingsChangeAtTheLastGuardPreventsRequest() = runTest {
        val task = enable()
        repo.beforeGuard = { store.configure(false, 1265, 0) }
        assertFalse(runner.attempt(task)); assertEquals(0, repo.submissions)
    }
    @Test fun wayOrServerTimeChangesAtLastGuardPreventRequest() = runTest {
        val task = enable()
        repo.finalSnapshot = repo.snapshot.copy(way = 2, status = CheckinStatus.NEEDS_ADDRESS, serverNow = now)
        runner.attempt(task)
        assertEquals(0, repo.submissions)
    }
    @Test fun expiredTasksDoNotEvenLoadSchoolState() = runTest {
        val task = enable(); now = now.plusDays(1)
        runner.attempt(task); assertEquals(0, repo.queries)
    }
    @Test fun selectedTimeOutsideSchoolWindowDoesNotSignWhenDelayedIntoWindow() = runTest {
        coordinator.login(credentials.value!!, false); coordinator.configure(true, 20 * 60)
        val s = store.read(); val task = AutoTask(s.version, s.generation, s.firstDue)
        now = AutoTimes.at(task.due).plusHours(2)
        runner.attempt(task); assertEquals(0, repo.submissions)
    }
    @Test fun processingUsesTwoQueriesAndNeverResends() = runTest {
        val task = enable(); repo.after = CheckinStatus.PROCESSING
        runner.attempt(task); assertEquals(1, confirmations.size)
        runner.confirm(task, confirmations[0].second, 0)
        runner.confirm(task, confirmations[1].second, 1)
        assertEquals(1, repo.submissions)
        assertEquals("签到结果待确认，请打开 App 核实", store.read().lastMessage)
    }
    @Test fun unknownResultCanBecomeConfirmedWithoutResending() = runTest {
        val task = enable(); repo.after = CheckinStatus.UNKNOWN_RESULT
        runner.attempt(task)
        repo.snapshot = repo.snapshot.copy(status = CheckinStatus.SUCCESS)
        runner.confirm(task, confirmations.single().second, 0)
        assertEquals(1, repo.submissions); assertEquals("签到成功，学校已确认", store.read().lastMessage)
    }
    @Test fun interruptedDispatchSurvivesDatabaseReopenAndReauthentication() = runTest {
        val task = enable()
        val key = submissionKey(store.read().accountId, repo.snapshot.copy(serverNow = now))!!
        store.reserve(key, task); store.close()
        store = AutomationStore(RuntimeEnvironment.getApplication())
        coordinator = CheckinCoordinator(repo, credentials, store) { now }
        coordinator.login(credentials.value!!, false)
        assertEquals(CheckinStatus.UNKNOWN_RESULT, coordinator.refresh().status)
        coordinator.submit("")
        assertEquals(0, repo.submissions)
    }
    @Test fun modifiedTimeDoesNotEraseUnconfirmedSubmission() = runTest {
        val task = enable(); repo.after = CheckinStatus.UNKNOWN_RESULT
        runner.attempt(task); coordinator.configure(true, 1270)
        repo.snapshot = repo.snapshot.copy(status = CheckinStatus.READY)
        coordinator.submit(""); assertEquals(1, repo.submissions)
    }
    @Test fun newSchoolWindowDoesNotInheritYesterdayUncertainty() = runTest {
        val task = enable(); repo.after = CheckinStatus.UNKNOWN_RESULT
        runner.attempt(task)
        now = now.plusDays(1); repo.snapshot = repo.snapshot.copy(status = CheckinStatus.READY); repo.after = CheckinStatus.SUCCESS
        coordinator.submit(""); assertEquals(2, repo.submissions)
    }
    @Test fun crossingMidnightHasOneStableSubmissionKey() {
        val evening = repo.snapshot.copy(serverNow = ZonedDateTime.parse("2026-11-07T23:30:00+08:00"), start = "21:00  ", end = "01:00  ")
        val morning = evening.copy(serverNow = evening.serverNow.plusHours(1))
        assertEquals(submissionKey("account", evening), submissionKey("account", morning))
        assertTrue(TimeRules.inRange(morning.serverNow, morning.start, morning.end))
    }
    @Test fun clearingAccountStopsOldTasksAndClearsVisibleState() = runTest {
        val task = enable(); coordinator.clearAccount()
        runner.attempt(task); assertNull(credentials.value); assertNull(coordinator.snapshots.value)
        assertFalse(store.read().enabled); assertEquals("", store.read().accountId); assertEquals(0, repo.queries)
    }
    @Test fun manualAndAutomaticSubmissionShareOneMutex() = runBlocking {
        val task = enable(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        repo.beforeGuard = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val background = async(Dispatchers.IO) { runner.attempt(task) }
        check(entered.await(5, TimeUnit.SECONDS))
        val manual = async(Dispatchers.IO) { coordinator.submit("") }
        release.countDown(); background.await(); manual.await()
        assertEquals(1, repo.submissions)
    }
    @Test fun changedPlanCannotConfirmSuccessForAnOldSubmission() = runTest {
        val task = enable(); repo.after = CheckinStatus.UNKNOWN_RESULT; runner.attempt(task)
        repo.snapshot = repo.snapshot.copy(planId = "different-plan", status = CheckinStatus.SUCCESS)
        runner.confirm(task, confirmations.single().second, 0)
        assertTrue(store.read().lastMessage.contains("计划已变化")); assertEquals(1, repo.submissions)
    }
    @Test fun failedDurableReservationPreventsSending() = runTest {
        val task = enable()
        store.writableDatabase.execSQL("CREATE TRIGGER block_submission BEFORE INSERT ON submissions BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")
        assertFalse(runner.attempt(task)); assertEquals(0, repo.submissions)
    }
    @Test fun changingAccountDuringARequestCannotOverwriteNewSettings() = runTest {
        val task = enable()
        repo.beforeGuard = { store.attachAccount(accountId("different-account")) }
        runner.attempt(task)
        assertEquals(0, repo.submissions); assertEquals("尚未执行", store.read().lastMessage)
    }
    @Test fun finalServerTimeFailureOnlyRetriesQueries() = runTest {
        val task = enable()
        repo.finalSnapshot = repo.snapshot.copy(serverTimeAvailable = false, serverNow = now)
        assertTrue(runner.attempt(task)); assertEquals(0, repo.submissions)
    }
    @Test fun manualOutsideModeStillAcceptsUserAddress() = runTest {
        coordinator.login(credentials.value!!, false)
        repo.snapshot = repo.snapshot.copy(status = CheckinStatus.NEEDS_ADDRESS, way = 2)
        coordinator.submit("测试地址")
        assertEquals(1, repo.submissions)
    }
    @Test fun editingTimeDoesNotWaitForOldWorkerToReachTheGuard() = runBlocking {
        val task = enable(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        repo.beforeGuard = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val background = async(Dispatchers.IO) { runner.attempt(task) }
        check(entered.await(5, TimeUnit.SECONDS))
        withTimeout(3000) { coordinator.configure(true, 1270) }
        release.countDown(); background.await()
        assertEquals(0, repo.submissions)
    }
    @Test fun resumeRevalidatesCredentialsEvenIfTheScreenStayedLoggedIn() = runTest {
        val task = enable(); repo.fail = ErrorKind.AUTH; runner.attempt(task)
        try { coordinator.configure(true, 1265); fail("resume must validate login") } catch (e: CheckinException) { assertEquals(ErrorKind.AUTH, e.kind) }
        assertTrue(store.read().paused.isNotEmpty())
    }
    @Test fun immediateConfirmationForADifferentPlanCannotReportSuccess() = runTest {
        val task = enable(); repo.afterPlan = "different-plan"
        runner.attempt(task)
        assertEquals(1, repo.submissions)
        assertEquals(1, confirmations.size)
        assertFalse(store.finished(task))
        assertTrue(notifications.isEmpty())
        runner.confirm(task, confirmations.single().second, 0)
        assertTrue(store.read().lastMessage.contains("计划已变化"))
    }
}
