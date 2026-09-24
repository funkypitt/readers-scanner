package com.freedomfighter.readersscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readersscanner.ui.DocScreen
import com.freedomfighter.readersscanner.ui.FolderScreen
import com.freedomfighter.readersscanner.ui.HomeScreen
import com.freedomfighter.readersscanner.ui.LocalColors
import com.freedomfighter.readersscanner.ui.Nav
import com.freedomfighter.readersscanner.ui.ReaderTheme
import com.freedomfighter.readersscanner.ui.Screen
import com.freedomfighter.readersscanner.ui.SearchScreen
import com.freedomfighter.readersscanner.ui.SettingsScreen
import com.freedomfighter.readersscanner.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                // A scan just filed: show it in its folder.
                val reveal = app.reveal.value
                LaunchedEffect(reveal) {
                    if (reveal != null) {
                        app.reveal.value = null
                        nav.home()
                        nav.push(Screen.FolderView(reveal.first.ifEmpty { null }))
                    }
                }
                when (val s = nav.current) {
                    Screen.Home -> HomeScreen(nav, app)
                    is Screen.FolderView -> FolderScreen(nav, app, s.folder)
                    is Screen.DocView -> DocScreen(nav, app, s.id)
                    is Screen.Viewer -> ViewerScreen(nav, s.id, s.page)
                    Screen.Search -> SearchScreen(nav)
                    is Screen.Compare -> com.freedomfighter.readersscanner.ui.CompareScreen(nav, s.id)
                    Screen.Settings -> SettingsScreen(nav, app)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.resumes.intValue++
        if (app.prefs.settings.value.syncOnOpen) app.sync()
    }

    @Composable
    private fun Bars() {
        val colors = LocalColors.current
        val view = LocalView.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }
}
