package com.shangjin.frameecho.core.media.source

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MotionPhotoFixtures {
    val video: ByteArray = box("ftyp", "isom".toByteArray() + integer(0) + "isommp42".toByteArray()) +
        box("moov", box("trak", box("mdia", box("hdlr", ByteArray(8) + "vide".toByteArray())))) +
        box("mdat", byteArrayOf(1, 2, 3, 4))

    fun box(type: String, data: ByteArray, extended: Boolean = false): ByteArray =
        if (extended) integer(1) + type.toByteArray() + integer(data.size + 16L, 8) + data
        else integer(data.size + 8L) + type.toByteArray() + data

    fun integer(value: Long, size: Int = 4): ByteArray = ByteArray(size) { index ->
        (value ushr ((size - 1 - index) * 8)).toByte()
    }

    fun jpeg(xmp: String? = null): ByteArray {
        val app = xmp?.let {
            val data = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + it.toByteArray()
            byteArrayOf(0xff.toByte(), 0xe1.toByte()) + integer(data.size + 2L, 2) + data
        } ?: byteArrayOf()
        return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + app +
            byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2, 1, 0xff.toByte(), 0xd9.toByte())
    }

    fun legacy(length: String = video.size.toString(), extra: String = ""): String = packet(
        "c:MicroVideo=\"1\" c:MicroVideoOffset=\"$length\" c:MicroVideoPresentationTimestampUs=\"123456\" $extra"
    )

    fun directory(
        mime: String = "image/jpeg", length: Long = video.size.toLong(), padding: Long = 0,
        extraItem: String = "", camera: String = "c:MotionPhoto=\"1\" c:MotionPhotoPresentationTimestampUs=\"987654\""
    ): String = packet(camera, """
        <b:Directory><r:Seq>
          <r:li r:parseType="Resource"><b:Item i:Mime="$mime" i:Semantic="Primary" i:Padding="$padding"/></r:li>
          $extraItem
          <r:li r:parseType="Resource"><b:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="$length"/></r:li>
        </r:Seq></b:Directory>
    """.trimIndent())

    fun packet(camera: String, content: String = ""): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><r:RDF xmlns:r="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <r:Description xmlns:c="http://ns.google.com/photos/1.0/camera/"
          xmlns:b="http://ns.google.com/photos/1.0/container/" xmlns:i="http://ns.google.com/photos/1.0/container/item/" $camera>
        $content</r:Description></r:RDF></x:xmpmeta>
    """.trimIndent()

    fun heif(brand: String = "heic", xmp: String? = null, extended: Boolean = false): ByteArray {
        val ftyp = box("ftyp", brand.toByteArray() + integer(0) + "mif1".toByteArray(), extended)
        val meta = xmp?.let { metadata(it.toByteArray()) } ?: byteArrayOf()
        return ftyp + meta + box("mdat", byteArrayOf(9, 8, 7)) + box("mpvd", video, extended)
    }

    fun metadata(xmp: ByteArray, absoluteOffset: Long? = null): ByteArray {
        val split = xmp.size / 2
        val payload = byteArrayOf(0, 0) + xmp.copyOfRange(0, split) + byteArrayOf(0, 0) + xmp.copyOfRange(split, xmp.size)
        val info = box("iinf", integer(0) + integer(1, 2) + box("infe",
            byteArrayOf(2, 0, 0, 0) + integer(7, 2) + integer(0, 2) + "mimeXMP\u0000application/rdf+xml\u0000".toByteArray()))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(1, 0, 0, 0, 0x44, 0))
            out.writeShort(1)
            out.writeShort(7)
            out.writeShort(if (absoluteOffset == null) 1 else 0)
            out.writeShort(0)
            out.writeShort(2)
            out.writeInt((absoluteOffset ?: 0).toInt() + 2)
            out.writeInt(split)
            out.writeInt((absoluteOffset ?: 0).toInt() + split + 4)
            out.writeInt(xmp.size - split)
        }
        return box("meta", integer(0) + info + box("iloc", bytes.toByteArray()) + box("idat", payload))
    }

    fun withExif(jpeg: ByteArray): ByteArray {
        val tiff = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put("II".toByteArray()).putShort(42).putInt(0)
        fun ascii(value: String) = value.toByteArray() + byteArrayOf(0)
        fun number(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun rational(vararg values: Int) = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it).putInt(1) }
        }.array()
        fun ifd(entries: List<Triple<Int, Int, ByteArray>>): Int {
            val start = tiff.position()
            tiff.putShort(entries.size.toShort())
            val valuesStart = start + 2 + entries.size * 12 + 4
            var nextValue = valuesStart
            for ((tag, format, value) in entries.sortedBy { it.first }) {
                val unitSize = when (format) { 3 -> 2; 4 -> 4; 5 -> 8; else -> 1 }
                tiff.putShort(tag.toShort()).putShort(format.toShort()).putInt(value.size / unitSize)
                if (value.size <= 4) tiff.put(value + ByteArray(4 - value.size))
                else {
                    tiff.putInt(nextValue)
                    val entryPosition = tiff.position()
                    tiff.position(nextValue)
                    tiff.put(value)
                    nextValue += value.size
                    tiff.position(entryPosition)
                }
            }
            tiff.putInt(0).position(nextValue)
            return start
        }
        val exif = ifd(listOf(
            Triple(0x9003, 2, ascii("2024:10:31 23:45:01")),
            Triple(0x9291, 2, ascii("123456")),
            Triple(0x9011, 2, ascii("+05:30")),
            Triple(0x8827, 3, byteArrayOf(200.toByte(), 0)),
            Triple(0x829d, 5, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(18).putInt(10).array())
        ))
        val gps = ifd(listOf(
            Triple(1, 2, ascii("N")), Triple(2, 5, rational(51, 30, 0)),
            Triple(3, 2, ascii("W")), Triple(4, 5, rational(0, 7, 30))
        ))
        val primary = ifd(listOf(
            Triple(0x10f, 2, ascii("Google")), Triple(0x110, 2, ascii("Pixel 9")),
            Triple(0x8769, 4, number(exif)), Triple(0x8825, 4, number(gps))
        ))
        tiff.putInt(4, primary)
        val data = "Exif\u0000\u0000".toByteArray() + tiff.array().copyOf(tiff.position())
        val app = byteArrayOf(0xff.toByte(), 0xe1.toByte()) + integer(data.size + 2L, 2) + data
        return jpeg.copyOfRange(0, 2) + app + jpeg.copyOfRange(2, jpeg.size)
    }
}
