package com.imsbooks.songbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.ui.SongBookApp
import com.imsbooks.songbook.ui.theme.SongBookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = (application as SongBookApplication).container.session
        // Keep the splash screen up while a saved session is being resumed (one quick refresh call).
        installSplashScreen().setKeepOnScreenCondition { session.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SongBookTheme { SongBookApp() }
        }
    }
}
