package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Size
import com.shangjin.frameecho.core.media.colorspace.ColorSpaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

internal class PreviewFrameReader(
    private val context: Context,
    private val videoUri: Uri,
    private val outputSize: Size
) {
    private val timestamps = FrameTimestampIndex(context)
    private var buffer: VideoBufferPreviewReader? = null
    private var graphics: ExactFrameReader? = null

    suspend fun read(positionUs: Long): DecodedFrame = withContext(Dispatchers.IO) {
        if (buffer == null && graphics == null) prepare()
        val exactUs = timestamps.nearest(videoUri, positionUs)
        val syncUs = timestamps.syncAtOrBefore(videoUri, exactUs)
        try {
            buffer?.read(exactUs, syncUs) ?: requireNotNull(graphics).read(exactUs)
        } catch (unsupported: UnsupportedPreviewPixels) {
            buffer?.release()
            buffer = null
            val reader = ExactFrameReader(context, videoUri, outputSize).also { graphics = it }
            reader.read(exactUs)
        }
    }

    private fun prepare() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("The selected file has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val color = ColorSpaceDetector.detect(format)
            if (color.isHdr ||
                (format.containsKey(MediaFormat.KEY_COLOR_STANDARD) && format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020)) {
                extractor.release()
                graphics = ExactFrameReader(context, videoUri, outputSize)
            } else {
                buffer = VideoBufferPreviewReader(extractor, format, outputSize)
            }
        } catch (unsupported: UnsupportedPreviewPixels) {
            extractor.release()
            graphics = ExactFrameReader(context, videoUri, outputSize)
        } catch (failure: Throwable) {
            extractor.release()
            throw failure
        }
    }

    suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            buffer?.release()
            buffer = null
        } finally {
            graphics?.release()
            graphics = null
        }
    }
}
