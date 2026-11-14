package com.shangjin.frameecho.app.ui.player.source

import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess
import com.shangjin.frameecho.core.model.CaptureTime
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.VideoMetadata
import kotlin.math.abs

/**
 * What the user opened, as opposed to what the player plays. For a motion photo the
 * player gets an extracted temporary video, while recents, filenames and metadata keep
 * referring to the original picture.
 */
data class LoadedSource(
    val sourceUri: Uri,
    val displayName: String,
    val mimeType: String?,
    val access: RecentAccess,
    /** Capture metadata of the original still; null for a plain video. */
    val sourceMetadata: VideoMetadata?,
    /** Where in the embedded video the still was taken; null when the file doesn't say. */
    val coverPresentationUs: Long?,
    val originalImageLength: Long?,
    val originalImageMimeType: String?
) {
    val isMotionPhoto: Boolean get() = originalImageLength != null

    /**
     * The video position the still was taken at, when the file records it. An unknown
     * cover is not guessed: a guess would pass off an invented position as capture-time
     * precision, and could not prove that a frame is the original still.
     */
    val coverAnchorUs: Long? get() = if (isMotionPhoto) coverPresentationUs else null

    /** Whether the encoded still can be copied byte for byte (JPEG and HEIC primaries). */
    val canCopyOriginalStill: Boolean
        get() = originalImageLength != null && originalImageMimeType?.lowercase() in COPYABLE_STILL_TYPES

    /**
     * Metadata for a frame of this source. Technical video fields come from the decoded
     * video; descriptive fields (location, camera, EXIF) come from the original still
     * when there is one. The still's capture date is used only with a known cover, as
     * stillDate + (frame - cover); otherwise the embedded video's own date, anchored at
     * its start, stays (or there is none, and the exporter reports the missing time).
     * The file name is always the original's, never the temporary extracted video's.
     */
    fun metadataFor(decoded: VideoMetadata): VideoMetadata {
        val still = sourceMetadata ?: return decoded.copy(sourceFileName = displayName)
        val anchorUs = coverAnchorUs
        val stillDate = still.dateTime?.takeIf { anchorUs != null }
        return decoded.copy(
            dateTime = stillDate ?: decoded.dateTime,
            dateTimePositionUs = if (stillDate != null && anchorUs != null) anchorUs else decoded.dateTimePositionUs,
            latitude = still.latitude ?: decoded.latitude,
            longitude = still.longitude ?: decoded.longitude,
            altitude = still.altitude ?: decoded.altitude,
            make = still.make ?: decoded.make,
            model = still.model ?: decoded.model,
            iso = still.iso ?: decoded.iso,
            exposureTime = still.exposureTime ?: decoded.exposureTime,
            fNumber = still.fNumber ?: decoded.fNumber,
            focalLength = still.focalLength ?: decoded.focalLength,
            exifAttributes = decoded.exifAttributes + still.exifAttributes,
            sourceFileName = displayName
        )
    }

    /**
     * Whether the frame at [frameUs] is the still itself, so "copy original still" is
     * honest: within half a frame of the cover anchor.
     */
    fun isAtCover(frameUs: Long, frameRate: Float): Boolean {
        val anchorUs = coverAnchorUs ?: return false
        val toleranceUs = if (frameRate > 0f) (500_000.0 / frameRate).toLong() else DEFAULT_COVER_TOLERANCE_US
        return abs(frameUs - anchorUs) <= toleranceUs
    }

    /**
     * The frame handed to the original-still export. It represents the still, not the
     * nearest decoded video sample, so its timestamp is the cover anchor, and its date is
     * restated at the anchor so the capture time stays the same whatever position the
     * metadata was originally anchored at.
     */
    fun originalStillFrame(frame: CapturedFrame): CapturedFrame? {
        val anchorUs = coverAnchorUs ?: return null
        val atAnchor = CaptureTime.atFrame(frame.metadata, anchorUs)?.toIsoString()
        return frame.copy(
            timestampUs = anchorUs,
            metadata = frame.metadata.copy(
                dateTime = atAnchor ?: frame.metadata.dateTime,
                dateTimePositionUs = anchorUs
            )
        )
    }

    private companion object {
        val COPYABLE_STILL_TYPES = setOf("image/jpeg", "image/heic", "image/heif")
        const val DEFAULT_COVER_TOLERANCE_US = 16_666L
    }
}
