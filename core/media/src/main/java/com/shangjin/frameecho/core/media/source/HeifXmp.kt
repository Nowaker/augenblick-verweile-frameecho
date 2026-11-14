package com.shangjin.frameecho.core.media.source

import java.io.ByteArrayOutputStream

internal class HeifXmp(private val source: SourceData, private val boxes: IsoBoxes) {
    fun read(meta: IsoBox): ByteArray? {
        if (meta.dataLength < 4) malformed("Truncated HEIF metadata")
        val children = boxes.children(meta.dataStart + 4, meta.end)
        val info = children.singleOrNull { it.type == "iinf" } ?: return null
        val locations = children.singleOrNull { it.type == "iloc" } ?: return null
        val id = xmpItemId(info) ?: return null
        val idat = children.singleOrNull { it.type == "idat" }
        val cursor = cursor(locations)
        val version = cursor.number(1).toInt()
        if (version !in 0..2) malformed("Unsupported HEIF item location version")
        cursor.number(3)
        val sizes = cursor.number(2).toInt()
        val offsetSize = sizes shr 12
        val lengthSize = (sizes shr 8) and 15
        val baseSize = (sizes shr 4) and 15
        val indexSize = if (version == 0) 0 else sizes and 15
        if (listOf(offsetSize, lengthSize, baseSize, indexSize).any { it > 8 }) {
            malformed("Invalid HEIF extent field size")
        }
        val count = cursor.number(if (version == 2) 4 else 2)
        if (count > 4096) malformed("Too many HEIF item locations")
        var result: ByteArray? = null
        repeat(count.toInt()) {
            val itemId = cursor.number(if (version == 2) 4 else 2)
            val method = if (version == 0) 0 else cursor.number(2).toInt() and 15
            val reference = cursor.number(2)
            val base = cursor.number(baseSize)
            val extentCount = cursor.number(2)
            if (extentCount > 4096) malformed("Too many HEIF extents")
            val output = if (itemId == id) ByteArrayOutputStream() else null
            if (output != null && (reference != 0L || method !in 0..1 || extentCount == 0L)) {
                malformed("Unsupported HEIF XMP extent reference")
            }
            repeat(extentCount.toInt()) {
                cursor.number(indexSize)
                val offset = cursor.number(offsetSize)
                val length = cursor.number(lengthSize)
                if (output != null) {
                    if (length == 0L || length > MAX_XMP_BYTES - output.size()) malformed("HEIF XMP extent is too large")
                    val dataStart = if (method == 1) idat?.dataStart ?: malformed("HEIF XMP is missing idat") else 0L
                    val location = add(add(dataStart, base), offset)
                    val dataEnd = if (method == 1) idat?.end ?: malformed("HEIF XMP is missing idat") else source.length
                    if (location > dataEnd || length > dataEnd - location) {
                        malformed("HEIF XMP extent is outside idat")
                    }
                    output.write(source.read(location, length.toInt()))
                }
            }
            if (output != null) {
                if (result != null) malformed("Duplicate HEIF XMP location")
                result = output.toByteArray()
            }
        }
        return result ?: malformed("HEIF XMP item has no location")
    }

    private fun xmpItemId(info: IsoBox): Long? {
        val cursor = cursor(info)
        val version = cursor.number(1)
        if (version !in 0L..1L) malformed("Unsupported HEIF item info version")
        cursor.number(3)
        val count = cursor.number(if (version == 0L) 2 else 4)
        val entries = boxes.children(info.dataStart + cursor.position, info.end)
        if (entries.size.toLong() != count) malformed("Invalid HEIF item info count")
        var id: Long? = null
        for (entry in entries.filter { it.type == "infe" }) {
            val item = cursor(entry)
            val itemVersion = item.number(1)
            item.number(3)
            if (itemVersion !in 2L..3L) continue
            val itemId = item.number(if (itemVersion == 2L) 2 else 4)
            item.number(2)
            val type = item.text(4)
            item.zeroTerminated()
            if (type == "mime" && item.zeroTerminated() == "application/rdf+xml") {
                if (id != null) malformed("Multiple HEIF XMP items")
                id = itemId
            }
        }
        return id
    }

    private fun cursor(box: IsoBox): ExtentCursor {
        if (box.dataLength > MAX_XMP_BYTES) malformed("HEIF metadata box is too large")
        return ExtentCursor(source.read(box.dataStart, box.dataLength.toInt()))
    }

    private fun add(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) malformed("HEIF extent offset overflows")
        return left + right
    }

    private class ExtentCursor(private val bytes: ByteArray) {
        var position = 0
            private set

        fun number(size: Int): Long {
            check(size)
            if (size == 8 && bytes[position] < 0) malformed("HEIF extent exceeds signed 64-bit range")
            return (0 until size).fold(0L) { value, _ ->
                (value shl 8) or (bytes[position++].toLong() and 0xff)
            }
        }

        fun text(size: Int): String {
            check(size)
            return String(bytes, position, size, Charsets.UTF_8).also { position += size }
        }

        fun zeroTerminated(): String {
            val start = position
            while (position < bytes.size && bytes[position] != 0.toByte()) position++
            if (position == bytes.size) malformed("Truncated HEIF item string")
            return String(bytes, start, position++ - start, Charsets.UTF_8)
        }

        private fun check(size: Int) {
            if (size < 0 || size > bytes.size - position) malformed("Truncated HEIF item extent")
        }
    }
}
