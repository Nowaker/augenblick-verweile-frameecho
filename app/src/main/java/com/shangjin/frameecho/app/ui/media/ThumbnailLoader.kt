package com.shangjin.frameecho.app.ui.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Grid thumbnails with a byte-bounded memory cache and at most [MAX_PARALLEL_LOADS]
 * decodes at once. A tile that scrolls away cancels its load: the coroutine is cancelled
 * and, on API 29+, so is the provider's own thumbnail work through [CancellationSignal].
 */
class ThumbnailLoader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext
    private val permits = Semaphore(MAX_PARALLEL_LOADS)
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(uri: Uri): Bitmap? = cache.get(uri.toString())

    /** Returns null when the file has no decodable picture or can no longer be read. */
    suspend fun load(uri: Uri, isVideo: Boolean, sizePx: Int): Bitmap? {
        cached(uri)?.let { return it }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    decode(uri, isVideo, sizePx)?.also { cache.put(uri.toString(), it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private suspend fun decode(uri: Uri, isVideo: Boolean, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            val signal = CancellationSignal()
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { signal.cancel() }
            try {
                return resolver.loadThumbnail(uri, Size(sizePx, sizePx), signal)
            } catch (e: android.os.OperationCanceledException) {
                throw CancellationException("Thumbnail load cancelled").apply { initCause(e) }
            } catch (_: Exception) {
                // Providers without thumbnail support fall through to decoding the file.
            } finally {
                cancelHandle?.dispose()
            }
        } else if (uri.authority == MediaStore.AUTHORITY) {
            legacyMediaStoreThumbnail(uri, isVideo)?.let { return it }
        }
        return if (isVideo) videoFrame(uri, sizePx) else sampledImage(uri, sizePx)
    }

    @Suppress("DEPRECATION")
    private fun legacyMediaStoreThumbnail(uri: Uri, isVideo: Boolean): Bitmap? {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 } ?: return null
        return if (isVideo) {
            MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    private fun videoFrame(uri: Uri, sizePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return frame.scaledToFit(sizePx)
        } finally {
            retriever.release()
        }
    }

    private fun sampledImage(uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.scaledToFit(sizePx)
    }

    private fun Bitmap.scaledToFit(sizePx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= sizePx) return this
        val factor = sizePx.toFloat() / longest
        val scaled = scale((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
        if (scaled !== this) recycle()
        return scaled
    }

    private companion object {
        const val MAX_PARALLEL_LOADS = 3
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
