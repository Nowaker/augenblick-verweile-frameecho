package com.shangjin.frameecho.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shangjin.frameecho.app.gallery.GalleryViewModel
import com.shangjin.frameecho.app.gallery.MediaStoreGallery
import com.shangjin.frameecho.app.incoming.IncomingMediaChooser
import com.shangjin.frameecho.app.incoming.IncomingMediaViewModel
import com.shangjin.frameecho.app.incoming.IncomingNoticeDialog
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.app.recents.RecentMediaStore
import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.settings.AppSettingsStore
import com.shangjin.frameecho.app.settings.StartScreen
import com.shangjin.frameecho.app.ui.about.AboutScreen
import com.shangjin.frameecho.app.ui.gallery.GalleryScreen
import com.shangjin.frameecho.app.ui.media.ThumbnailLoader
import com.shangjin.frameecho.app.ui.player.PlayerScreen
import com.shangjin.frameecho.app.ui.player.PlayerViewModel
import com.shangjin.frameecho.app.ui.player.components.TopBarActions
import com.shangjin.frameecho.app.ui.recents.RecentsScreen
import com.shangjin.frameecho.app.ui.settings.AppSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val NAV_ANIM_DURATION = 350

/**
 * Navigation routes for the FrameEcho app.
 */
sealed class Screen(val route: String) {
    /** Home when nothing is open, the player otherwise. */
    data object Player : Screen("player")
    data object Gallery : Screen("gallery")
    data object Recents : Screen("recents")
    data object Settings : Screen("settings")
    data object About : Screen("about")
}

/**
 * Where the app starts: the chosen start screen, unless it was opened with a file, which
 * always goes straight to the player.
 */
internal fun startRoute(settings: AppSettings, openedWithMedia: Boolean): String = when {
    openedWithMedia -> Screen.Player.route
    settings.startScreen == StartScreen.IN_APP_GALLERY -> Screen.Gallery.route
    else -> Screen.Player.route
}

/**
 * Navigation host for the FrameEcho app.
 *
 * The player state lives at this level, so a file picked on any screen (system picker,
 * gallery, recents, a share) opens in the same player.
 */
@Composable
fun FrameEchoNavHost(incoming: IncomingMediaViewModel, openedWithMedia: Boolean) {
    val context = LocalContext.current
    val settingsStore = remember { AppSettingsStore(context) }
    // The start screen is a preference, so wait for the first read (a few ms) rather than
    // flash the welcome screen before switching to the gallery.
    val initialSettings by produceState<AppSettings?>(null) { value = settingsStore.settings.first() }
    val loaded = initialSettings ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val startDestination = remember { startRoute(loaded, openedWithMedia) }
    val appSettings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = loaded)

    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val playerViewModel: PlayerViewModel = viewModel()
    // Before the open-request collector below: opening needs the resolver in place.
    LaunchedEffect(playerViewModel) { playerViewModel.initialize(context) }
    val galleryViewModel: GalleryViewModel = viewModel { GalleryViewModel(MediaStoreGallery(context.applicationContext)) }
    val thumbnails = remember { ThumbnailLoader(context) }
    val recentStore = remember { RecentMediaStore.create(context) }

    fun openMedia(uri: Uri, access: RecentAccess) {
        playerViewModel.openMedia(uri, access)
        navController.navigate(Screen.Player.route) {
            launchSingleTop = true
            popUpTo(Screen.Player.route)
        }
    }

    // ACTION_OPEN_DOCUMENT, so the read grant can be kept and recent files reopen later.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val persisted = try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: SecurityException) {
                false
            }
            openMedia(uri, if (persisted) RecentAccess.PERSISTED_GRANT else RecentAccess.TEMPORARY_GRANT)
        }
    }
    val actions = TopBarActions(
        onOpenSystemPicker = { systemPicker.launch(arrayOf("video/*", "image/*")) },
        onOpenRecents = { navController.navigateSingleTop(Screen.Recents.route) },
        onOpenAppSettings = { navController.navigateSingleTop(Screen.Settings.route) },
        onNavigateToAbout = { navController.navigateSingleTop(Screen.About.route) }
    )

    LaunchedEffect(incoming) {
        incoming.openRequests.collect { item -> openMedia(item.uri, item.access) }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
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
                viewModel = playerViewModel,
                appSettings = appSettings,
                onOpenSystemPicker = actions.onOpenSystemPicker,
                onBrowseGallery = { navController.navigateSingleTop(Screen.Gallery.route) },
                onOpenRecents = actions.onOpenRecents,
                onOpenAppSettings = actions.onOpenAppSettings,
                onNavigateToAbout = actions.onNavigateToAbout,
                onMediaClosed = {
                    // Opened from the gallery or recents: go back there.
                    if (navController.previousBackStackEntry != null) navController.popBackStack()
                    incoming.onMediaClosed()
                }
            )
        }
        composable(Screen.Gallery.route) {
            GalleryScreen(
                viewModel = galleryViewModel,
                thumbnails = thumbnails,
                actions = actions,
                onOpen = { item -> openMedia(item.uri, RecentAccess.MEDIA_STORE) },
                onNavigateBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else null
            )
        }
        composable(Screen.Recents.route) {
            RecentsScreen(
                store = recentStore,
                onlyWithSavedFrames = appSettings.recentsOnlyWithSavedFrames,
                onOnlyWithSavedFramesChange = { scope.launch { settingsStore.setRecentsOnlyWithSavedFrames(it) } },
                thumbnails = thumbnails,
                actions = actions,
                onOpen = { entry -> openMedia(entry.uri.toUri(), entry.access) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            AppSettingsScreen(
                settings = appSettings,
                onStartScreenChange = { scope.launch { settingsStore.setStartScreen(it) } },
                onHoldToRepeatEnabledChange = { scope.launch { settingsStore.setHoldToRepeatEnabled(it) } },
                onRepeatScheduleChange = { scope.launch { settingsStore.setRepeatSchedule(it) } },
                onPrecisionDialChange = { scope.launch { settingsStore.setPrecisionDialSettings(it) } },
                onNavigateToAbout = { navController.navigateSingleTop(Screen.About.route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }

    val queue by incoming.queue.collectAsStateWithLifecycle()
    if (queue.chooserVisible) {
        IncomingMediaChooser(queue = queue, onPick = incoming::pick, onClose = incoming::dismiss)
    }
    val notice by incoming.notice.collectAsStateWithLifecycle()
    notice?.let { IncomingNoticeDialog(it, onDismiss = incoming::clearNotice) }
}

private fun NavHostController.navigateSingleTop(route: String) = navigate(route) { launchSingleTop = true }
