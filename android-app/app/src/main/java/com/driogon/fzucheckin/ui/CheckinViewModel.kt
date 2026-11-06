package com.driogon.fzucheckin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driogon.fzucheckin.core.*
import com.driogon.fzucheckin.data.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) { val busy: Boolean get() = phase != BusyPhase.NONE }

class CheckinViewModel(
    private val repository: CheckinRepository,
    private val store: CredentialStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    restore: Boolean = true,
) : ViewModel() {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var credentials: Credentials? = null
    private var operation: Job? = null

    init {
        if (restore) run(BusyPhase.LOGIN) {
            val saved = try { withContext(io) { store.load() } } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = "本机账号数据无法解密，请重新输入账号密码")
                null
            }
            if (saved != null) authenticate(saved, persist = false)
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            mutable.value = mutable.value.copy(error = "请输入账号和密码")
            return
        }
        run(BusyPhase.LOGIN) { authenticate(Credentials(username.trim(), password), persist = true) }
    }

    private suspend fun authenticate(account: Credentials, persist: Boolean) {
        mutable.value = mutable.value.copy(username = account.username)
        withContext(io) { repository.login(account) }
        credentials = account
        // Login credentials remain useful even when the separate status query fails.
        mutable.value = mutable.value.copy(loggedIn = true)
        if (persist) {
            try { withContext(io) { store.save(account) } }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(notice = "已登录，但账号未能加密保存；下次打开需重新输入")
            }
        }
        mutable.value = mutable.value.copy(phase = BusyPhase.REFRESH)
        val snapshot = queryWithLogin()
        mutable.value = mutable.value.copy(snapshot = snapshot)
    }

    private suspend fun queryWithLogin(): Snapshot = withContext(io) {
        try { repository.refresh() } catch (e: CheckinException) {
            if (e.kind != ErrorKind.AUTH || credentials == null) throw e
            repository.login(credentials!!)
            repository.refresh()
        }
    }

    fun refresh() = run(BusyPhase.REFRESH) {
        mutable.value = mutable.value.copy(snapshot = queryWithLogin())
    }

    fun submit(address: String) = run(BusyPhase.SUBMIT) {
        val outcome = withContext(io) {
            repository.submit(address) {
                mutable.value = mutable.value.copy(phase = BusyPhase.CONFIRM)
            }
        }
        mutable.value = mutable.value.copy(snapshot = outcome.snapshot, notice = outcome.notice)
    }

    fun clearAccount() = run(BusyPhase.CLEAR) {
        withContext(io) { store.clear(); repository.clearSession() }
        credentials = null
        mutable.value = AppState()
    }

    private fun run(phase: BusyPhase, action: suspend () -> Unit) {
        if (operation?.isActive == true || mutable.value.busy) return
        mutable.value = mutable.value.copy(phase = phase, error = null, notice = null)
        operation = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: CheckinException) {
                if (e.kind == ErrorKind.AUTH || e.kind == ErrorKind.CAPTCHA) {
                    withContext(io) { repository.clearSession() }
                    credentials = null
                    mutable.value = mutable.value.copy(loggedIn = false, snapshot = null)
                }
                mutable.value = mutable.value.copy(error = e.message)
            }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(error = "操作未完成，请重试；如持续失败，请清除本机账号后重新登录")
            }
            finally { mutable.value = mutable.value.copy(phase = BusyPhase.NONE) }
        }
    }

    class Factory(private val repository: CheckinRepository, private val store: CredentialStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CheckinViewModel(repository, store) as T
    }
}
