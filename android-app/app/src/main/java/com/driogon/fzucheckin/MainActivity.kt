package com.driogon.fzucheckin

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driogon.fzucheckin.core.FzuRepository
import com.driogon.fzucheckin.data.EncryptedCredentialStore
import com.driogon.fzucheckin.ui.CheckinViewModel
import com.driogon.fzucheckin.ui.FzuApp
import com.driogon.fzucheckin.ui.FzuTheme

class MainActivity : ComponentActivity() {
    private val model: CheckinViewModel by viewModels {
        CheckinViewModel.Factory(FzuRepository(), EncryptedCredentialStore(applicationContext))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            FzuTheme {
                FzuApp(state, model::login, model::refresh, model::submit, model::clearAccount)
            }
        }
    }
}
