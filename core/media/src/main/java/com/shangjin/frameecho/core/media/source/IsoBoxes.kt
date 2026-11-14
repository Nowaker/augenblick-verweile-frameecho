package com.shangjin.frameecho.core.media.source

internal data class IsoBox(val type: String, val start: Long, val end: Long, val headerSize: Int) {
    val dataStart: Long get() = start + headerSize
    val dataLength: Long get() = end - dataStart
}

internal class IsoBoxes(private val source: SourceData, private val checkCancellation: () -> Unit) {
    private var visited = 0

    fun read(start: Long, end: Long): IsoBox {
        checkCancellation()
        if (++visited > 100_000) malformed("Too many ISO media boxes")
        source.checkRange(start, end - start)
        if (end - start < 8) malformed("Truncated ISO box header")
        val header = source.read(start, 8)
        val shortSize = header.uint32()
        val type = header.fourcc(4)
        val headerSize = if (shortSize == 1L) 16 else 8
        val size = when (shortSize) {
            0L -> {
                if (type == "mpvd") malformed("An mpvd box must have an explicit size")
                end - start
            }
            1L -> {
                if (end - start < 16) malformed("Truncated extended ISO box header")
                val bytes = source.read(start + 8, 8)
                if (bytes[0] < 0) malformed("ISO box size exceeds signed 64-bit range")
                (0 until 8).fold(0L) { value, index -> (value shl 8) or (bytes[index].toLong() and 0xff) }
            }
            else -> shortSize
        }
        if (size < headerSize || size > end - start) malformed("ISO box size is outside its container")
        return IsoBox(type, start, start + size, headerSize)
    }

    fun children(start: Long, end: Long): List<IsoBox> {
        val boxes = mutableListOf<IsoBox>()
        var position = start
        while (position < end) {
            if (boxes.size >= 4096) malformed("Too many child ISO boxes")
            val box = read(position, end)
            boxes += box
            position = box.end
        }
        return boxes
    }

    fun imageMimeType(ftyp: IsoBox): String? {
        if (ftyp.type != "ftyp" || ftyp.dataLength < 8 || ftyp.dataLength > 4096 ||
            ftyp.dataLength % 4 != 0L) malformed("Invalid file type box")
        val bytes = source.read(ftyp.dataStart, ftyp.dataLength.toInt())
        return imageMimeType(bytes)
    }

    companion object {
        fun imageMimeType(bytes: ByteArray): String? {
            val brands = (0 until bytes.size - 3 step 4).filter { it != 4 }.map { bytes.fourcc(it) }
            return when {
                brands.any { it == "avif" || it == "avis" } -> "image/avif"
                brands.any { it in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1") } -> "image/heic"
                else -> null
            }
        }
    }

    /** Validate the container without reading mdat or allocating according to its size. */
    fun validateVideo(offset: Long, length: Long) {
        source.checkRange(offset, length)
        val end = offset + length
        val ftyp = read(offset, end)
        if (ftyp.type != "ftyp" || imageMimeType(ftyp) != null) malformed("Embedded video has no MP4 file type signature")
        var position = ftyp.end
        var videoTrack = false
        var mediaData = false
        while (position < end) {
            val box = read(position, end)
            when (box.type) {
                "moov" -> videoTrack = videoTrack || children(box.dataStart, box.end).any { track ->
                    track.type == "trak" && children(track.dataStart, track.end).any { media ->
                        media.type == "mdia" && children(media.dataStart, media.end).any { handler ->
                            handler.type == "hdlr" && handler.dataLength >= 12 &&
                                source.read(handler.dataStart + 8, 4).fourcc() == "vide"
                        }
                    }
                }
                "mdat" -> mediaData = mediaData || box.dataLength > 0
            }
            position = box.end
        }
        if (!videoTrack || !mediaData) malformed("Embedded MP4 has no video track or media data")
    }
}
