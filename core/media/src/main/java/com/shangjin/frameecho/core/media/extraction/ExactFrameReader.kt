@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DecodedFrame(val bitmap: Bitmap, val presentationTimeUs: Long)

/** Decodes reference-dependent frames without the platform retriever's open-GOP seek corruption. */
internal class ExactFrameReader(
    private val context: Context,
    private val videoUri: Uri,
    private val outputSize: Size? = null
) {
    private val presentationTimeUs = AtomicLong(-1L)
    private var extractor: FrameExtractor? = null

    suspend fun read(positionUs: Long): DecodedFrame = withContext(Dispatchers.Main.immediate) {
        val reader = extractor ?: createExtractor().also { extractor = it }
        val frame = withTimeoutOrNull(30_000L) { reader.getFrame(positionUs.coerceAtLeast(0L) / 1_000L).awaitFrame() }
            ?: throw IOException("Timed out decoding the selected video frame")
        val actualUs = presentationTimeUs.get()
        check(actualUs >= 0L) { "The decoder did not report the captured frame timestamp" }
        // Inspector retains its last frame for duplicate seeks; callers own and may recycle this copy.
        val bitmap = requireNotNull(frame.bitmap.copy(frame.bitmap.config ?: Bitmap.Config.ARGB_8888, false))
        DecodedFrame(bitmap, actualUs)
    }

    suspend fun release() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        extractor?.close()
        extractor = null
    }

    private fun createExtractor(): FrameExtractor {
        // The public frame result exposes only milliseconds. This identity effect observes the
        // exact PTS of the same frame immediately before Inspector reads its pixels.
        val effects = buildList {
            add(MatrixTransformation { timestampUs ->
                presentationTimeUs.set(timestampUs)
                Matrix()
            })
            outputSize?.let { size ->
                add(Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }
        val item = MediaItem.Builder().setUri(videoUri).setMediaId(UUID.randomUUID().toString()).build()
        val builder = FrameExtractor.Builder(context, item)
            .setSeekParameters(SeekParameters.EXACT)
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            .setEffects(effects)
        if (outputSize == null && Build.VERSION.SDK_INT >= 34) builder.setExtractHdrFrames(true)
        return builder.build()
    }
}

internal suspend fun decodeExactFrame(context: Context, videoUri: Uri, positionUs: Long): DecodedFrame {
    val reader = ExactFrameReader(context, videoUri)
    return try {
        reader.read(positionUs)
    } finally {
        reader.release()
    }
}

private suspend fun ListenableFuture<FrameExtractor.Frame>.awaitFrame(): FrameExtractor.Frame =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(false) }
        addListener({
            if (continuation.isActive) {
                try {
                    continuation.resume(get())
                } catch (failure: ExecutionException) {
                    continuation.resumeWithException(failure.cause ?: failure)
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    continuation.cancel(cancelled)
                }
            }
        }, Executor { it.run() })
    }
