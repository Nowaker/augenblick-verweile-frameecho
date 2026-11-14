package com.shangjin.frameecho.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.settings.AppSettingsStore
import com.shangjin.frameecho.app.ui.about.AboutScreen
import com.shangjin.frameecho.app.ui.player.PlayerScreen

private const val NAV_ANIM_DURATION = 350

/**
 * Navigation routes for the FrameEcho app.
 */
sealed class Screen(val route: String) {
    data object Player : Screen("player")
    data object About : Screen("about")
}

/**
 * Navigation host for the FrameEcho app.
 */
@Composable
fun FrameEchoNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsStore = remember { AppSettingsStore(context) }
    val appSettings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    NavHost(
        navController = navController,
        startDestination = Screen.Player.route,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing),
                initialOffset = { it / 4 }
            ) + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing),
                targetOffset = { it / 4 }
            ) + fadeOut(animationSpec = tween(NAV_ANIM_DURATION / 2))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing),
                initialOffset = { it / 4 }
            ) + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing),
                targetOffset = { it / 4 }
            ) + fadeOut(animationSpec = tween(NAV_ANIM_DURATION / 2))
        }
    ) {
        composable(Screen.Player.route) {
            PlayerScreen(
                appSettings = appSettings,
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
