package com.shangjin.frameecho.core.media.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

class MediaSourceResolver(context: Context) {
    private val context = context.applicationContext

    suspend fun resolve(uri: Uri): ResolvedMedia {
        var resolved: ResolvedMedia? = null
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) {
                val coroutineContext = currentCoroutineContext()
                val checkCancellation = { coroutineContext.ensureActive() }
                checkCancellation()
                val name = sourceFileName(uri)
                fun extract(source: SourceData, video: EmbeddedVideo): ResolvedMedia {
                    val file = File.createTempFile("motion_photo_", ".mp4", context.cacheDir)
                    var retained = false
                    try {
                        val metadata = source.stream(video.imageLength.coerceAtMost(16L * MAX_XMP_BYTES))
                            .buffered(64 * 1024).use { StillMetadata.read(it, name) }
                            .copy(dateTimePositionUs = video.presentationTimestampUs ?: 0)
                        file.outputStream().use { output ->
                            var position = video.offset
                            var remaining = video.length
                            while (remaining > 0) {
                                checkCancellation()
                                val count = minOf(64 * 1024L, remaining).toInt()
                                output.write(source.read(position, count))
                                position += count
                                remaining -= count
                            }
                        }
                        checkCancellation()
                        return ResolvedMedia(
                            Uri.fromFile(file), uri, name, metadata, video.presentationTimestampUs,
                            video.imageLength, video.imageMimeType, file
                        ).also { retained = true }
                    } finally {
                        if (!retained) Files.deleteIfExists(file.toPath())
                    }
                }
                val mime = context.contentResolver.getType(uri)
                val prefix = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(4096)
                    var count = 0
                    while (count < buffer.size) {
                        checkCancellation()
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    buffer.copyOf(count)
                } ?: throw IOException("Cannot open media source")
                val jpeg = prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte()
                val iso = isIsoImage(prefix)
                val picture = jpeg || mime?.startsWith("image/") == true || isStillSignature(prefix)
                val value = if (picture || iso) SourceAccess(context, checkCancellation).read(uri) { source ->
                    val video = MotionPhotoParser(source, checkCancellation).parse()
                    if (video == null) {
                        if (picture) noVideo()
                        ResolvedMedia(uri, uri, name)
                    } else extract(source, video)
                } else ResolvedMedia(uri, uri, name)
                resolved = value
                value
            }
            delivered = true
            return result
        } finally {
            if (!delivered) resolved?.release()
        }
    }

    private fun sourceFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "media"
    }

    private fun isStillSignature(bytes: ByteArray): Boolean =
        (bytes.size >= 4 && bytes.fourcc() == "\u0089PNG") ||
            (bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF") ||
            (bytes.size >= 12 && bytes.fourcc() == "RIFF" && bytes.fourcc(8) == "WEBP")

    private fun isIsoImage(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || bytes.fourcc(4) != "ftyp") return false
        val extended = bytes.uint32() == 1L
        val header = if (extended) 16 else 8
        val end = (if (extended) bytes.uint32(12) else bytes.uint32()).coerceAtMost(bytes.size.toLong()).toInt()
        return end >= header + 4 && IsoBoxes.imageMimeType(bytes.copyOfRange(header, end)) != null
    }
}
