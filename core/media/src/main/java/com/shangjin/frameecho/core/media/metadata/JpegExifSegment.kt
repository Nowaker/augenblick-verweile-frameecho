package com.shangjin.frameecho.core.media.metadata

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal object JpegExifSegment {
    fun read(file: File): ByteArray = RandomAccessFile(file, "r").use { input ->
        if (input.readUnsignedShort() != 0xffd8) throw IOException("EXIF carrier is not JPEG")
        while (input.filePointer < input.length()) {
            if (input.readUnsignedByte() != 0xff) throw IOException("Invalid JPEG marker")
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) break
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            val length = input.readUnsignedShort() - 2
            if (length < 0 || length > input.length() - input.filePointer) throw IOException("Invalid JPEG segment length")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            if (marker == 0xe1 && bytes.size >= 6 && bytes.copyOfRange(0, 6)
                    .contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) return@use bytes
        }
        throw IOException("JPEG has no EXIF block")
    }
}
