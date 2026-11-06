package com.driogon.fzucheckin.ui

import com.driogon.fzucheckin.core.*
import com.driogon.fzucheckin.data.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Store : CredentialStore {
        var account: Credentials? = null
        override fun load() = account
        override fun save(credentials: Credentials) { account = credentials }
        override fun clear() { account = null }
    }
    private class Repo : CheckinRepository {
        var logins = 0; var reads = 0; var submissions = 0; var clears = 0
        var expireOnce = false
        val sample = Snapshot("测试同学", "测试学院", "p", 1, "21:00", "23:00", "22:00",
            ZonedDateTime.parse("2026-11-06T21:30:00+08:00"), true, CheckinStatus.READY, Campus("c", "测试校区"))
        override fun login(credentials: Credentials) { logins++ }
        override fun refresh(): Snapshot {
            reads++
            if (expireOnce) { expireOnce = false; throw CheckinException(ErrorKind.AUTH, "登录过期") }
            return sample
        }
        override fun submit(address: String, onConfirming: () -> Unit): SubmitOutcome {
            submissions++; onConfirming()
            return SubmitOutcome(sample.copy(status = CheckinStatus.SUCCESS))
        }
        override fun clearSession() { clears++ }
    }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun restoringAccountOnlyQueriesAndNeverSubmits() = runTest(dispatcher) {
        val store = Store().apply { account = Credentials("synthetic", "synthetic-password") }
        val repo = Repo()
        val vm = CheckinViewModel(repo, store, dispatcher)
        advanceUntilIdle()
        assertTrue(vm.state.value.loggedIn)
        assertEquals(1, repo.logins)
        assertEquals(1, repo.reads)
        assertEquals(0, repo.submissions)
    }

    @Test fun repeatedClicksAreIgnoredWhileAnOperationIsActive() = runTest(dispatcher) {
        val repo = Repo(); val vm = CheckinViewModel(repo, Store(), dispatcher, restore = false)
        vm.login("synthetic", "synthetic-password"); advanceUntilIdle()
        vm.submit(""); vm.submit(""); advanceUntilIdle()
        assertEquals(1, repo.submissions)
        assertEquals(CheckinStatus.SUCCESS, vm.state.value.snapshot!!.status)
    }

    @Test fun expiredQueryMayLoginAgainButNeverReplaysSubmission() = runTest(dispatcher) {
        val repo = Repo(); val vm = CheckinViewModel(repo, Store(), dispatcher, restore = false)
        vm.login("synthetic", "synthetic-password"); advanceUntilIdle()
        repo.expireOnce = true
        vm.refresh(); advanceUntilIdle()
        assertEquals(2, repo.logins)
        assertEquals(0, repo.submissions)
    }

    @Test fun changingAccountClearsStoredCredentialsSessionAndScreenData() = runTest(dispatcher) {
        val repo = Repo(); val store = Store(); val vm = CheckinViewModel(repo, store, dispatcher, restore = false)
        vm.login("synthetic", "synthetic-password"); advanceUntilIdle()
        vm.clearAccount(); advanceUntilIdle()
        assertNull(store.account)
        assertFalse(vm.state.value.loggedIn)
        assertNull(vm.state.value.snapshot)
        assertEquals("", vm.state.value.username)
        assertEquals(1, repo.clears)
    }
}
