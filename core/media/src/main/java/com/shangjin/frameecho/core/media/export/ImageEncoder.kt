package com.shangjin.frameecho.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import com.shangjin.frameecho.core.media.metadata.JpegExifSegment
import com.shangjin.frameecho.core.media.metadata.MetadataWriter
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.VideoMetadata
import java.io.Closeable
import java.io.File
import java.io.IOException

internal data class EncodedImage(val file: File, val metadataPreserved: Boolean) : Closeable {
    override fun close() { file.delete() }
}

internal class ImageEncoder(private val context: Context) {
    fun encode(bitmap: Bitmap, config: ExportConfig, metadata: VideoMetadata): EncodedImage {
        if (config.format == ExportFormat.HEIC && !HeicSupport.isSupported()) {
            throw IOException("HEIC encoding is unavailable on this device. Select JPEG to export this frame.")
        }
        val file = File.createTempFile("encoded_frame_", ".${config.format.extension}", context.cacheDir)
        try {
            when (config.format) {
                ExportFormat.JPEG -> encodeJpeg(file, bitmap, config.quality)
                ExportFormat.HEIC -> {
                    check(Build.VERSION.SDK_INT >= 28) { "HEIC requires Android 9 or later" }
                    val exifData = if (config.preserveMetadata) createExifData(metadata) else null
                    HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                        .setQuality(config.quality).setMaxImages(1).build().use { writer ->
                            writer.start()
                            exifData?.let { writer.addExifData(0, it, 0, it.size) }
                            writer.addBitmap(bitmap)
                            writer.stop(30_000)
                        }
                }
            }
            if (config.preserveMetadata && config.format == ExportFormat.JPEG) {
                val exif = ExifInterface(file)
                MetadataWriter.writeExifData(exif, metadata)
                exif.saveAttributes()
            }
            return EncodedImage(file, config.preserveMetadata)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun encodeJpeg(file: File, bitmap: Bitmap, quality: Int) {
        file.outputStream().use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encoding failed")
        }
    }

    private fun createExifData(metadata: VideoMetadata): ByteArray {
        val carrier = File.createTempFile("heic_exif_", ".jpg", context.cacheDir)
        try {
            // A minimal JPEG carrier lets ExifInterface serialize TIFF without recompressing the exported image.
            carrier.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            val exif = ExifInterface(carrier)
            MetadataWriter.writeExifData(exif, metadata)
            exif.saveAttributes()
            return JpegExifSegment.read(carrier)
        } finally {
            carrier.delete()
        }
    }
}
