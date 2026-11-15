@file:androidx.annotation.OptIn(UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package com.shangjin.frameecho.app.ui.player

import android.Manifest
import androidx.media3.common.util.UnstableApi
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import android.graphics.Bitmap
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepMath
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shangjin.frameecho.core.media.utils.LogUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.settings.AppSettings
import com.shangjin.frameecho.app.ui.components.LEGACY_ONBOARDING_STEP_KEYS
import com.shangjin.frameecho.app.ui.components.OnboardingManager
import com.shangjin.frameecho.app.ui.components.OnboardingOverlay
import com.shangjin.frameecho.app.ui.components.ThumbnailTimeline
import com.shangjin.frameecho.app.ui.components.rememberAllOnboardingSteps
import com.shangjin.frameecho.app.ui.export.ExportSettingsSheet
import com.shangjin.frameecho.app.ui.player.components.EmptyState
import com.shangjin.frameecho.app.ui.player.components.FrameInfoCard
import com.shangjin.frameecho.app.ui.player.components.PlaybackControls
import com.shangjin.frameecho.app.ui.player.components.PlayerBottomBar
import com.shangjin.frameecho.app.ui.player.components.PlayerTopBar
import com.shangjin.frameecho.app.ui.player.components.VideoSurface
import com.shangjin.frameecho.core.model.ExportResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Main player screen with M3 design — video playback and frame capture controls.
 *
 * Design choices:
 * - PlayerView built-in controller is disabled; custom M3 controls are used instead.
 * - Single progress slider (no duplicate progress bars).
 * - Bottom bar: toggle buttons (mute, motion photo, metadata) + capture FAB.
 * - Dragging slider seeks video in real-time for instant preview.
 * - ExoPlayer (Media3) is the optimal engine — only the UI layer is customized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel(),
    appSettings: AppSettings = AppSettings(),
    onOpenSystemPicker: () -> Unit = {},
    onBrowseGallery: () -> Unit = {},
    onOpenRecents: (() -> Unit)? = null,
    onOpenAppSettings: (() -> Unit)? = null,
    onNavigateToAbout: () -> Unit = {},
    /** Called after the open media was closed with Back, e.g. to return to the gallery. */
    onMediaClosed: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()

    // Onboarding state — version-based, only shows unseen steps
    val onboardingManager = remember { OnboardingManager(context) }
    val allOnboardingSteps = rememberAllOnboardingSteps()
    var unseenSteps by remember { mutableStateOf<List<com.shangjin.frameecho.app.ui.components.OnboardingStep>>(emptyList()) }
    var showOnboarding by remember { mutableStateOf(false) }
    var onboardingStep by remember { mutableIntStateOf(0) }

    // Trigger onboarding when a video is loaded — only show unseen steps
    LaunchedEffect(uiState.videoUri) {
        if (uiState.videoUri != null) {
            val allKeys = allOnboardingSteps.map { it.key }
            val unseenKeys = onboardingManager.getUnseenStepKeys(allKeys, LEGACY_ONBOARDING_STEP_KEYS).toSet()
            if (unseenKeys.isNotEmpty()) {
                unseenSteps = allOnboardingSteps.filter { it.key in unseenKeys }
                onboardingStep = 0
                delay(800)
                showOnboarding = true
            }
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // SAF folder picker launcher for custom export directory
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                LogUtils.w(context, "PlayerScreen", "Failed to take persistable URI permission", e)
            }
            viewModel.setCustomExportTreeUri(uri)
        }
    }

    // ExoPlayer instance — lifecycle-aware via DisposableEffect
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()
            .apply {
                playWhenReady = false
            }
    }

    // Back handler: return to home (clear video) instead of exiting
    BackHandler(enabled = uiState.videoUri != null) {
        if (uiState.isCapturing || uiState.isExporting) return@BackHandler
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        viewModel.clearVideoUri()
        onMediaClosed()
    }

    var pendingCaptureAfterPermission by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    // Video loading state (ExoPlayer buffering)
    var isVideoLoading by remember { mutableStateOf(false) }
    var showBufferingIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(isVideoLoading) {
        if (isVideoLoading) {
            delay(500L)
            showBufferingIndicator = true
        } else {
            showBufferingIndicator = false
        }
    }

    // Scrub sync state (shared between PlaybackControls and ThumbnailTimeline)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPreviewPositionMs by remember { mutableLongStateOf(0L) }

    // Preview frame drawn over the surface while coarse-scrubbing. Collected without `by`
    // so this composable never reads the value — only VideoSurface does, via a lambda.
    val scrubPreviewState = viewModel.scrubPreview.collectAsStateWithLifecycle()
    // True between issuing a frame-exact seek and the player actually rendering it. The
    // preview is held until then, otherwise retiring it would flash the pre-seek frame.
    var awaitingSettledFrame by remember { mutableStateOf(false) }

    // Safety net for the above: a seek to a position the player is already at renders no
    // new frame, so onRenderedFirstFrame may never come. Retire the preview regardless
    // rather than leaving it stuck over the video. Deliberately long — a slow exact seek
    // on a long-GOP 4K clip must be allowed to finish and report, since expiring early
    // shows the pre-seek frame, which is the very thing the preview exists to hide.
    LaunchedEffect(awaitingSettledFrame) {
        if (awaitingSettledFrame) {
            delay(1_500L)
            awaitingSettledFrame = false
            viewModel.clearScrubPreview()
        }
    }

    // Frame-step overlay. While stepping, the decoded preview of the frame being stepped
    // to covers the surface; during a hold it is the only thing drawing the steps. It stays
    // until the player reports that the frame on screen is the final target. There is
    // deliberately no timeout and no "first frame rendered" shortcut: a seek issued before
    // the hold can still render late, and retiring on it would flash a frame the user has
    // already left. Playback and video changes clear it as well.
    val framePreviewState = viewModel.framePreview.collectAsStateWithLifecycle()
    var frameStepHolding by remember { mutableStateOf(false) }
    var frameStepOverlayMs by remember { mutableStateOf<Long?>(null) }
    // The last preview that was the stepping target when it arrived. Kept while the next
    // target is still decoding, so the overlay never falls back to the stale player frame.
    var frameStepShownPreview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uiState.videoUri) {
        frameStepHolding = false
        frameStepOverlayMs = null
    }
    LaunchedEffect(Unit) {
        snapshotFlow { framePreviewState.value to frameStepOverlayMs }.collect { (preview, targetMs) ->
            when {
                targetMs == null -> frameStepShownPreview = null
                preview != null && preview.positionMs == targetMs -> frameStepShownPreview = preview.bitmap
            }
        }
    }
    LaunchedEffect(uiState.displayedFrameUs, frameStepHolding, frameStepOverlayMs, uiState.videoFrameRate) {
        val targetMs = frameStepOverlayMs ?: return@LaunchedEffect
        val displayedUs = uiState.displayedFrameUs ?: return@LaunchedEffect
        val rate = uiState.videoFrameRate
        if (!frameStepHolding && rate > 0f &&
            FrameStepMath.frameIndexAt((displayedUs + 500L) / 1000L, rate) == FrameStepMath.frameIndexAt(targetMs, rate)
        ) {
            frameStepOverlayMs = null
        }
    }

    // Capture flash effect
    var showCaptureFlash by remember { mutableStateOf(false) }

    val runCaptureAndSave by rememberUpdatedState(newValue = {
        if (uiState.isCapturing || uiState.isExporting) {
            Unit
        } else {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            exoPlayer.pause()
            viewModel.updatePosition(exoPlayer.currentPosition)
            viewModel.captureAndSave(uiState.exportConfig.motionPhoto)
        }
    })

    // Asked when a file is opened with metadata preservation on, never at launch: reading
    // it without this permission makes MediaStore strip location from photos and videos.
    val mediaLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean -> viewModel.onMediaLocationConsentResult(granted) }
    LaunchedEffect(uiState.awaitingMediaLocationConsent) {
        if (uiState.awaitingMediaLocationConsent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaLocationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted && pendingCaptureAfterPermission) {
            pendingCaptureAfterPermission = false
            runCaptureAndSave()
        } else {
            pendingCaptureAfterPermission = false
        }
    }

    // Camera flash effect on successful export
    LaunchedEffect(uiState.exportResult) {
        if (uiState.exportResult is ExportResult.Success) {
            showCaptureFlash = true
            delay(150)
            showCaptureFlash = false
        }
    }

    // Update ExoPlayer when video URI changes
    LaunchedEffect(uiState.videoUri) {
        uiState.videoUri?.let { uri ->
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            if (uiState.currentPositionMs > 0) {
                exoPlayer.seekTo(uiState.currentPositionMs)
            }
        }
    }

    LaunchedEffect(uiState.isMuted) {
        exoPlayer.volume = if (uiState.isMuted) 0f else 1f
    }

    // Periodically sync player position to ViewModel
    LaunchedEffect(uiState.isPlaying) {
        while (uiState.isPlaying) {
            if (!isScrubbing) {
                viewModel.updatePosition(exoPlayer.currentPosition)
            }
            delay(250L)
        }
    }

    val lastRenderedFrameUs = remember { AtomicLong(-1L) }
    val playingForFrameMetadata = remember { AtomicBoolean(false) }
    val displayedFramePosted = remember { AtomicBoolean(false) }
    LaunchedEffect(uiState.videoUri) { lastRenderedFrameUs.set(-1L) }

    // Listen to player state changes + extract frame rate from video track
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isVideoLoading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    viewModel.onPlaybackReady()
                    viewModel.updateDuration(exoPlayer.duration)
                    // Extract frame rate from ExoPlayer's selected video track
                    exoPlayer.videoFormat?.takeIf { it.frameRate > 0f }?.let { format ->
                        viewModel.updateVideoFrameRate(format.frameRate)
                    }
                    // Open the frame-step decoder early, so the first held step is a cache hit.
                    if (!exoPlayer.isPlaying) viewModel.requestFramePreview(exoPlayer.currentPosition)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // While scrubbing, every seek fires a discontinuity. Pushing those into
                // the ViewModel would recompose the whole screen per pointer event, and
                // the position shown during a drag comes from the scrub preview anyway.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !isScrubbing) {
                    viewModel.updatePosition(newPosition.positionMs)
                }
            }

            override fun onRenderedFirstFrame() {
                // Fires after every seek once the new frame is on screen — the cue to
                // hand the surface back and drop the preview overlay.
                if (awaitingSettledFrame) {
                    awaitingSettledFrame = false
                    viewModel.clearScrubPreview()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Decoder or source failures would otherwise leave a frozen black surface.
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                viewModel.onPlaybackError(error.errorCodeName)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingForFrameMetadata.set(isPlaying)
                viewModel.setPlaying(isPlaying)
                if (isPlaying) {
                    frameStepOverlayMs = null
                } else if (!isScrubbing) {
                    // Paused: the frame on screen is the selection, not the playback clock.
                    val renderedUs = lastRenderedFrameUs.get()
                    if (renderedUs >= 0L) viewModel.onPausedAtFrame(renderedUs)
                    else viewModel.updatePosition(exoPlayer.currentPosition)
                    // Warm the frame-step cache around where playback stopped.
                    viewModel.requestFramePreview(exoPlayer.currentPosition)
                }
            }
        }
        exoPlayer.addListener(listener)

        // The frame actually on screen while paused: what a capture takes and names. Called
        // on the playback thread for every rendered frame, so only the newest presentation
        // time is kept and at most one hop to the main thread is pending at a time.
        val mainHandler = Handler(Looper.getMainLooper())
        val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            lastRenderedFrameUs.set(presentationTimeUs)
            if (!playingForFrameMetadata.get() && displayedFramePosted.compareAndSet(false, true)) {
                mainHandler.post {
                    displayedFramePosted.set(false)
                    if (!playingForFrameMetadata.get()) viewModel.onDisplayedFrame(lastRenderedFrameUs.get())
                }
            }
        }
        exoPlayer.setVideoFrameMetadataListener(frameMetadataListener)

        onDispose {
            exoPlayer.clearVideoFrameMetadataListener(frameMetadataListener)
            mainHandler.removeCallbacksAndMessages(null)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pause ExoPlayer when app goes to background;
    // stop playback fully on ON_DESTROY to release codec resources promptly
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_DESTROY -> {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Localized strings for snackbar messages
    val exportSuccessMsg = stringResource(R.string.export_success_message)
    val exportFallbackMsg = stringResource(R.string.export_format_fallback_message)
    val exportAudioDroppedMsg = stringResource(R.string.export_audio_dropped_message)
    val exportFallbackAudioDroppedMsg = stringResource(R.string.export_fallback_audio_dropped_message)
    val viewActionLabel = stringResource(R.string.snackbar_action_view)

    // Show export result
    LaunchedEffect(uiState.exportResult) {
        when (val result = uiState.exportResult) {
            is ExportResult.Success -> {
                val message = when {
                    result.formatFallbackOccurred && result.audioDropped -> exportFallbackAudioDroppedMsg.format(
                        result.requestedFormat!!.name,
                        result.format.name
                    )
                    result.audioDropped -> exportAudioDroppedMsg
                    result.formatFallbackOccurred -> exportFallbackMsg.format(
                        result.requestedFormat!!.name,
                        result.format.name
                    )
                    else -> exportSuccessMsg
                }
                val snackbarResult = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = viewActionLabel,
                    duration = SnackbarDuration.Short
                )
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    try {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                result.outputPath.toUri(),
                                result.format.mimeType
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        // Security: Wrap implicit intent with chooser to prevent hijacking
                        val chooserIntent = Intent.createChooser(viewIntent, null)
                        context.startActivity(chooserIntent)
                    } catch (_: Exception) { }
                }
                viewModel.clearExportResult()
            }
            is ExportResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = result.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.clearExportResult()
            }
            null -> {}
        }
    }

    // Show errors
    val captureErrorMsg = stringResource(R.string.error_capture_failed)
    val exportErrorMsgGeneric = stringResource(R.string.error_export_failed)
    val permissionErrorMsg = stringResource(R.string.error_permission_denied)
    val noVideoMsg = stringResource(R.string.error_open_no_video)
    val malformedMsg = stringResource(R.string.error_open_malformed)
    val unreadableMsg = stringResource(R.string.error_open_unreadable)
    val playbackErrorFormat = stringResource(R.string.error_playback)
    val originalUnavailableMsg = stringResource(R.string.error_original_unavailable)
    val goToCoverLabel = stringResource(R.string.go_to_cover)
    val mediaLocationNoticeMsg = stringResource(R.string.media_location_denied_notice)
    LaunchedEffect(uiState.showMediaLocationNotice) {
        if (uiState.showMediaLocationNotice) {
            snackbarHostState.showSnackbar(message = mediaLocationNoticeMsg, duration = SnackbarDuration.Long)
            viewModel.clearMediaLocationNotice()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val message = when (error) {
                is PlayerError.CaptureFailed -> captureErrorMsg
                is PlayerError.PermissionDenied -> permissionErrorMsg
                is PlayerError.ExportFailed -> error.detail ?: exportErrorMsgGeneric
                is PlayerError.OpenFailed -> when (error.reason) {
                    OpenFailure.NO_EMBEDDED_VIDEO -> noVideoMsg
                    OpenFailure.MALFORMED_MOTION_PHOTO -> malformedMsg
                    OpenFailure.UNREADABLE -> unreadableMsg
                }
                is PlayerError.PlaybackFailed -> playbackErrorFormat.format(error.detail ?: "?")
                is PlayerError.OriginalStillUnavailable -> originalUnavailableMsg
            }
            val coverMs = viewModel.uiState.value.coverAnchorUs?.let { it / 1000L }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (error is PlayerError.OriginalStillUnavailable && coverMs != null) goToCoverLabel else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed && coverMs != null) {
                exoPlayer.pause()
                exoPlayer.setSeekParameters(SeekParameters.EXACT)
                exoPlayer.seekTo(coverMs)
                viewModel.updatePosition(coverMs)
            }
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                PlayerTopBar(
                    scrollBehavior = scrollBehavior,
                    onOpenSystemPicker = onOpenSystemPicker,
                    onOpenRecents = onOpenRecents,
                    onOpenAppSettings = onOpenAppSettings,
                    onNavigateToAbout = onNavigateToAbout,
                    onOpenExportSettings = if (uiState.videoUri != null) {
                        { viewModel.toggleExportSettings() }
                    } else null
                )
            },
            bottomBar = {
                if (uiState.videoUri != null) {
                    PlayerBottomBar(
                        isMuted = uiState.isMuted,
                        motionPhotoEnabled = uiState.exportConfig.motionPhoto,
                        preserveMetadata = uiState.exportConfig.preserveMetadata,
                        isCapturing = uiState.isCapturing,
                        isExporting = uiState.isExporting,
                        onToggleMute = { viewModel.setMuted(!uiState.isMuted) },
                        onToggleMotionPhoto = {
                            viewModel.updateExportConfig(
                                uiState.exportConfig.copy(
                                    motionPhoto = !uiState.exportConfig.motionPhoto,
                                    // Copying the original still and a Motion Photo are exclusive.
                                    originalImage = uiState.exportConfig.originalImage &&
                                        uiState.exportConfig.motionPhoto
                                )
                            )
                        },
                        onToggleMetadata = {
                            viewModel.updateExportConfig(
                                uiState.exportConfig.copy(
                                    preserveMetadata = !uiState.exportConfig.preserveMetadata
                                )
                            )
                        },
                        onCapture = {
                            if (uiState.isCapturing || uiState.isExporting) return@PlayerBottomBar
                            val requiresLegacyWritePermission =
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                    uiState.customExportTreeUri == null &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) != PackageManager.PERMISSION_GRANTED
                            if (requiresLegacyWritePermission) {
                                pendingCaptureAfterPermission = true
                                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                return@PlayerBottomBar
                            }
                            runCaptureAndSave()
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.videoUri != null) {
                    // Video Player
                    VideoSurface(
                        exoPlayer = exoPlayer,
                        isExporting = uiState.isExporting,
                        showBufferingIndicator = showBufferingIndicator,
                        isSliderDragging = isScrubbing,
                        showCaptureFlash = showCaptureFlash,
                        onTogglePlayPause = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onCancelExport = { viewModel.cancelExport() },
                        scrubPreview = {
                            frameStepShownPreview?.takeIf { frameStepOverlayMs != null } ?: scrubPreviewState.value
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    )

                    // Playback controls with fine scrubbing
                    PlaybackControls(
                        exoPlayer = exoPlayer,
                        currentPositionMs = uiState.currentPositionMs,
                        durationMs = uiState.durationMs,
                        isPlaying = uiState.isPlaying,
                        videoFrameRate = uiState.videoFrameRate,
                        isExporting = uiState.isExporting,
                        seekIntervalMs = uiState.seekIntervalMs,
                        onUpdatePosition = { viewModel.updatePosition(it) },
                        onTogglePlayPause = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onSeekTo = { exoPlayer.seekTo(it) },
                        // Warm the preview decoding session up while the finger is still
                        // on its way, so the first preview lands as early as possible.
                        onDragStarted = { viewModel.beginScrubPreview() },
                        onDragEnded = { /* preview retires when the final seek renders */ },
                        onScrubStateChanged = { scrubbing, posMs, isFine ->
                            isScrubbing = scrubbing
                            scrubPreviewPositionMs = posMs
                            // Coarse drag wants speed, so it gets the extractor's preview.
                            // Fine scrub wants the exact frame, which only the real decoder
                            // can give — drop the preview and let it show through.
                            if (scrubbing && !isFine) {
                                // Moving again — cancel the pending hand-back to the surface.
                                awaitingSettledFrame = false
                                viewModel.requestScrubPreview(posMs)
                            } else if (isFine && scrubPreviewState.value != null) {
                                viewModel.clearScrubPreview()
                            }
                        },
                        isScrubPreviewLive = { scrubPreviewState.value != null },
                        onExactSeekIssued = { awaitingSettledFrame = true },
                        holdToRepeat = appSettings.holdToRepeatEnabled,
                        repeatSchedule = appSettings.repeatSchedule,
                        isFrameStepPreviewAvailable = { viewModel.isFramePreviewWorking },
                        onFrameStep = { targetMs, moved ->
                            frameStepHolding = true
                            if (moved) {
                                frameStepOverlayMs = targetMs
                                viewModel.requestFramePreview(targetMs)
                            }
                        },
                        onFrameStepEnded = { finalMs, _ ->
                            // The overlay retires once the player displays this frame (see
                            // the displayedFrameUs effect above); nothing to wait for if
                            // the press never moved.
                            frameStepHolding = false
                            if (frameStepOverlayMs != finalMs) frameStepOverlayMs = null
                        }
                    )

                    // Thumbnail timeline strip
                    if (uiState.thumbnailCount > 0) {
                        Surface(
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val onThumbnailClick = remember(exoPlayer, viewModel) {
                                { index: Int ->
                                    val state = viewModel.uiState.value
                                    val denominator =
                                        (state.thumbnailCount - 1).coerceAtLeast(1)
                                    val newPosition = (state.durationMs * index) / denominator
                                    exoPlayer.seekTo(newPosition)
                                    viewModel.updatePosition(newPosition)
                                }
                            }

                            ThumbnailTimeline(
                                thumbnailCount = uiState.thumbnailCount,
                                // Passed as a lambda so the position read happens inside
                                // ThumbnailTimeline: a scrub then recomposes only the strip
                                // instead of this whole Column.
                                currentPositionFraction = {
                                    val durationMs = uiState.durationMs
                                    if (durationMs > 0) {
                                        val posMs =
                                            if (isScrubbing) scrubPreviewPositionMs else uiState.currentPositionMs
                                        (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                    } else 0f
                                },
                                isScrubbing = isScrubbing,
                                getThumbnail = viewModel::getThumbnail,
                                requestThumbnail = viewModel::requestThumbnail,
                                onThumbnailClick = onThumbnailClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Frame info card
                    FrameInfoCard(capturedFrame = uiState.capturedFrame)
                } else {
                    EmptyState(
                        onOpenSystemPicker = onOpenSystemPicker,
                        onBrowseGallery = onBrowseGallery,
                        isOpening = uiState.isOpening
                    )
                }
            }

            // Export settings bottom sheet
            if (uiState.showExportSettings) {
                ExportSettingsSheet(
                    // Carries the frozen file-name clock, so the previewed name is the saved one.
                    config = uiState.effectiveExportConfig,
                    onConfigChange = { viewModel.updateExportConfig(it) },
                    rememberQuickSettings = uiState.rememberQuickSettings,
                    onRememberQuickSettingsChange = { viewModel.setRememberQuickSettings(it) },
                    onDismiss = { viewModel.toggleExportSettings() },
                    customExportTreeUri = uiState.customExportTreeUri,
                    onPickCustomFolder = { folderPickerLauncher.launch(null) },
                    onClearCustomFolder = { viewModel.setCustomExportTreeUri(null) },
                    previewFrame = uiState.previewFrame,
                    originalStillAvailable = uiState.originalStillAvailable,
                    originalStillMimeType = uiState.source?.originalImageMimeType
                )
            }
        }

        // Onboarding overlay — shows only unseen steps one bubble at a time
        if (showOnboarding && uiState.videoUri != null && unseenSteps.isNotEmpty()) {
            OnboardingOverlay(
                steps = unseenSteps,
                currentStep = onboardingStep,
                onNextStep = {
                    if (onboardingStep < unseenSteps.size - 1) {
                        onboardingStep++
                    }
                },
                onSkip = {
                    showOnboarding = false
                    // Mark all steps (both seen and unseen) as seen
                    scope.launch {
                        onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                        onboardingManager.markOnboardingCompleted()
                    }
                },
                onFinish = {
                    showOnboarding = false
                    scope.launch {
                        onboardingManager.markAllSeen(allOnboardingSteps.map { it.key })
                        onboardingManager.markOnboardingCompleted()
                    }
                }
            )
        }
    }
}
