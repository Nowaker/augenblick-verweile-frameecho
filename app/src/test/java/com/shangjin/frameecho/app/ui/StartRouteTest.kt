package com.shangjin.frameecho.app.ui

import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.settings.StartScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class StartRouteTest {
    @Test
    fun `start screen preference decides, unless the app was opened with a file`() {
        assertEquals(Screen.Player.route, startRoute(AppSettings(startScreen = StartScreen.WELCOME), false))
        assertEquals(Screen.Gallery.route, startRoute(AppSettings(startScreen = StartScreen.IN_APP_GALLERY), false))
        assertEquals(Screen.Player.route, startRoute(AppSettings(startScreen = StartScreen.IN_APP_GALLERY), true))
    }
}
