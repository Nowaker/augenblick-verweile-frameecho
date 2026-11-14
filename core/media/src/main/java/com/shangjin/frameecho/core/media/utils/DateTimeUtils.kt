package com.shangjin.frameecho.core.media.utils

import com.shangjin.frameecho.core.model.CaptureTime

object DateTimeUtils {
    fun normalizeToIso(dateStr: String): String = CaptureTime.parse(dateStr)?.toIsoString() ?: dateStr

    fun convertToExif(isoDate: String): String? = CaptureTime.parse(isoDate)?.exifDateTime

    fun parseToMillis(dateStr: String): Long? = CaptureTime.parse(dateStr)?.epochMillis
}
