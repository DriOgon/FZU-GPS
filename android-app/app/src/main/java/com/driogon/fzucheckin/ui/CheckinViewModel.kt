package com.driogon.fzucheckin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driogon.fzucheckin.core.*
import com.driogon.fzucheckin.automation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BusyPhase { NONE, LOGIN, REFRESH, SUBMIT, CONFIRM, CLEAR }
data class AppState(
    val loggedIn: Boolean = false,
    val username: String = "",
    val snapshot: Snapshot? = null,
    val phase: BusyPhase = BusyPhase.NONE,
    val error: String? = null,
    val notice: String? = null,
    val credentialSaved: Boolean = false,
    val automation: AutomationSettings = AutomationSettings(),
) { val busy: Boolean get() = phase != BusyPhase.NONE }

class CheckinViewModel(
    private val coordinator: CheckinCoordinator,
    private val scheduler: AutoCheckinScheduler? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    restore: Boolean = true,
) : ViewModel() {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch { coordinator.automation.state.collect { value -> mutable.update { it.copy(automation = value) } } }
        viewModelScope.launch { coordinator.snapshots.collect { snapshot ->
            mutable.update { if (it.loggedIn) it.copy(snapshot = snapshot) else it }
        } }
        if (restore) run(BusyPhase.LOGIN) {
            val saved = try { withContext(io) { coordinator.loadSaved() } } catch (_: Exception) {
                mutable.update { it.copy(error = "本机账号数据无法解密，请重新输入账号密码") }
                null
            }
            if (saved != null) authenticate(saved, persist = false)
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            mutable.update { it.copy(error = "请输入账号和密码") }
            return
        }
        run(BusyPhase.LOGIN) { authenticate(Credentials(username.trim(), password), persist = true) }
    }

    private suspend fun authenticate(account: Credentials, persist: Boolean) {
        mutable.update { it.copy(username = account.username) }
        val saved = withContext(io) { coordinator.login(account, persist) }
        // Login credentials remain useful even when the separate status query fails.
        mutable.update { it.copy(loggedIn = true, credentialSaved = saved,
            notice = if (saved) null else "已登录，但账号未能加密保存；下次打开需重新输入") }
        mutable.update { it.copy(phase = BusyPhase.REFRESH) }
        val snapshot = queryWithLogin()
        mutable.update { it.copy(snapshot = snapshot) }
    }

    private suspend fun queryWithLogin(): Snapshot = withContext(io) {
        coordinator.refresh()
    }

    fun refresh() = run(BusyPhase.REFRESH) {
        val snapshot = queryWithLogin()
        mutable.update { it.copy(snapshot = snapshot) }
    }

    fun submit(address: String) = run(BusyPhase.SUBMIT) {
        val outcome = withContext(io) {
            coordinator.submit(address) {
                mutable.update { it.copy(phase = BusyPhase.CONFIRM) }
            }
        }
        mutable.update { it.copy(snapshot = outcome.snapshot, notice = outcome.notice) }
    }

    fun clearAccount() = run(BusyPhase.CLEAR) {
        withContext(io) {
            val old = coordinator.automation.read().version
            coordinator.clearAccount()
            scheduler?.cancel(old)
        }
        mutable.value = AppState()
    }

    fun configureAutomation(enabled: Boolean, minute: Int) = run(BusyPhase.REFRESH) {
        withContext(io) {
            val old = coordinator.configure(enabled, minute)
            scheduler?.cancel(old)
            scheduler?.reconcile()
        }
        mutable.update { it.copy(notice = if (enabled) "自动签到设置已保存，执行可能受系统延迟影响"
            else "自动签到已关闭；已经发出的请求无法撤回，请刷新核实结果") }
    }

    private fun run(phase: BusyPhase, action: suspend () -> Unit) {
        if (operation?.isActive == true || mutable.value.busy) return
        mutable.update { it.copy(phase = phase, error = null, notice = null) }
        operation = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: CheckinException) {
                if (e.kind == ErrorKind.AUTH || e.kind == ErrorKind.CAPTCHA) {
                    withContext(io) {
                        val settings = coordinator.automation.read()
                        if (settings.active) coordinator.automation.message(AutoTask(settings.version, settings.generation, settings.firstDue),
                            "认证不可用，请重新登录后恢复自动签到", pause = true)
                        coordinator.clearSession()
                    }
                    mutable.update { it.copy(loggedIn = false, snapshot = null, credentialSaved = false) }
                }
                mutable.update { it.copy(error = e.message) }
            }
            catch (_: Exception) {
                mutable.update { it.copy(error = "操作未完成，请重试；如持续失败，请清除本机账号后重新登录") }
            }
            finally { mutable.update { it.copy(phase = BusyPhase.NONE) } }
        }
    }

    class Factory(private val coordinator: CheckinCoordinator, private val scheduler: AutoCheckinScheduler) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CheckinViewModel(coordinator, scheduler) as T
    }
}
