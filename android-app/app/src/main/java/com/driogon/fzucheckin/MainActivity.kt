package com.driogon.fzucheckin

import android.graphics.Color
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driogon.fzucheckin.ui.CheckinViewModel
import com.driogon.fzucheckin.ui.FzuApp
import com.driogon.fzucheckin.ui.FzuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: CheckinViewModel by viewModels {
        val services = (application as FzuApplication).services
        CheckinViewModel.Factory(services.coordinator, services.scheduler)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.decorView.isForceDarkAllowed = false
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.BLACK),
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            FzuTheme {
                FzuApp(state, model::login, model::refresh, model::submit, model::clearAccount, model::configureAutomation)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as FzuApplication
        app.backgroundScope.launch { try { app.services.scheduler.reconcile() } catch (_: Exception) { } }
    }
}
