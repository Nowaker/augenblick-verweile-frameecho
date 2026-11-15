package com.shangjin.frameecho.app.ui.player

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.app.recents.RecentMediaRecorder
import com.shangjin.frameecho.app.recents.RecentMediaStore
import com.shangjin.frameecho.app.ui.player.source.LoadedSource
import com.shangjin.frameecho.core.media.extraction.AdjacentFramePreview
import com.shangjin.frameecho.core.media.extraction.FrameExtractor
import com.shangjin.frameecho.core.media.extraction.FramePreview
import com.shangjin.frameecho.core.media.extraction.ScrubPreviewExtractor
import com.shangjin.frameecho.core.media.export.FrameExporter
import com.shangjin.frameecho.core.media.metadata.MetadataExtractor
import com.shangjin.frameecho.core.media.source.MediaSourceException
import com.shangjin.frameecho.core.media.source.MediaSourceResolver
import com.shangjin.frameecho.core.media.source.OriginalMediaAccess
import com.shangjin.frameecho.core.media.source.ResolvedMedia
import com.shangjin.frameecho.core.media.utils.LogUtils
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Represents user-facing error types. Mapped to localized strings in the UI layer.
 */
sealed class PlayerError {
    data object CaptureFailed : PlayerError()
    data object PermissionDenied : PlayerError()
    data class ExportFailed(val detail: String? = null) : PlayerError()
    /** The picked file could not be opened; [detail] is the platform's explanation. */
    data class OpenFailed(val reason: OpenFailure, val detail: String? = null) : PlayerError()
    /** The player could not decode the media, e.g. an unsupported codec. */
    data class PlaybackFailed(val detail: String? = null) : PlayerError()
    /** Original-still export asked for away from the cover frame. */
    data object OriginalStillUnavailable : PlayerError()
}

/** ACCESS_MEDIA_LOCATION as it bears on reading location metadata. */
enum class MediaLocationConsent {
    /** Before Android 10 location is never redacted, so there is nothing to ask. */
    NOT_APPLICABLE,
    GRANTED,
    NOT_GRANTED
}

enum class OpenFailure {
    /** A still picture without an embedded video (including Apple Live Photo stills). */
    NO_EMBEDDED_VIDEO,
    MALFORMED_MOTION_PHOTO,
    /** The file is gone or the read grant has ended. */
    UNREADABLE
}

/**
 * UI state for the player screen.
 */
data class PlayerUiState(
    val videoUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val rememberQuickSettings: Boolean = true,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val capturedFrame: CapturedFrame? = null,
    val capturedBitmap: Bitmap? = null,
    val isCapturing: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: ExportResult? = null,
    val exportConfig: ExportConfig = ExportConfig(),
    val showExportSettings: Boolean = false,
    val error: PlayerError? = null,
    /** Number of thumbnails to display in the timeline */
    val thumbnailCount: Int = 0,
    /** Custom export directory URI from SAF picker */
    val customExportTreeUri: Uri? = null,
    /** Video frame rate (fps) — used for frame-precise seeking in fine scrub mode */
    val videoFrameRate: Float = 0f,
    /** What the user opened; null while nothing is open or for media set directly. */
    val source: LoadedSource? = null,
    /** True while a picked file is being resolved into something playable. */
    val isOpening: Boolean = false,
    /** Presentation time of the frame on screen while paused, as reported by the player. */
    val displayedFrameUs: Long? = null,
    /** Metadata a capture of the open media would carry, original still merged in. */
    val frameMetadata: VideoMetadata? = null,
    /**
     * Wall clock frozen for the `{timestamp}` part of the next file name. Set when the
     * shown frame changes and after each save, never persisted, so the name previewed
     * for a frame is exactly the name the save writes.
     */
    val fileNameTimestampMs: Long = 0L,
    /**
     * A picked file waits for the user to allow or refuse location metadata access
     * before it is read, so its metadata is read unredacted from the start.
     */
    val awaitingMediaLocationConsent: Boolean = false,
    /** Location access was refused: say once that location may be missing. */
    val showMediaLocationNotice: Boolean = false
) {
    /** The frame a capture takes: the one on screen when known, else the position. */
    val selectedFrameUs: Long
        get() = displayedFrameUs ?: (currentPositionMs * 1000L)

    /** Video position of the motion photo's still, when the open media is one. */
    val coverAnchorUs: Long?
        get() = source?.coverAnchorUs

    /** Whether the encoded original still can be copied for the frame on screen. */
    val originalStillAvailable: Boolean
        get() = source?.canCopyOriginalStill == true &&
            source.isAtCover(selectedFrameUs, videoFrameRate)

    /** Export options as a save would use them, with the frozen file-name clock. */
    val effectiveExportConfig: ExportConfig
        get() = exportConfig.copy(fileNameTimestampMs = fileNameTimestampMs)

    /**
     * The frame a save would export right now, for previews such as the file name.
     * Null until the media's metadata is known.
     */
    val previewFrame: CapturedFrame?
        get() {
            val metadata = frameMetadata ?: return null
            return CapturedFrame(
                timestampUs = selectedFrameUs,
                width = metadata.videoWidth,
                height = metadata.videoHeight,
                colorSpace = ColorSpaceInfo(),
                metadata = if (metadata.frameRate > 0f) metadata else metadata.copy(frameRate = videoFrameRate),
                format = exportConfig.format,
                quality = exportConfig.quality
            )
        }

    /**
     * Adaptive seek interval: ~5% of total duration, rounded to a "nice" value.
     * Clamped to [1s, 30s].
     */
    val seekIntervalMs: Long
        get() {
            if (durationMs <= 0) return 1_000L
            val raw = (durationMs * 0.05).toLong()
            // Round to nearest nice step: 1, 2, 5, 10, 15, 20, 30
            val nice = longArrayOf(1000, 2000, 5000, 10_000, 15_000, 20_000, 30_000)
            return nice.minByOrNull { kotlin.math.abs(it - raw) }!!
                .coerceIn(1_000L, 30_000L)
        }

}

/**
 * ViewModel for the player screen.
 * Manages video playback state, frame capture/export, and thumbnail timeline.
 *
 * Thumbnail strategy: thumbnails span the FULL video duration so they load once
 * and remain stable. Auto-scroll follows the playback position smoothly.
 */
class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * Preview frame shown over the video while the seek bar is being dragged.
     *
     * Deliberately kept out of [PlayerUiState]: it changes many times per drag, and folding
     * it into the main state would recompose the whole player screen every time.
     */
    private val _scrubPreview = MutableStateFlow<Bitmap?>(null)
    val scrubPreview: StateFlow<Bitmap?> = _scrubPreview.asStateFlow()

    /**
     * Decoded preview of the frame most recently requested by frame stepping. The screen
     * draws it only while its [FramePreview.positionMs] is the frame being stepped to, so
     * a slow decode never shows a frame other than the one the readout names.
     */
    private val _framePreview = MutableStateFlow<FramePreview?>(null)
    val framePreview: StateFlow<FramePreview?> = _framePreview.asStateFlow()
    private var adjacentFramePreview: AdjacentFramePreview? = null
    private var framePreviewForwardJob: Job? = null

    /**
     * True while the frame-step decoder has a usable preview for the open video.
     * Otherwise held repeats seek the player instead.
     */
    var isFramePreviewWorking: Boolean = false
        private set

    private var frameExtractor: FrameExtractor? = null
    private var frameExporter: FrameExporter? = null
    private var resolveMedia: (suspend (Uri) -> ResolvedMedia)? = null
    private var loadMetadata: (suspend (Uri) -> VideoMetadata)? = null
    private var recentMedia: RecentMediaRecorder? = null
    private var clock: () -> Long = System::currentTimeMillis
    private var mediaLocationConsent: () -> MediaLocationConsent = { MediaLocationConsent.NOT_APPLICABLE }

    /** The open waiting on [PlayerUiState.awaitingMediaLocationConsent]. */
    private var pendingOpen: Pair<Uri, RecentAccess>? = null
    /** Refused once this session: open without asking again until the app restarts. */
    private var mediaLocationRefused = false

    /** Owns the extracted motion-photo video of the open source, if any. */
    private var currentResolved: ResolvedMedia? = null
    private var openJob: Job? = null
    private var metadataJob: Job? = null
    private var decodedMetadata: VideoMetadata? = null
    private var openRecordedFor: LoadedSource? = null
    private var preferencesStore: PlayerPreferencesStore? = null
    private var appContext: Context? = null

    /** Thumbnail cache: index → Bitmap. Kept outside UiState to avoid serialization issues. */
    private val _thumbnailCache = mutableStateMapOf<Int, Bitmap>()
    private var batchThumbnailJob: Job? = null
    private var captureAndSaveJob: Job? = null
    private var activeBitmapExportRefCount: Int = 0

    // ── Scrub preview ───────────────────────────────────────────────────
    private var scrubPreviewExtractor: ScrubPreviewExtractor? = null
    private var scrubPreviewJob: Job? = null
    private var scrubPreviewIdleJob: Job? = null
    /** Conflated: while one frame is being extracted, only the newest target survives. */
    private var scrubRequests: Channel<Long>? = null
    /**
     * Bumped whenever the preview is retired. An extraction that was already running
     * carries the generation it started under, so its result is discarded instead of
     * popping back over the video after the player has taken the surface back.
     */
    private var scrubPreviewGeneration: Int = 0

    companion object {
        /** Width of each thumbnail in the timeline strip (px) */
        const val THUMBNAIL_WIDTH_PX = 120
        /**
         * Thumbnail count scales with video duration: ~1 thumbnail per second
         * for short videos, tapering off for longer ones using a square-root curve.
         * Clamped to [10, 150].
         */
        fun thumbnailCountForDuration(durationMs: Long): Int {
            if (durationMs <= 0) return 10
            val durationS = durationMs / 1000.0
            // sqrt curve: gives ~10 for 5s, ~28 for 30s, ~55 for 2min, ~77 for 5min, ~110 for 10min
            return (5.0 * kotlin.math.sqrt(durationS)).toInt().coerceIn(10, 150)
        }

        /**
         * How long the preview decoding session is kept open after a drag ends.
         * Keeping it means back-to-back drags stay instant; releasing it eventually
         * gives the decoder and GL context back, which matters on devices with few
         * concurrent codec instances.
         */
        const val SCRUB_PREVIEW_IDLE_RELEASE_MS = 10_000L

        /** Rounding between millisecond positions and microsecond presentation times. */
        private const val DISPLAYED_FRAME_SLACK_MS = 1.0
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val permissionContext = context.applicationContext
        mediaLocationConsent = {
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> MediaLocationConsent.NOT_APPLICABLE
                OriginalMediaAccess.canReadOriginal(permissionContext) -> MediaLocationConsent.GRANTED
                else -> MediaLocationConsent.NOT_GRANTED
            }
        }
        if (frameExtractor == null) {
            frameExtractor = FrameExtractor(context.applicationContext)
            frameExporter = FrameExporter(context.applicationContext)
        }
        if (resolveMedia == null) {
            resolveMedia = MediaSourceResolver(context.applicationContext)::resolve
        }
        if (loadMetadata == null) {
            val appContext = context.applicationContext
            loadMetadata = { uri -> withContext(Dispatchers.IO) { MetadataExtractor.extract(appContext, uri) } }
        }
        if (recentMedia == null) {
            recentMedia = RecentMediaStore.create(context.applicationContext)
        }
        if (preferencesStore == null) {
            preferencesStore = PlayerPreferencesStore(context.applicationContext)
            applyPersistedQuickSettings(context.applicationContext)
        }
    }

    /**
     * Inject dependencies for testing.
     */
    fun setDependenciesForTesting(extractor: FrameExtractor, exporter: FrameExporter) {
        this.frameExtractor = extractor
        this.frameExporter = exporter
    }

    /**
     * Test-only seams for opening media: the resolver, metadata reader, recent files
     * and the wall clock used for file names.
     */
    internal fun setSourceDependenciesForTesting(
        resolve: suspend (Uri) -> ResolvedMedia,
        metadata: suspend (Uri) -> VideoMetadata,
        recents: RecentMediaRecorder?,
        clock: () -> Long
    ) {
        this.resolveMedia = resolve
        this.loadMetadata = metadata
        this.recentMedia = recents
        this.clock = clock
    }

    /**
     * Open what the user picked. Pictures are resolved to their embedded motion video,
     * videos play as they are; a still without a video, or a file that can no longer be
     * read, ends in [PlayerError.OpenFailed] instead of a player error.
     */
    /** Test-only seam for the ACCESS_MEDIA_LOCATION state. */
    internal fun setMediaLocationConsentForTesting(consent: () -> MediaLocationConsent) {
        mediaLocationConsent = consent
    }

    /**
     * Open what the user picked, from any route. With metadata preservation on, a
     * content file on Android 10+ first waits for the location-access answer (asked at
     * most once per session after a refusal), because MediaStore redacts location from
     * reads made without it and the resolver caches what it reads.
     */
    fun openMedia(uri: Uri, access: RecentAccess) {
        if (resolveMedia == null) return
        val needsConsent = _uiState.value.exportConfig.preserveMetadata &&
            !mediaLocationRefused &&
            mediaLocationConsent() == MediaLocationConsent.NOT_GRANTED &&
            uri.scheme == ContentResolver.SCHEME_CONTENT
        if (!needsConsent) {
            resolveAndShow(uri, access)
            return
        }
        clearVideoUri()
        pendingOpen = uri to access
        _uiState.update { it.copy(isOpening = true, awaitingMediaLocationConsent = true) }
    }

    /**
     * The answer to the location-access request. Either way the waiting file opens; a
     * refusal only means Android or its provider may leave location out, which is said
     * once, and no other path is used to get it anyway.
     */
    fun onMediaLocationConsentResult(granted: Boolean) {
        val (uri, access) = pendingOpen ?: return
        pendingOpen = null
        if (!granted) mediaLocationRefused = true
        _uiState.update {
            it.copy(awaitingMediaLocationConsent = false, showMediaLocationNotice = it.showMediaLocationNotice || !granted)
        }
        resolveAndShow(uri, access)
    }

    fun clearMediaLocationNotice() {
        _uiState.update { it.copy(showMediaLocationNotice = false) }
    }

    private fun resolveAndShow(uri: Uri, access: RecentAccess) {
        val resolve = resolveMedia ?: return
        openJob?.cancel()
        clearVideoUri()
        _uiState.update { it.copy(isOpening = true) }
        openJob = viewModelScope.launch {
            val resolved = try {
                resolve(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaSourceException) {
                failOpen(
                    when (e.reason) {
                        MediaSourceException.Reason.NO_EMBEDDED_VIDEO -> OpenFailure.NO_EMBEDDED_VIDEO
                        MediaSourceException.Reason.MALFORMED_MOTION_PHOTO -> OpenFailure.MALFORMED_MOTION_PHOTO
                    },
                    e.message
                )
                return@launch
            } catch (e: Exception) {
                failOpen(OpenFailure.UNREADABLE, e.message)
                return@launch
            }
            try {
                val mimeType = appContext?.let { context ->
                    withContext(Dispatchers.IO) { runCatching { context.contentResolver.getType(uri) }.getOrNull() }
                }
                val source = LoadedSource(
                    sourceUri = resolved.sourceUri,
                    displayName = resolved.sourceFileName,
                    mimeType = mimeType,
                    access = access,
                    sourceMetadata = resolved.sourceMetadata,
                    coverPresentationUs = resolved.presentationTimestampUs,
                    originalImageLength = resolved.originalImageLength,
                    originalImageMimeType = resolved.originalImageMimeType
                )
                showMedia(resolved.videoUri, source, resolved)
            } catch (e: CancellationException) {
                // Superseded by another pick: this one never reached the player.
                if (currentResolved !== resolved) resolved.release()
                throw e
            }
        }
    }

    private fun failOpen(reason: OpenFailure, detail: String?) {
        _uiState.update { it.copy(isOpening = false, error = PlayerError.OpenFailed(reason, detail)) }
    }

    fun setVideoUri(uri: Uri) = showMedia(uri, source = null, resolved = null)

    private fun showMedia(uri: Uri, source: LoadedSource?, resolved: ResolvedMedia?) {
        // Capture reference BEFORE updating state so we can recycle safely
        // after the StateFlow no longer exposes the old bitmap.
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        releaseMediaSessions()
        currentResolved = resolved

        _uiState.update {
            it.copy(
                videoUri = uri,
                source = source,
                // A motion photo opens on its still when the file says where that is.
                currentPositionMs = source?.coverAnchorUs?.div(1000L) ?: it.currentPositionMs,
                isOpening = false,
                capturedBitmap = null,
                capturedFrame = null,
                error = null,
                thumbnailCount = 0,
                videoFrameRate = 0f,
                displayedFrameUs = null,
                frameMetadata = null,
                fileNameTimestampMs = clock()
            )
        }
        loadFrameMetadata(uri)

        // Recycle only when not exporting — the export coroutine may still
        // reference the bitmap on Dispatchers.IO.
        if (canRecycleImmediately) {
            oldBitmap?.recycle()
        }
    }

    /**
     * Clear the video URI and return to home state.
     */
    fun clearVideoUri() {
        openJob?.cancel()
        openJob = null
        pendingOpen = null
        val oldBitmap = _uiState.value.capturedBitmap
        val canRecycleImmediately = !isAnyExportRunning()
        releaseMediaSessions()
        _uiState.update {
            PlayerUiState(
                isMuted = it.isMuted,
                rememberQuickSettings = it.rememberQuickSettings,
                exportConfig = it.exportConfig,
                customExportTreeUri = it.customExportTreeUri,
                showMediaLocationNotice = it.showMediaLocationNotice
            )
        }
        // Recycle after state update so no observer can read a recycled bitmap.
        if (canRecycleImmediately) {
            oldBitmap?.recycle()
        }
    }

    /**
     * Stop everything reading the current media, then delete the extracted motion-photo
     * video once the decoders that opened it have actually let go.
     */
    private fun releaseMediaSessions() {
        val capture = captureAndSaveJob
        val thumbnails = batchThumbnailJob
        val scrub = scrubPreviewJob
        cancelExport()
        clearThumbnailCache()
        releaseScrubPreview()
        val framePreviewReleased = releaseFramePreview()
        metadataJob?.cancel()
        metadataJob = null
        decodedMetadata = null
        val retired = currentResolved ?: return
        currentResolved = null
        viewModelScope.launch {
            listOfNotNull(capture, thumbnails, scrub, framePreviewReleased).forEach { it.join() }
            retired.release()
        }
    }

    private fun loadFrameMetadata(uri: Uri) {
        val load = loadMetadata ?: return
        metadataJob = viewModelScope.launch {
            val decoded = try {
                load(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appContext?.let { LogUtils.w(it, "PlayerViewModel", "Metadata unavailable", e) }
                VideoMetadata()
            }
            decodedMetadata = decoded
            refreshFrameMetadata()
        }
    }

    private fun refreshFrameMetadata() {
        val decoded = decodedMetadata ?: return
        _uiState.update { state -> state.copy(frameMetadata = state.source?.metadataFor(decoded) ?: decoded) }
    }

    /** Called once the player has prepared the media: a successful open for recents. */
    fun onPlaybackReady() {
        val source = _uiState.value.source ?: return
        if (openRecordedFor === source) return
        openRecordedFor = source
        val recents = recentMedia ?: return
        viewModelScope.launch {
            recents.recordOpened(source.sourceUri, source.displayName, source.mimeType, source.access)
        }
    }

    /** The player could not play the media: return home and say why. */
    fun onPlaybackError(detail: String?) {
        clearVideoUri()
        _uiState.update { it.copy(error = PlayerError.PlaybackFailed(detail)) }
    }

    /**
     * The player rendered a frame with presentation time [presentationUs]. Only tracked
     * while paused, where it defines what a capture takes and what the file is named.
     *
     * An exact seek renders the first frame at or after its position, so a frame is
     * accepted from just before the position up to one frame after it. Anything else is
     * a late render of an older seek (say, the tap that started a backward hold) and
     * would make a capture take a frame the user already left.
     */
    fun onDisplayedFrame(presentationUs: Long) {
        val state = _uiState.value
        if (state.isPlaying || state.displayedFrameUs == presentationUs) return
        if (state.videoFrameRate > 0f) {
            val aheadMs = presentationUs / 1000.0 - state.currentPositionMs
            val frameMs = 1000.0 / state.videoFrameRate
            if (aheadMs < -DISPLAYED_FRAME_SLACK_MS || aheadMs > frameMs - DISPLAYED_FRAME_SLACK_MS) return
        }
        _uiState.update { it.copy(displayedFrameUs = presentationUs, fileNameTimestampMs = clock()) }
    }

    /** Playback paused on the frame with presentation time [presentationUs]. */
    fun onPausedAtFrame(presentationUs: Long) {
        _uiState.update {
            it.copy(
                currentPositionMs = (presentationUs + 500L) / 1000L,
                displayedFrameUs = presentationUs,
                fileNameTimestampMs = if (it.displayedFrameUs == presentationUs) it.fileNameTimestampMs else clock()
            )
        }
    }

    fun updatePosition(positionMs: Long) {
        _uiState.update { state ->
            // A seek elsewhere makes the last rendered frame stale until the next one lands.
            val frameMs = if (state.videoFrameRate > 0f) 1000.0 / state.videoFrameRate else 0.0
            val displayed = state.displayedFrameUs?.takeIf { abs(it / 1000.0 - positionMs) <= frameMs.coerceAtLeast(1.0) }
            state.copy(currentPositionMs = positionMs, displayedFrameUs = displayed)
        }
    }

    fun updateDuration(durationMs: Long) {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        if (safeDurationMs == _uiState.value.durationMs && _uiState.value.thumbnailCount > 0) return
        _uiState.update {
            it.copy(
                durationMs = safeDurationMs,
                thumbnailCount = thumbnailCountForDuration(safeDurationMs)
            )
        }
        loadThumbnailsForFullVideo()
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing, displayedFrameUs = if (playing) null else it.displayedFrameUs) }
    }

    fun updateVideoFrameRate(frameRate: Float) {
        if (frameRate > 0f && frameRate != _uiState.value.videoFrameRate) {
            _uiState.update { it.copy(videoFrameRate = frameRate) }
        }
    }

    fun setCustomExportTreeUri(uri: Uri?) {
        _uiState.update { it.copy(customExportTreeUri = uri) }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun setMuted(muted: Boolean) {
        _uiState.update { state ->
            state.copy(
                isMuted = muted,
                exportConfig = state.exportConfig.copy(muteAudio = muted)
            )
        }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun setRememberQuickSettings(enabled: Boolean) {
        _uiState.update { it.copy(rememberQuickSettings = enabled) }
        viewModelScope.launch {
            preferencesStore?.setRememberQuickSettings(enabled)
            if (enabled) {
                persistQuickSettingsIfEnabled()
            }
        }
    }

    // ── Thumbnail timeline ──────────────────────────────────────────────

    /**
     * Get a cached thumbnail, or null if not yet loaded.
     */
    fun getThumbnail(index: Int): Bitmap? {
        return _thumbnailCache[index]
    }

    /**
     * Request a thumbnail for the given timeline index.
     * Triggers batch loading if not already loaded.
     */
    fun requestThumbnail(index: Int) {
        if (index !in 0 until _uiState.value.thumbnailCount) return
        if (_thumbnailCache.containsKey(index)) return
        loadThumbnailsForFullVideo()
    }

    /**
     * Load all missing thumbnails spanning the full video duration.
     * Thumbnails are evenly distributed across the entire video, so they
     * load once and remain stable throughout playback.
     *
     * All missing thumbnails are extracted in a single batch call so the
     * resource-heavy `MediaMetadataRetriever` (and its native decoder) is
     * created and released only once instead of once per chunk.
     */
    private fun loadThumbnailsForFullVideo() {
        if (batchThumbnailJob?.isActive == true) return

        val uri = _uiState.value.videoUri ?: return
        val durationMs = _uiState.value.durationMs
        if (durationMs <= 0) return
        val extractor = frameExtractor ?: return

        batchThumbnailJob = viewModelScope.launch {
            val denominator = (_uiState.value.thumbnailCount - 1).coerceAtLeast(1)

            val missingIndices = (0 until _uiState.value.thumbnailCount).filter {
                !_thumbnailCache.containsKey(it)
            }
            if (missingIndices.isEmpty()) return@launch

            ensureActive()

            val indexToTimestamp = missingIndices.associateWith { index ->
                val positionMs = (durationMs * index) / denominator
                positionMs * 1000L
            }

            try {
                val results = extractor.extractThumbnails(
                    uri,
                    indexToTimestamp.values.toList(),
                    THUMBNAIL_WIDTH_PX
                )

                val timestampToBitmap = results.toMap()
                indexToTimestamp.forEach { (index, timestampUs) ->
                    timestampToBitmap[timestampUs]?.let { bitmap ->
                        _thumbnailCache[index] = bitmap
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appContext?.let { ctx ->
                    LogUtils.w(ctx, "PlayerViewModel", "Failed to load thumbnails", e)
                }
            }
        }
    }

    /**
     * Clear thumbnail cache.
     *
     * @param recycleBitmaps If true, recycles all cached bitmaps immediately.
     *   Use true only when no composables can reference them (e.g., onCleared).
     *   Use false during video change to avoid race conditions with composables
     *   that may still reference the bitmaps — GC will handle cleanup.
     */
    private fun clearThumbnailCache(recycleBitmaps: Boolean = false) {
        batchThumbnailJob?.cancel()
        batchThumbnailJob = null
        if (recycleBitmaps) {
            _thumbnailCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        _thumbnailCache.clear()
    }

    // ── Scrub preview ───────────────────────────────────────────────────

    /**
     * Open the preview session as a drag begins.
     *
     * Called on drag start rather than lazily on the first frame request so the decoding
     * session is warming up while the finger is still on its way.
     */
    fun beginScrubPreview() {
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = null
        // Drop whatever the last drag left behind. A leftover frame would both show the
        // wrong moment and, because the UI treats a live preview as "no need to seek the
        // player", keep the surface frozen until the first fresh frame arrives.
        scrubPreviewGeneration++
        _scrubPreview.value = null
        startScrubPreviewWorker()
    }

    /**
     * Ask for a preview at [positionMs]. Never blocks and never queues up: if an
     * extraction is already running, this replaces whatever target was waiting behind it,
     * so the preview always heads for where the finger is now.
     */
    fun requestScrubPreview(positionMs: Long) {
        startScrubPreviewWorker()
        scrubRequests?.trySend(positionMs)
    }

    /**
     * Drop the preview overlay, revealing the real (now exactly seeked) video surface.
     * The decoding session stays open for [SCRUB_PREVIEW_IDLE_RELEASE_MS] in case the
     * user drags again.
     */
    fun clearScrubPreview() {
        scrubPreviewGeneration++
        _scrubPreview.value = null
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = viewModelScope.launch {
            delay(SCRUB_PREVIEW_IDLE_RELEASE_MS)
            releaseScrubPreview()
        }
    }

    private fun startScrubPreviewWorker() {
        if (scrubPreviewJob?.isActive == true) return
        val uri = _uiState.value.videoUri ?: return
        val context = appContext ?: return

        val requests = Channel<Long>(Channel.CONFLATED)
        scrubRequests = requests
        scrubPreviewJob = viewModelScope.launch {
            val extractor = scrubPreviewExtractor ?: ScrubPreviewExtractor(context).also {
                scrubPreviewExtractor = it
            }
            try {
                extractor.prepare(uri)
                // Awaiting each frame before taking the next request is what paces this:
                // one extraction in flight, newest target wins. Firing requests faster
                // than the decoder can serve them only builds a backlog the user then
                // has to wait out.
                for (positionMs in requests) {
                    val generation = scrubPreviewGeneration
                    val bitmap = extractor.frameAt(positionMs) ?: continue
                    // The preview was retired while this frame was being decoded — the
                    // player owns the surface again, so this result is no longer wanted.
                    if (generation != scrubPreviewGeneration) continue
                    _scrubPreview.value = bitmap
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Preview is an optimisation, not a requirement — the UI falls back to
                // seeking the player when no preview arrives.
                appContext?.let { ctx ->
                    LogUtils.w(ctx, "PlayerViewModel", "Scrub preview unavailable", e)
                }
                _scrubPreview.value = null
            } finally {
                // The worker owns the session for its whole lifetime. Without this, a
                // worker that ends on its own — an extraction failure, or the request
                // channel closing — would leave a dead extractor cached in the field for
                // the next drag to pick up and keep failing on, holding its decoder and
                // GL context until the video changed.
                //
                // release() is idempotent, so this and the deterministic release in
                // releaseScrubPreview() can't conflict; whichever runs first wins.
                extractor.release()
                if (scrubPreviewExtractor === extractor) {
                    scrubPreviewExtractor = null
                }
            }
        }
    }

    /**
     * Preview frames are deliberately never recycled.
     *
     * A frame that has been published can still be referenced by a composition, or by a
     * draw pass already in flight when the state flips — recycling it races that draw and
     * crashes. Since API 26 (this app's minSdk) bitmap pixels live on the native heap and
     * are freed when the Bitmap becomes unreachable, so an explicit recycle buys nothing
     * here. They are small by construction: [ScrubPreviewExtractor] downscales to a 480px
     * short side on the GPU, and at most a couple are reachable at a time.
     */
    private fun releaseScrubPreview() {
        scrubPreviewIdleJob?.cancel()
        scrubPreviewIdleJob = null
        scrubRequests?.close()
        scrubRequests = null
        scrubPreviewJob?.cancel()
        scrubPreviewJob = null
        scrubPreviewExtractor?.release()
        scrubPreviewExtractor = null
        scrubPreviewGeneration++
        _scrubPreview.value = null
    }

    // ── Frame-step preview ──────────────────────────────────────────────

    /**
     * Decode [positionMs] for the frame-step overlay and prefetch its neighbours. Called
     * on every step and when playback pauses, so the next step is usually a cache hit.
     */
    fun requestFramePreview(positionMs: Long) {
        val state = _uiState.value
        val uri = state.videoUri ?: return
        val context = appContext ?: return
        if (state.videoFrameRate <= 0f || state.durationMs <= 0L) return
        val preview = adjacentFramePreview ?: AdjacentFramePreview(context, uri, viewModelScope).also { created ->
            adjacentFramePreview = created
            framePreviewForwardJob = viewModelScope.launch {
                created.preview.collect {
                    isFramePreviewWorking = it != null
                    _framePreview.value = it
                }
            }
        }
        preview.request(positionMs, state.videoFrameRate, state.durationMs)
    }

    /**
     * Close the frame-step preview decoder. Returns the job that completes once the
     * native decoder has really let go, for callers that must wait before deleting
     * the file it reads.
     */
    private fun releaseFramePreview(): Job? {
        framePreviewForwardJob?.cancel()
        framePreviewForwardJob = null
        isFramePreviewWorking = false
        val preview = adjacentFramePreview ?: return null
        adjacentFramePreview = null
        preview.close()
        _framePreview.value = null
        return viewModelScope.launch { preview.release() }
    }

    // ── Frame capture ───────────────────────────────────────────────────

    /**
     * One-tap capture and save: captures the current frame and immediately exports it.
     *
     * @param motionPhoto whether to export as a motion photo or static image
     */
    fun captureAndSave(motionPhoto: Boolean) {
        if (captureAndSaveJob?.isActive == true ||
            _uiState.value.isCapturing ||
            _uiState.value.isExporting
        ) return
        val uri = _uiState.value.videoUri ?: return
        val extractor = frameExtractor ?: return
        val exporter = frameExporter ?: return

        captureAndSaveJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, isExporting = true, error = null, exportResult = null) }

            try {
                // Snapshot once: the name previewed for this frame and its metadata must
                // be what the save writes, even if state moves on while it runs.
                val selection = _uiState.value
                val source = selection.source
                val config = selection.effectiveExportConfig
                val wantsOriginal = config.originalImage && !motionPhoto
                if (wantsOriginal && !selection.originalStillAvailable) {
                    _uiState.update {
                        it.copy(isCapturing = false, isExporting = false, error = PlayerError.OriginalStillUnavailable)
                    }
                    return@launch
                }
                val result = extractor.extractFrameWithInfo(uri, selection.selectedFrameUs)

                if (result != null) {
                    val (bitmap, extracted) = result
                    val frame = source?.let { extracted.copy(metadata = it.metadataFor(extracted.metadata)) }
                        ?: extracted
                    // Capture old reference BEFORE updating state,
                    // then recycle AFTER so no observer reads a recycled bitmap.
                    val oldBitmap = _uiState.value.capturedBitmap
                    _uiState.update {
                        it.copy(
                            capturedBitmap = bitmap,
                            capturedFrame = frame,
                            isCapturing = false
                        )
                    }
                    oldBitmap?.let { extractor.releaseBitmap(it) }

                    val customTreeUri = _uiState.value.customExportTreeUri

                    // Export immediately
                    val exportResult = try {
                        beginBitmapExportUsage()
                        val originalFrame = if (wantsOriginal) source?.originalStillFrame(frame) else null
                        if (originalFrame != null && source != null) {
                            exporter.exportOriginalImage(
                                sourceUri = source.sourceUri,
                                imageLength = requireNotNull(source.originalImageLength),
                                mimeType = requireNotNull(source.originalImageMimeType),
                                frame = originalFrame,
                                config = config,
                                customExportTreeUri = customTreeUri
                            )
                        } else if (motionPhoto) {
                            val motionConfig = config.copy(motionPhoto = true, originalImage = false)
                            exporter.exportMotionPhoto(
                                videoUri = uri,
                                bitmap = bitmap,
                                frame = frame,
                                config = motionConfig,
                                customExportTreeUri = customTreeUri
                            )
                        } else {
                            exporter.exportStaticFrame(
                                bitmap = bitmap,
                                frame = frame,
                                config = config.copy(originalImage = false),
                                customExportTreeUri = customTreeUri
                            )
                        }
                    } finally {
                        endBitmapExportUsage()
                    }
                    _uiState.update {
                        it.copy(isExporting = false, exportResult = exportResult)
                    }
                    if (exportResult is ExportResult.Success) {
                        // The next save is a new selection with its own name.
                        _uiState.update { it.copy(fileNameTimestampMs = clock()) }
                        if (source != null) {
                            recentMedia?.recordSaved(source.sourceUri, source.displayName, source.mimeType, source.access)
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            isExporting = false,
                            error = PlayerError.CaptureFailed
                        )
                    }
                }
            } catch (e: CancellationException) {
                // User cancelled or scope cancelled — reset state, don't show error
                _uiState.update { it.copy(isCapturing = false, isExporting = false) }
                throw e
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        isExporting = false,
                        error = PlayerError.PermissionDenied
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        isExporting = false,
                        error = PlayerError.ExportFailed(e.message)
                    )
                }
            } finally {
                captureAndSaveJob = null
            }
        }
    }



    // ── Export ───────────────────────────────────────────────────────────

    fun updateExportConfig(config: ExportConfig) {
        _uiState.update { state ->
            // The file-name clock belongs to the frame selection, not to the settings.
            state.copy(exportConfig = config.copy(muteAudio = state.isMuted, fileNameTimestampMs = null))
        }
        viewModelScope.launch {
            persistQuickSettingsIfEnabled()
        }
    }

    fun toggleExportSettings() {
        _uiState.update { it.copy(showExportSettings = !it.showExportSettings) }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Cancel the ongoing capture-and-save operation.
     */
    fun cancelExport() {
        captureAndSaveJob?.cancel()
        captureAndSaveJob = null
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel running jobs FIRST — the export coroutine may still hold a
        // reference to capturedBitmap on Dispatchers.IO (e.g. inside
        // Bitmap.compress).  Recycling the bitmap while the IO work is
        // in-flight would cause a use-after-recycle crash.
        captureAndSaveJob?.cancel()
        // Do NOT recycle capturedBitmap here: the cancelled export coroutine
        // may still be executing non-cancellable blocking IO that references
        // the bitmap.  The Bitmap finalizer will recycle it once the last
        // reference is released by GC.
        // Recycle thumbnail bitmaps — no composables reference them after ViewModel destruction
        clearThumbnailCache(recycleBitmaps = true)
        // Same for the preview session: it owns a decoder and a GL context.
        releaseScrubPreview()
        // viewModelScope is already cancelled, which stops the worker and releases its decoder.
        adjacentFramePreview?.close()
        adjacentFramePreview = null
        // Nothing can read the extracted video any more once the scope is gone.
        currentResolved?.release()
        currentResolved = null
    }

    private fun beginBitmapExportUsage() {
        activeBitmapExportRefCount += 1
    }

    private fun endBitmapExportUsage() {
        if (activeBitmapExportRefCount > 0) {
            activeBitmapExportRefCount -= 1
        }
    }

    private fun isBitmapInUseByExport(): Boolean {
        return activeBitmapExportRefCount > 0
    }

    private fun isAnyExportRunning(): Boolean {
        return _uiState.value.isExporting ||
            captureAndSaveJob?.isActive == true ||
            isBitmapInUseByExport()
    }

    private fun applyPersistedQuickSettings(context: Context) {
        viewModelScope.launch {
            val persisted = preferencesStore?.load() ?: return@launch
            _uiState.update { state ->
                if (!persisted.rememberQuickSettings) {
                    state.copy(
                        isMuted = false,
                        rememberQuickSettings = false,
                        exportConfig = state.exportConfig.copy(muteAudio = false)
                    )
                } else {
                    val savedFormat = persisted.format?.let { name ->
                        try { ExportFormat.valueOf(name) } catch (e: Exception) { null }
                    } ?: state.exportConfig.format

                    val savedDirectory = persisted.exportDirectory?.let { name ->
                        try { ExportDirectory.valueOf(name) } catch (e: Exception) { null }
                    } ?: state.exportConfig.exportDirectory

                    val savedTreeUriStr = persisted.customExportTreeUri
                    val validatedUri = if (!savedTreeUriStr.isNullOrEmpty()) {
                        val parsed = Uri.parse(savedTreeUriStr)
                        val hasPermission = try {
                            context.contentResolver.persistedUriPermissions.any { perm ->
                                perm.uri == parsed && perm.isWritePermission
                            }
                        } catch (e: Exception) {
                            false
                        }
                        if (hasPermission) parsed else null
                    } else null

                    state.copy(
                        isMuted = persisted.isMuted,
                        rememberQuickSettings = true,
                        customExportTreeUri = validatedUri,
                        exportConfig = state.exportConfig.copy(
                            format = savedFormat,
                            quality = persisted.quality,
                            preserveMetadata = persisted.preserveMetadata,
                            motionPhoto = persisted.motionPhoto,
                            customFileName = persisted.customFileName,
                            exportDirectory = savedDirectory,
                            muteAudio = persisted.isMuted
                        )
                    )
                }
            }
        }
    }

    private suspend fun persistQuickSettingsIfEnabled() {
        val state = _uiState.value
        if (!state.rememberQuickSettings) return
        preferencesStore?.saveQuickSettings(
            isMuted = state.isMuted,
            motionPhoto = state.exportConfig.motionPhoto,
            preserveMetadata = state.exportConfig.preserveMetadata,
            format = state.exportConfig.format.name,
            quality = state.exportConfig.quality,
            customFileName = state.exportConfig.customFileName,
            exportDirectory = state.exportConfig.exportDirectory.name,
            customExportTreeUri = state.customExportTreeUri?.toString()
        )
    }
}
