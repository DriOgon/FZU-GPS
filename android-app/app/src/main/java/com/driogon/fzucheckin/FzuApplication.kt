package com.driogon.fzucheckin

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.driogon.fzucheckin.automation.*
import com.driogon.fzucheckin.core.FzuRepository
import com.driogon.fzucheckin.data.EncryptedCredentialStore
import kotlinx.coroutines.*

class AppServices(context: Context) {
    val store = AutomationStore(context)
    val coordinator = CheckinCoordinator(FzuRepository(), EncryptedCredentialStore(context), store)
    val scheduler = AutoCheckinScheduler(context, store)
    private val notifier = ResultNotifier(context)
    val runner = AutomationRunner(coordinator, scheduler, notifier::show)
}

class FzuApplication : Application() {
    val services by lazy { AppServices(this) }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        backgroundScope.launch { try { services.scheduler.reconcile() } catch (_: Exception) { /* Reconciled again on foreground. */ } }
    }
}

class ClockChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        val pending = goAsync()
        val app = context.applicationContext as FzuApplication
        app.backgroundScope.launch {
            try { app.services.scheduler.reconcile() } finally { pending.finish() }
        }
    }
}
