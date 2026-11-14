package com.shangjin.frameecho.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/** Source wall time and its known offset, retaining the source's fractional precision. */
data class CaptureTime(
    val localDateTime: LocalDateTime,
    val offset: ZoneOffset?,
    val fractionalDigits: Int = 0
) {
    val epochMillis: Long
        get() = offset?.let { localDateTime.toInstant(it) }
            ?.toEpochMilli() ?: localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val exifDateTime: String
        get() = EXIF.format(localDateTime)

    val exifSubseconds: String?
        get() = if (fractionalDigits == 0) null else localDateTime.nano.toString()
            .padStart(9, '0').take(fractionalDigits)

    val exifOffset: String?
        get() = offset?.let { if (it == ZoneOffset.UTC) "+00:00" else it.id }

    fun plusMicroseconds(microseconds: Long): CaptureTime = copy(
        localDateTime = localDateTime.plusSeconds(microseconds / 1_000_000)
            .plusNanos(microseconds % 1_000_000 * 1_000),
        fractionalDigits = if (microseconds % 1_000_000 == 0L) fractionalDigits
            else maxOf(fractionalDigits, 6)
    )

    fun toIsoString(): String = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .apply {
            if (fractionalDigits > 0) appendFraction(ChronoField.NANO_OF_SECOND, fractionalDigits, fractionalDigits, true)
        }
        .toFormatter(Locale.US).format(localDateTime) + (offset?.id ?: "")

    companion object {
        private val EXIF = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.US)
        private val BASE_PATTERNS = listOf(
            "uuuu-MM-dd'T'HH:mm:ss", "uuuuMMdd'T'HHmmss", "uuuu-MM-dd HH:mm:ss",
            "uuuu:MM:dd HH:mm:ss", "uuuuMMddHHmmss"
        )
        private val LOCAL_FORMATS = BASE_PATTERNS.map { pattern ->
            DateTimeFormatterBuilder().appendPattern(pattern)
                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter(Locale.US)
        }
        private val OFFSET_FORMATS = LOCAL_FORMATS.flatMap { base ->
            listOf("+HH:MM", "+HHMM", "+HH").map { pattern ->
                DateTimeFormatterBuilder().append(base).appendOffset(pattern, "Z").toFormatter(Locale.US)
            }
        }

        fun parse(value: String?): CaptureTime? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val digits = text.substringAfter('.', "").takeWhile { it.isDigit() }.length.coerceAtMost(9)
            for (formatter in OFFSET_FORMATS) {
                try {
                    val parsed = OffsetDateTime.parse(text, formatter)
                    return CaptureTime(parsed.toLocalDateTime(), parsed.offset, digits)
                } catch (_: DateTimeParseException) { }
            }
            for (formatter in LOCAL_FORMATS) {
                try {
                    return CaptureTime(LocalDateTime.parse(text, formatter), null, digits)
                } catch (_: DateTimeParseException) { }
            }
            return try {
                CaptureTime(LocalDate.parse(text, DateTimeFormatter.ofPattern("uuuu MM dd", Locale.US)).atStartOfDay(), null)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        fun atFrame(metadata: VideoMetadata, timestampUs: Long): CaptureTime? = parse(metadata.dateTime)
            ?.plusMicroseconds(Math.subtractExact(timestampUs, metadata.dateTimePositionUs))
    }
}
