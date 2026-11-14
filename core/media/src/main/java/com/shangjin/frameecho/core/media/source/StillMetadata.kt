package com.shangjin.frameecho.core.media.source

import androidx.exifinterface.media.ExifInterface
import com.shangjin.frameecho.core.media.metadata.ExifMetadata
import com.shangjin.frameecho.core.media.utils.ExifUtils
import com.shangjin.frameecho.core.model.VideoMetadata
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal object StillMetadata {
    fun read(input: InputStream, fileName: String): VideoMetadata {
        val exif = try {
            ExifInterface(input)
        } catch (_: IOException) {
            return VideoMetadata(sourceFileName = fileName)
        }
        val location = exif.latLong
        return VideoMetadata(
            dateTime = captureDate(exif),
            latitude = location?.get(0),
            longitude = location?.get(1),
            altitude = exif.getAltitude(Double.NaN).takeIf { it.isFinite() },
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.toIntOrNull(),
            exposureTime = ExifUtils.normalizeExposureTime(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN).takeIf { it.isFinite() },
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN).takeIf { it.isFinite() },
            sourceFileName = fileName,
            exifAttributes = ExifMetadata.read(exif)
        )
    }

    private fun captureDate(exif: ExifInterface): String? {
        val dateTags = listOf(
            Triple(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            Triple(ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            Triple(ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME)
        )
        for ((dateTag, fractionTag, offsetTag) in dateTags) {
            val date = exif.getAttribute(dateTag) ?: continue
            val parsed = try {
                LocalDateTime.parse(date, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.US))
            } catch (_: DateTimeParseException) {
                continue
            }
            val fraction = exif.getAttribute(fractionTag)?.trim()?.takeIf { value ->
                value.isNotEmpty() && value.all { it in '0'..'9' }
            }?.take(9)?.padEnd(9, '0')?.toIntOrNull() ?: 0
            val local = parsed.withNano(fraction)
            val offset = exif.getAttribute(offsetTag)?.let {
                try {
                    ZoneOffset.of(it)
                } catch (_: java.time.DateTimeException) {
                    null
                }
            }
            return if (offset == null) local.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            else local.atOffset(offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        return null
    }
}
