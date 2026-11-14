package com.shangjin.frameecho.core.media.export

import com.shangjin.frameecho.core.model.ExportFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

internal data class OriginalImage(val length: Long, val format: ExportFormat) {
    init { require(length >= 4) { "Original image range is too short" } }

    suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        val header = ByteArray(64)
        var headerSize = 0
        var remaining = length
        var previous = -1
        var last = -1
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Original image range exceeds source length")
            if (count == 0) throw IOException("Source stream made no progress")
            val toKeep = minOf(count, header.size - headerSize)
            buffer.copyInto(header, headerSize, 0, toKeep)
            headerSize += toKeep
            previous = if (count > 1) buffer[count - 2].toInt() and 255 else last
            last = buffer[count - 1].toInt() and 255
            output.write(buffer, 0, count)
            remaining -= count
        }
        when (format) {
            ExportFormat.JPEG -> if (header[0].toInt() and 255 != 255 || header[1].toInt() and 255 != 216 ||
                previous != 255 || last != 217) throw IOException("Original range is not a complete JPEG image")
            ExportFormat.HEIC -> {
                if (headerSize < 16 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") {
                    throw IOException("Original range is not a HEIC image")
                }
                val boxSize = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
                if (boxSize < 16 || boxSize > length || boxSize % 4 != 0L) throw IOException("Invalid HEIC file type box")
                val brands = (8 until minOf(headerSize.toLong(), boxSize).toInt() step 4)
                    .filter { it != 12 }.map { String(header, it, 4, Charsets.US_ASCII) }
                if (brands.none { it in setOf("heic", "heix", "hevc", "hevx") }) {
                    throw IOException("Original range has no HEIC image brand")
                }
            }
        }
        if (input.read() == -1) throw IOException("Original image must be the primary range of a Motion Photo")
    }

    companion object {
        fun fromMimeType(length: Long, mimeType: String): OriginalImage = OriginalImage(length, when (mimeType.lowercase()) {
            "image/jpeg" -> ExportFormat.JPEG
            "image/heic", "image/heif" -> ExportFormat.HEIC
            else -> throw IllegalArgumentException("Unsupported original image MIME type: $mimeType")
        })
    }
}
