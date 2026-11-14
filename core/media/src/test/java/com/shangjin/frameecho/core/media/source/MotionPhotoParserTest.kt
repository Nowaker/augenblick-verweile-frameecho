package com.shangjin.frameecho.core.media.source

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

class MotionPhotoParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = MotionPhotoFixtures

    @Test
    fun `legacy JPEG resolves the exact video and cover timestamp`() {
        val jpeg = fixtures.jpeg(fixtures.legacy())
        val bytes = jpeg + fixtures.video

        val result = parse(bytes)

        assertEquals(EmbeddedVideo(jpeg.size.toLong(), fixtures.video.size.toLong(), jpeg.size.toLong(), "image/jpeg", 123456), result)
        assertArrayEquals(fixtures.video, bytes.copyOfRange(result!!.offset.toInt(), (result.offset + result.length).toInt()))
    }

    @Test
    fun `directory overrides obsolete legacy offset with arbitrary namespace prefixes`() {
        val xmp = fixtures.directory(camera = "c:MotionPhoto=\"1\" c:MicroVideoOffset=\"bogus\"")
        val image = fixtures.jpeg(xmp)

        val result = parse(image + fixtures.video)

        assertEquals(image.size.toLong(), result?.offset)
        assertNull(result?.presentationTimestampUs)
    }

    @Test
    fun `directory padding is excluded from the original still copy range`() {
        val image = fixtures.jpeg(fixtures.directory(padding = 13))

        val result = parse(image + ByteArray(13) + fixtures.video)

        assertEquals(image.size.toLong(), result?.imageLength)
        assertEquals(image.size + 13L, result?.offset)
    }

    @Test
    fun `gainmap bytes remain inside the unchanged still range`() {
        val gainmap = fixtures.jpeg()
        val item = "<r:li><b:Item i:Mime=\"image/jpeg\" i:Semantic=\"GainMap\" i:Length=\"${gainmap.size}\"/></r:li>"
        val image = fixtures.jpeg(fixtures.directory(extraItem = item)) + gainmap

        val result = parse(image + fixtures.video)

        assertEquals(image.size.toLong(), result?.imageLength)
        assertEquals(image.size.toLong(), result?.offset)
    }

    @Test
    fun `HEIC and AVIF mpvd payloads support normal and extended box headers`() {
        for ((brand, mime) in listOf("heic" to "image/heic", "avif" to "image/avif")) {
            for (extended in listOf(false, true)) {
                val bytes = fixtures.heif(brand, fixtures.directory(mime, padding = if (extended) 16 else 8), extended)

                val result = parse(bytes)

                assertEquals(mime, result?.imageMimeType)
                assertEquals(bytes.size - fixtures.video.size.toLong(), result?.offset)
                assertEquals(result!!.offset - if (extended) 16 else 8, result.imageLength)
                assertEquals(987654L, result.presentationTimestampUs)
            }
        }
    }

    @Test
    fun `unambiguous mpvd is readable without optional XMP`() {
        val bytes = fixtures.heif()

        val result = parse(bytes)

        assertEquals(fixtures.video.size.toLong(), result?.length)
        assertNull(result?.presentationTimestampUs)
    }

    @Test
    fun `HEIF XMP file extents retain presentation metadata`() {
        val xmp = fixtures.directory("image/heic", padding = 8).toByteArray()
        val ftyp = fixtures.box("ftyp", "heic".toByteArray() + fixtures.integer(0) + "mif1".toByteArray())
        val placeholder = fixtures.metadata(xmp, 0)
        val idatStart = ftyp.size + placeholder.size - xmp.size - 4L
        val meta = fixtures.metadata(xmp, idatStart)

        val result = parse(ftyp + meta + fixtures.box("mpvd", fixtures.video))

        assertEquals(987654L, result?.presentationTimestampUs)
    }

    @Test
    fun `still and disabled motion inputs clearly report no embedded video`() {
        val cases = listOf(
            fixtures.jpeg(),
            fixtures.jpeg(fixtures.packet("c:MotionPhoto=\"0\" c:MicroVideoOffset=\"${fixtures.video.size}\"")) + fixtures.video,
            fixtures.box("ftyp", "avif".toByteArray() + fixtures.integer(0)),
            fixtures.jpeg(fixtures.legacy().replace("http://ns.google.com/photos/1.0/camera/", "https://attacker.invalid/camera")) + fixtures.video
        )
        for (bytes in cases) {
            val error = assertThrows(MediaSourceException::class.java) { parse(bytes) }
            assertEquals(MediaSourceException.Reason.NO_EMBEDDED_VIDEO, error.reason)
        }
    }

    @Test
    fun `standalone MP4 is passed through without inspecting its payload`() {
        assertNull(parse(fixtures.video.copyOfRange(0, 24)))
    }

    @Test
    fun `malformed offsets signatures and truncated boxes are rejected`() {
        val cases = listOf(
            fixtures.jpeg(fixtures.legacy("-1")) + fixtures.video,
            fixtures.jpeg(fixtures.legacy("0")) + fixtures.video,
            fixtures.jpeg(fixtures.legacy(Long.MAX_VALUE.toString())) + fixtures.video,
            fixtures.jpeg(fixtures.legacy("18446744073709551615")) + fixtures.video,
            fixtures.jpeg(fixtures.directory(length = Long.MAX_VALUE)) + fixtures.video,
            fixtures.jpeg(fixtures.directory(padding = Long.MAX_VALUE)) + fixtures.video,
            fixtures.jpeg(fixtures.legacy()) + ByteArray(fixtures.video.size),
            (fixtures.jpeg(fixtures.legacy()) + fixtures.video).dropLast(1).toByteArray(),
            fixtures.heif().dropLast(1).toByteArray(),
            fixtures.heif() + byteArrayOf(0),
            fixtures.heif().copyOfRange(0, 31) + fixtures.integer(0) + "mpvd".toByteArray() + fixtures.video,
            fixtures.jpeg(fixtures.legacy()).dropLast(1).toByteArray() + fixtures.video,
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, 1)
        )
        for ((index, bytes) in cases.withIndex()) {
            val error = assertThrows("case $index", MediaSourceException::class.java) { parse(bytes) }
            assertEquals("case $index", MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `sparse video over four GiB is parsed with 64-bit offsets and bounded reads`() {
        val payloadLength = 5L * 1024 * 1024 * 1024
        val prefix = fixtures.video.copyOfRange(0, fixtures.video.size - 12)
        val videoLength = prefix.size + 16 + payloadLength
        val image = fixtures.jpeg(fixtures.legacy(videoLength.toString()))
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { source ->
            source.write(image + prefix + fixtures.integer(1) + "mdat".toByteArray() + fixtures.integer(payloadLength + 16, 8))
            source.setLength(image.size + videoLength)

            val result = MotionPhotoParser(SourceData(source.channel, source.length())) {}.parse()

            assertEquals(videoLength, result?.length)
            assertEquals(image.size.toLong(), result?.offset)
        }
    }

    @Test
    fun `parsing observes cancellation`() {
        val file = temporary.newFile().apply { writeBytes(fixtures.heif()) }
        RandomAccessFile(file, "r").use {
            assertThrows(CancellationException::class.java) {
                MotionPhotoParser(SourceData(it.channel, it.length())) { throw CancellationException() }.parse()
            }
        }
    }

    private fun parse(bytes: ByteArray): EmbeddedVideo? {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return RandomAccessFile(file, "r").use {
            MotionPhotoParser(SourceData(it.channel, it.length())) {}.parse()
        }
    }
}
