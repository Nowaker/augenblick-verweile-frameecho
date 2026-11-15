package com.shangjin.frameecho.core.media.extraction

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Size
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Keeps forward reference frames in the codec instead of repeatedly seeking and rebuilding a GL graph. */
internal class VideoBufferPreviewReader(
    private val source: MediaExtractor,
    private val sourceFormat: MediaFormat,
    private val outputSize: Size
) {
    private val codec = MediaCodec.createDecoderByType(requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME)))
    private val info = MediaCodec.BufferInfo()
    private var outputFormat = sourceFormat
    private var inputEnded = false
    private var queuedInput = false
    private var last: DecodedFrame? = null
    private var released = false

    init {
        try {
            val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in codec.codecInfo.getCapabilitiesForType(mime).colorFormats) {
                throw UnsupportedPreviewPixels()
            }
            sourceFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()
        } catch (failure: Throwable) {
            codec.release()
            throw failure
        }
    }

    suspend fun read(targetUs: Long, syncUs: Long): DecodedFrame {
        val cached = last
        if (cached?.presentationTimeUs == targetUs) return ownedCopy(cached)
        if (cached != null && (targetUs < cached.presentationTimeUs || targetUs - cached.presentationTimeUs > 2_000_000L)) {
            seekWithReferences(syncUs)
        } else if (cached == null && targetUs > 2_000_000L) {
            seekWithReferences(syncUs)
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        val coroutine = currentCoroutineContext()
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutine.ensureActive()
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(inputIndex))
                    val size = source.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        if (source.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw UnsupportedPreviewPixels()
                        val flags = if (source.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                        codec.queueInputBuffer(inputIndex, 0, size, source.sampleTime, flags)
                        queuedInput = true
                        source.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
                if (sourceFormat.containsKey(MediaFormat.KEY_ROTATION)) outputFormat.setInteger(MediaFormat.KEY_ROTATION, sourceFormat.getInteger(MediaFormat.KEY_ROTATION))
                for (key in listOf(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER)) {
                    if (!outputFormat.containsKey(key) && sourceFormat.containsKey(key)) outputFormat.setInteger(key, sourceFormat.getInteger(key))
                }
            } else if (outputIndex >= 0) {
                try {
                    if (info.size > 0 && info.presentationTimeUs >= targetUs) {
                        val image = codec.getOutputImage(outputIndex) ?: throw UnsupportedPreviewPixels()
                        val bitmap = image.use { yuvPreviewBitmap(it, outputFormat, outputSize) }
                        val decoded = DecodedFrame(bitmap, info.presentationTimeUs)
                        last?.bitmap?.recycle()
                        last = decoded
                        return ownedCopy(decoded)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        throw IOException("The requested preview frame was not decoded")
                    }
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        throw IOException("Timed out decoding a video preview")
    }

    private fun seekWithReferences(syncUs: Long) {
        source.seekTo(syncUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (queuedInput) codec.flush()
        inputEnded = false
        queuedInput = false
    }

    private fun ownedCopy(frame: DecodedFrame): DecodedFrame =
        DecodedFrame(requireNotNull(frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)), frame.presentationTimeUs)

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } finally {
            try {
                codec.release()
            } finally {
                source.release()
                last?.bitmap?.recycle()
                last = null
            }
        }
    }
}
