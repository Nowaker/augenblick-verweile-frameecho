package com.shangjin.frameecho.core.media.extraction

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import com.shangjin.frameecho.core.media.utils.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A decoded preview at a requested frame position, independent of full-resolution export. */
data class FramePreview(val positionMs: Long, val bitmap: Bitmap)

/**
 * Keeps one decoder open and preloads a small window around paused frame steps.
 * The worker alone owns the retriever and cache. Published bitmaps are left to
 * the GC because a draw can still reference them after cache eviction.
 */
class AdjacentFramePreview(
    private val context: Context,
    private val videoUri: Uri,
    scope: CoroutineScope
) : Closeable {
    private data class Request(val positionMs: Long, val frameRate: Float, val durationMs: Long)

    private val requests = Channel<Request>(Channel.CONFLATED)
    private val _preview = MutableStateFlow<FramePreview?>(null)
    val preview: StateFlow<FramePreview?> = _preview.asStateFlow()

    private val worker: Job = scope.launch(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val cache = LinkedHashMap<Long, Bitmap>(16, 0.75f, true)
        var cacheBytes = 0L
        try {
            retriever.setDataSource(context, videoUri)
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val scale = minOf(1.0, MAX_PREVIEW_DIMENSION.toDouble() / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            var pending: Request? = null
            var previousPositionMs = 0L

            fun decode(positionUs: Long): Bitmap? {
                cache[positionUs]?.let { return it }
                val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        positionUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height
                    )
                } else {
                    retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { full ->
                        val scaled = full.scale(width, height)
                        if (scaled !== full) full.recycle()
                        scaled
                    }
                } ?: return null
                val bytes = bitmap.allocationByteCount.toLong()
                if (bytes <= MAX_CACHE_BYTES) {
                    while (cache.isNotEmpty() &&
                        (cache.size >= MAX_CACHE_FRAMES || cacheBytes + bytes > MAX_CACHE_BYTES)
                    ) {
                        val iterator = cache.entries.iterator()
                        cacheBytes -= iterator.next().value.allocationByteCount
                        iterator.remove()
                    }
                    cache[positionUs] = bitmap
                    cacheBytes += bytes
                }
                return bitmap
            }

            while (true) {
                val request = pending ?: requests.receiveCatching().getOrNull() ?: break
                pending = null
                ensureActive()
                val requestedUs = request.positionMs * 1_000L
                val bitmap = decode(requestedUs)
                ensureActive()
                pending = requests.tryReceive().getOrNull()
                if (pending == null && bitmap != null) {
                    _preview.value = FramePreview(request.positionMs, bitmap)
                }
                val direction = if (request.positionMs < previousPositionMs) -1 else 1
                previousPositionMs = request.positionMs
                if (pending != null) continue

                for (positionUs in adjacentTimestampsUs(
                    request.positionMs, request.frameRate, request.durationMs, direction
                )) {
                    ensureActive()
                    pending = requests.tryReceive().getOrNull()
                    if (pending != null) break
                    decode(positionUs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _preview.value = null
            LogUtils.w(context, "AdjacentFramePreview", "Frame preview unavailable", e)
        } finally {
            requests.close()
            cache.clear()
            try {
                retriever.release()
            } catch (e: Exception) {
                LogUtils.w(context, "AdjacentFramePreview", "Failed to release preview decoder", e)
            }
        }
    }

    fun request(positionMs: Long, frameRate: Float, durationMs: Long) {
        if (durationMs <= 0L) return
        val clamped = positionMs.coerceIn(0L, durationMs - 1L)
        requests.trySend(Request(clamped, frameRate, durationMs))
    }

    /** Cancel immediately; the worker releases its decoder after any native decode returns. */
    override fun close() {
        requests.close()
        worker.cancel()
        _preview.value = null
    }

    /** Await decoder release before retiring an extracted motion-photo video. */
    suspend fun release() {
        close()
        worker.cancelAndJoin()
    }

    companion object {
        const val MAX_PREVIEW_DIMENSION = 960
        const val MAX_CACHE_FRAMES = 12
        const val MAX_CACHE_BYTES = 16L * 1024L * 1024L

        internal fun adjacentTimestampsUs(
            positionMs: Long,
            frameRate: Float,
            durationMs: Long,
            direction: Int
        ): List<Long> {
            if (!frameRate.isFinite() || frameRate <= 0f || durationMs <= 0L) return emptyList()
            val currentIndex = (positionMs.toDouble() * frameRate / 1_000.0).roundToLong()
            val endUs = durationMs * 1_000L
            // Round frame indices back to milliseconds exactly as the player does.
            // Repeatedly adding a truncated 33 ms at 29.97 fps would miss cache keys.
            return listOf(1, 2, 3, 4, -1, -2).mapNotNull { offset ->
                val index = currentIndex + offset * direction
                if (index < 0L) return@mapNotNull null
                val timestampUs = (index * 1_000.0 / frameRate).roundToLong() * 1_000L
                timestampUs.takeIf { it in 0L until endUs && it != positionMs * 1_000L }
            }.distinct()
        }
    }
}
