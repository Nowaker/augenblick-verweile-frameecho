package com.shangjin.frameecho.app.ui.export

import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import java.util.Locale

internal const val MAX_FILE_NAME_TEMPLATE_LENGTH = 80
internal const val SOURCE_POSITION_TEMPLATE = "{filename}_{timestamp}_{position}"
internal const val SOURCE_FRAME_TEMPLATE = "{filename}_{timestamp}_{frame}"
internal val fileNameTokens = listOf("{filename}", "{timestamp}", "{position}", "{frame}")

internal enum class FileNameTemplateIssue { TOO_LONG, INCOMPLETE_TOKEN, UNKNOWN_TOKEN }

/** Incomplete edits stay in the field instead of reaching the exporter's strict token parser. */
internal fun fileNameTemplateIssue(value: String): FileNameTemplateIssue? {
    if (value.length > MAX_FILE_NAME_TEMPLATE_LENGTH) return FileNameTemplateIssue.TOO_LONG
    var index = 0
    while (index < value.length) {
        when (value[index]) {
            '{' -> {
                val end = value.indexOf('}', index + 1)
                if (end < 0 || '{' in value.substring(index + 1, end)) {
                    return FileNameTemplateIssue.INCOMPLETE_TOKEN
                }
                if (value.substring(index, end + 1) !in fileNameTokens) {
                    return FileNameTemplateIssue.UNKNOWN_TOKEN
                }
                index = end + 1
            }
            '}' -> return FileNameTemplateIssue.INCOMPLETE_TOKEN
            else -> index++
        }
    }
    return null
}

internal fun originalStillFormat(mimeType: String?): ExportFormat? = when (mimeType?.lowercase(Locale.ROOT)) {
    "image/jpeg" -> ExportFormat.JPEG
    "image/heic", "image/heif" -> ExportFormat.HEIC
    else -> null
}

internal fun ExportConfig.forFileNamePreview(originalStillMimeType: String?): ExportConfig =
    if (originalImage) copy(format = originalStillFormat(originalStillMimeType) ?: format, motionPhoto = false)
    else this
