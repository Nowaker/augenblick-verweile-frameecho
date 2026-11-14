package com.shangjin.frameecho.core.model

import kotlin.math.roundToLong

/** The same pure filename generator is used by previews and exports. */
object ExportFileName {
    private val TOKEN = Regex("\\{[^{}]+}")
    private val IMAGE_EXTENSION = Regex("\\.(jpe?g|heic|heif)$", RegexOption.IGNORE_CASE)

    /** Position is PTS in microseconds (12 digits); frame is a zero-based index (8 digits). */
    fun tokens(frame: CapturedFrame, exportTimestampMs: Long): Map<String, String> = linkedMapOf(
        "{filename}" to sanitize(frame.metadata.sourceFileName?.substringAfterLast('/')
            ?.substringAfterLast('\\')?.substringBeforeLast('.') ?: "FrameEcho"),
        "{timestamp}" to exportTimestampMs.toString(),
        "{position}" to frame.timestampUs.coerceAtLeast(0).toString().padStart(12, '0'),
        "{frame}" to (frame.timestampUs.coerceAtLeast(0) / 1_000_000.0 *
            (frame.metadata.frameRate.takeIf { it.isFinite() && it > 0f } ?: 0f))
            .roundToLong().toString().padStart(8, '0')
    )

    fun generate(
        frame: CapturedFrame,
        config: ExportConfig,
        exportTimestampMs: Long,
        isMotion: Boolean = false
    ): String {
        val timestampMs = config.fileNameTimestampMs ?: exportTimestampMs
        val values = tokens(frame, timestampMs)
        val template = config.customFileName?.takeIf { it.isNotBlank() }
        val suffix = "${timestampMs}_${frame.timestampUs / 1000}"
        val base = when {
            template == null -> "${if (isMotion) "MVIMG" else "IMG"}_$suffix"
            TOKEN.containsMatchIn(template) -> TOKEN.replace(template) { match ->
                requireNotNull(values[match.value]) { "Unknown filename token: ${match.value}" }
            }
            else -> "${template.replace(IMAGE_EXTENSION, "")}_$suffix"
        }
        val extension = if (isMotion) ExportFormat.JPEG.extension else config.format.extension
        return "${sanitize(base.replace(IMAGE_EXTENSION, ""))}.$extension"
    }

    fun sanitize(value: String): String {
        val safe = value.replace(Regex("[\\p{Cc}\\p{Cn}\\p{Cf}]"), "_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\.\\.+"), "_").replace(Regex("_+"), "_")
            .trim { it == '_' || it == '.' || it.isWhitespace() }.ifBlank { "FrameEcho" }
        // Leave room for the extension and provider-added collision suffixes in a 255-byte name.
        var end = safe.length
        while (safe.substring(0, end).toByteArray(Charsets.UTF_8).size > 180) {
            end -= Character.charCount(safe.codePointBefore(end))
        }
        return safe.substring(0, end).trimEnd('.', ' ')
    }

}
