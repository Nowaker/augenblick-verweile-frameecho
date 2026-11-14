package com.shangjin.frameecho.app.gallery

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/**
 * Tells motion photos apart from ordinary stills without decoding them, so the gallery
 * only lists pictures that really carry a video.
 *
 * Google-spec motion photos (Pixel, OnePlus/OPPO, Xiaomi, current Samsung) declare
 * themselves in XMP near the start of the file: `Camera:MotionPhoto="1"` or the legacy
 * `MicroVideo="1"`, whatever namespace prefix the camera used. Older Samsung files have
 * no XMP flag and instead end with a `MotionPhoto_Data` trailer. Both places are read
 * with a fixed byte budget, so a gallery of large photos costs a bounded amount of I/O.
 */
object MotionPhotoDetector {
    /** Covers the JPEG APP1 XMP segment and the HEIF `meta` box of camera output. */
    const val HEAD_BYTES = 256 * 1024
    const val TAIL_BYTES = 64 * 1024

    private val XMP_FLAG = Regex(
        """:(?:MotionPhoto|MicroVideo)\s*(?:=\s*["']1["']|>\s*1\s*<)"""
    )
    private const val SAMSUNG_TRAILER = "MotionPhoto_Data"

    fun hasMotionPhotoMarker(head: ByteArray, tail: ByteArray = ByteArray(0)): Boolean {
        // ISO-8859-1 maps every byte to one char, so binary data can't break the decode.
        val headText = String(head, Charsets.ISO_8859_1)
        if (XMP_FLAG.containsMatchIn(headText)) return true
        return SAMSUNG_TRAILER in headText || SAMSUNG_TRAILER in String(tail, Charsets.ISO_8859_1)
    }

    /** Reads at most [HEAD_BYTES] + [TAIL_BYTES] of [uri]. */
    suspend fun isMotionPhoto(resolver: ContentResolver, uri: Uri, sizeBytes: Long): Boolean {
        val head = resolver.openInputStream(uri)?.use { it.readUpTo(HEAD_BYTES) } ?: return false
        currentCoroutineContext().ensureActive()
        if (hasMotionPhotoMarker(head)) return true
        if (sizeBytes <= HEAD_BYTES) return false
        val tail = resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                // A provider stream that can't skip would have to be read whole: give up
                // on the trailer rather than spend unbounded I/O on one thumbnail.
                if (input.skipFully(sizeBytes - TAIL_BYTES)) input.readUpTo(TAIL_BYTES) else ByteArray(0)
            }
        } ?: return false
        return hasMotionPhotoMarker(ByteArray(0), tail)
    }

    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(buffer, count, limit - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }

    private fun InputStream.skipFully(bytes: Long): Boolean {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) return false
            remaining -= skipped
        }
        return true
    }
}
