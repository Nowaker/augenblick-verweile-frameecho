package com.shangjin.frameecho.core.media.source

internal data class EmbeddedVideo(
    val offset: Long,
    val length: Long,
    val imageLength: Long,
    val imageMimeType: String,
    val presentationTimestampUs: Long?
)

internal class MotionPhotoParser(private val source: SourceData, private val checkCancellation: () -> Unit) {
    private val boxes = IsoBoxes(source, checkCancellation)

    fun parse(): EmbeddedVideo? {
        val signature = source.read(0, minOf(12L, source.length).toInt())
        val result = when {
            signature.size >= 2 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() -> jpeg()
            signature.size >= 8 && signature.fourcc(4) == "ftyp" -> isoImage()
            else -> null
        }
        result?.let { boxes.validateVideo(it.offset, it.length) }
        return result
    }

    private fun jpeg(): EmbeddedVideo {
        var position = 2L
        var metadata: MotionPhotoXmp? = null
        var scanned = 0L
        var segments = 0
        while (position < source.length) {
            checkCancellation()
            if (++segments > 4096 || scanned > 16 * MAX_XMP_BYTES) malformed("JPEG header exceeds limits")
            val marker = source.read(position, 2)
            if (marker[0] != 0xff.toByte()) malformed("Invalid JPEG marker")
            val type = marker[1].toInt() and 0xff
            if (type == 0xff) { position++; scanned++; continue }
            if (type == 0xda || type == 0xd9) break
            if (type == 0 || type == 0xd8) malformed("Invalid JPEG header marker")
            if (type == 1 || type in 0xd0..0xd7) { position += 2; scanned += 2; continue }
            val sizeBytes = source.read(position + 2, 2)
            val size = ((sizeBytes[0].toInt() and 0xff) shl 8) or (sizeBytes[1].toInt() and 0xff)
            if (size < 2) malformed("Invalid JPEG segment length")
            source.checkRange(position + 2, size.toLong())
            if (type == 0xe1 && size >= XMP_HEADER.size + 2) {
                val header = source.read(position + 4, XMP_HEADER.size)
                if (header.contentEquals(XMP_HEADER)) {
                    if (metadata != null) malformed("Duplicate JPEG XMP packet")
                    metadata = MotionPhotoXmp.parse(source.read(position + 4 + header.size, size - 2 - header.size))
                }
            }
            position += 2 + size
            scanned += 2 + size
        }
        val xmp = metadata ?: noVideo()
        if (xmp.enabled == false) noVideo()
        val video = if (xmp.items.isNotEmpty()) directory(xmp, "image/jpeg") else {
            val length = xmp.legacyOffset ?: noVideo()
            if (length <= 0 || length >= source.length) malformed("Invalid legacy motion video offset")
            val offset = source.length - length
            EmbeddedVideo(offset, length, offset, "image/jpeg", xmp.presentationTimestampUs)
        }
        if (video.offset < position + 2 || video.imageLength < position + 2) malformed("Motion video overlaps JPEG metadata")
        if (!source.read(video.imageLength - 2, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte()))) {
            malformed("Motion Photo still image is truncated or its padding is invalid")
        }
        return video
    }

    private fun isoImage(): EmbeddedVideo? {
        val first = boxes.read(0, source.length)
        val mime = boxes.imageMimeType(first) ?: return null
        var position = first.end
        var videoBox: IsoBox? = null
        var xmp: MotionPhotoXmp? = null
        while (position < source.length) {
            val box = boxes.read(position, source.length)
            when (box.type) {
                "mpvd" -> {
                    if (videoBox != null || box.end != source.length) malformed("mpvd must be the final ISO box")
                    videoBox = box
                }
                "meta" -> HeifXmp(source, boxes).read(box)?.let {
                    if (xmp != null) malformed("Duplicate HEIF XMP packet")
                    xmp = MotionPhotoXmp.parse(it)
                }
            }
            position = box.end
        }
        val mpvd = videoBox ?: noVideo()
        val metadata = xmp
        if (metadata?.enabled == false) noVideo()
        if (metadata != null && metadata.items.isNotEmpty()) {
            val declared = directory(metadata, mime)
            if (declared.offset != mpvd.dataStart || declared.length != mpvd.dataLength ||
                declared.imageLength != mpvd.start) malformed("XMP directory does not match mpvd extent")
        }
        return EmbeddedVideo(mpvd.dataStart, mpvd.dataLength, mpvd.start, mime, metadata?.presentationTimestampUs)
    }

    private fun directory(xmp: MotionPhotoXmp, mime: String): EmbeddedVideo {
        val primary = xmp.items.first()
        if (primary.semantic != "Primary" || primary.mime != mime || primary.length != 0L) {
            malformed("Invalid primary Motion Photo directory item")
        }
        if (xmp.items.size < 2 || xmp.items.drop(1).any { it.semantic == "Primary" }) {
            malformed("Invalid Motion Photo directory")
        }
        val motion = xmp.items.last()
        if (motion.semantic != "MotionPhoto" || motion.mime !in setOf("video/mp4", "video/quicktime") ||
            motion.length <= 0 || xmp.items.dropLast(1).any { it.semantic == "MotionPhoto" }) {
            malformed("Missing final Motion Photo video item")
        }
        var secondaryLength = 0L
        for (item in xmp.items.drop(1)) {
            if (item.padding != 0L || item.length > source.length - secondaryLength) malformed("Invalid Motion Photo item length or padding")
            secondaryLength += item.length
        }
        if (primary.padding >= source.length - secondaryLength) malformed("Motion Photo padding overlaps the image")
        val primaryEnd = source.length - secondaryLength - primary.padding
        if (mime == "image/jpeg" && (primaryEnd < 4 ||
                !source.read(primaryEnd - 2, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte())))) {
            malformed("XMP primary image extent has no JPEG end marker")
        }
        val videoOffset = source.length - motion.length
        val imageEnd = if (mime == "image/jpeg" && xmp.items.size > 2) videoOffset else primaryEnd
        return EmbeddedVideo(videoOffset, motion.length, imageEnd, mime, xmp.presentationTimestampUs)
    }

    companion object {
        private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
    }
}
