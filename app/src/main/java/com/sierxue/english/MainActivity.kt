package com.sierxue.english

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.sierxue.english.di.AppContainer
import com.sierxue.english.ui.SiErXueApp
import com.sierxue.english.ui.components.LocalAppContainer
import com.sierxue.english.ui.theme.SiErXueTheme

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as SiErXueApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SiErXueTheme { SiErXueApp(container) }
            }
        }
    }

    override fun onStop() {
        container.localEngine.cancel()
        container.speech.stopAll()
        super.onStop()
    }
}
