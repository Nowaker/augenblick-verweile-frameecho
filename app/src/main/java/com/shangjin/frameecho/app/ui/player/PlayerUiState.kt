package com.shangjin.frameecho.app.ui.player

import android.graphics.Bitmap
import android.net.Uri
import com.shangjin.frameecho.app.ui.player.source.LoadedSource
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportResult
import com.shangjin.frameecho.core.model.VideoMetadata

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
    /** Video frame rate (fps) - used for frame-precise seeking in fine scrub mode */
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
            val frame = CapturedFrame(
                timestampUs = selectedFrameUs,
                width = metadata.videoWidth,
                height = metadata.videoHeight,
                colorSpace = ColorSpaceInfo(),
                metadata = if (metadata.frameRate > 0f) metadata else metadata.copy(frameRate = videoFrameRate),
                format = exportConfig.format,
                quality = exportConfig.quality
            )
            return if (exportConfig.originalImage && originalStillAvailable) source?.originalStillFrame(frame) else frame
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
