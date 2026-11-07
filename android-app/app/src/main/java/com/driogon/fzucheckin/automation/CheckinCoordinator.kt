package com.driogon.fzucheckin.automation

import com.driogon.fzucheckin.core.*
import com.driogon.fzucheckin.data.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

class StaleAutoTask : RuntimeException()
private class GuardStopped(val snapshot: Snapshot, val reason: String) : RuntimeException()
data class AutomaticOutcome(val snapshot: Snapshot, val key: String?, val notice: String? = null)

/** All session/network operations, whether initiated by the UI or workers, share this mutex. */
class CheckinCoordinator(
    private val repository: CheckinRepository,
    private val credentialsStore: CredentialStore,
    val automation: AutomationStore,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(TimeRules.china) },
) {
    private val mutex = Mutex()
    @Volatile private var credentials: Credentials? = null
    private val mutableSnapshot = MutableStateFlow<Snapshot?>(null)
    val snapshots = mutableSnapshot.asStateFlow()

    fun loadSaved(): Credentials? = try { credentialsStore.load() } catch (_: Exception) {
        throw CheckinException(ErrorKind.AUTH, "本机账号数据无法解密，请重新输入账号密码")
    }

    suspend fun login(account: Credentials, persist: Boolean): Boolean = mutex.withLock {
        repository.login(account)
        credentials = account
        automation.attachAccount(accountId(account.username))
        if (persist) try { credentialsStore.save(account) } catch (_: Exception) { return@withLock false }
        savedMatches(account)
    }

    private fun savedMatches(account: Credentials): Boolean = try {
        val saved = credentialsStore.load()
        saved?.username == account.username && saved.password == account.password
    } catch (_: Exception) { false }

    private fun query(): Snapshot {
        val snapshot = try { repository.refresh() } catch (e: CheckinException) {
            val account = credentials
            if (e.kind != ErrorKind.AUTH || account == null) throw e
            repository.login(account)
            repository.refresh()
        }
        return decorate(snapshot).also { mutableSnapshot.value = it }
    }

    private fun decorate(snapshot: Snapshot): Snapshot {
        val key = submissionKey(automation.read().accountId, snapshot) ?: return snapshot
        automation.submission(key) ?: return snapshot
        if (snapshot.status == CheckinStatus.SUCCESS || snapshot.status == CheckinStatus.PROCESSING) {
            automation.updateSubmission(key, snapshot.status.name)
            return snapshot
        }
        return if (snapshot.status in listOf(CheckinStatus.READY, CheckinStatus.NEEDS_ADDRESS))
            snapshot.copy(status = CheckinStatus.UNKNOWN_RESULT)
        else snapshot
    }

    suspend fun refresh(): Snapshot = mutex.withLock { query() }

    suspend fun submit(address: String, onConfirming: () -> Unit = {}): SubmitOutcome = mutex.withLock {
        val before = query()
        if (before.status !in listOf(CheckinStatus.READY, CheckinStatus.NEEDS_ADDRESS)) return@withLock SubmitOutcome(before)
        dispatch(address, null, currentCoroutineContext()[Job], onConfirming).also { mutableSnapshot.value = it.snapshot }
    }

    private fun dispatch(address: String, task: AutoTask?, job: Job?, onConfirming: () -> Unit = {}): SubmitOutcome {
        var reserved: String? = null
        var submittedSnapshot: Snapshot? = null
        var owner = ""
        try {
            val result = repository.submit(address, SubmissionGuard { fresh ->
                job?.ensureActive()
                if (task != null) {
                    validate(task)
                    if (!fresh.serverTimeAvailable) throw CheckinException(ErrorKind.NETWORK, "服务器时间暂不可用")
                    automaticBlock(fresh, task)?.let { throw GuardStopped(fresh, it) }
                }
                owner = automation.read().accountId
                val key = submissionKey(owner, fresh)
                    ?: throw CheckinException(ErrorKind.VALIDATION, "无法识别学校签到窗口，本次未提交")
                if (automation.submission(key) != null) throw GuardStopped(decorate(fresh), "已有提交记录，本次只查询确认")
                automation.reserve(key, task)
                reserved = key
                submittedSnapshot = fresh
            }, onConfirming)
            val checked = if (reserved != null && submissionKey(owner, result.snapshot) != reserved)
                SubmitOutcome(checkNotNull(submittedSnapshot).copy(status = CheckinStatus.UNKNOWN_RESULT), "学校计划已变化，原签到结果请手动核实")
            else result
            reserved?.let { automation.updateSubmission(it, checked.snapshot.status.name) }
            return checked
        } catch (e: GuardStopped) {
            return SubmitOutcome(e.snapshot, e.reason)
        } catch (e: Exception) {
            // A durable DISPATCHING record survives cancellation, process death and response loss.
            reserved?.let { automation.updateSubmission(it, "UNKNOWN_RESULT") }
            throw e
        }
    }

    fun validate(task: AutoTask) {
        if (!AutoTimes.current(automation.read(), task)) throw StaleAutoTask()
    }

    private fun ensureAutoSession(task: AutoTask) {
        validate(task)
        val saved = loadSaved() ?: throw CheckinException(ErrorKind.AUTH, "未找到已保存的账号，请重新登录")
        if (accountId(saved.username) != automation.read().accountId) throw StaleAutoTask()
        if (credentials != saved) { repository.login(saved); credentials = saved }
        validate(task)
    }

    suspend fun automatic(task: AutoTask): AutomaticOutcome = mutex.withLock {
        ensureAutoSession(task)
        val before = query()
        validate(task)
        if (!before.serverTimeAvailable) throw CheckinException(ErrorKind.NETWORK, "服务器时间暂不可用")
        val key = submissionKey(automation.read().accountId, before)
        val block = automaticBlock(before, task)
        if (block != null) return@withLock AutomaticOutcome(before, key, block)
        val outcome = dispatch("", task, currentCoroutineContext()[Job])
        validate(task)
        mutableSnapshot.value = outcome.snapshot
        AutomaticOutcome(outcome.snapshot, submissionKey(automation.read().accountId, outcome.snapshot), outcome.notice)
    }

    suspend fun confirm(task: AutoTask, expectedKey: String): Snapshot = mutex.withLock {
        ensureAutoSession(task)
        val snapshot = query()
        validate(task)
        if (submissionKey(automation.read().accountId, snapshot) != expectedKey)
            throw CheckinException(ErrorKind.VALIDATION, "学校计划已变化，原签到结果请手动核实")
        snapshot
    }

    /** Invalidating settings does not wait for a network request already in progress. */
    suspend fun configure(enabled: Boolean, minute: Int): Long {
        if (!enabled) {
            val previous = automation.read().version
            automation.configure(false, minute, 0)
            return previous
        }
        val account = credentials ?: throw CheckinException(ErrorKind.AUTH, "请先成功登录")
        if (!savedMatches(account)) throw CheckinException(ErrorKind.VALIDATION, "账号尚未成功加密保存，请重新登录后开启")
        if (automation.read().paused.isNotEmpty()) mutex.withLock {
            // An explicit Resume action must verify authentication again, even with an open UI.
            repository.login(account)
            credentials = account
        }
        val previous = automation.read().version
        automation.configure(true, minute, AutoTimes.millis(AutoTimes.next(clock(), minute)), accountId(account.username))
        return previous
    }

    suspend fun clearAccount() {
        automation.invalidateAccount()
        mutex.withLock {
            credentialsStore.clear(); repository.clearSession(); credentials = null
            automation.clearRecords(); mutableSnapshot.value = null
        }
    }

    suspend fun clearSession() = mutex.withLock { repository.clearSession(); credentials = null }
}
