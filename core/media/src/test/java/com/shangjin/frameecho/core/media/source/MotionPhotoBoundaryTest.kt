package com.shangjin.frameecho.core.media.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class MotionPhotoBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = MotionPhotoFixtures

    @Test
    fun `XMP element properties resolve without relying on attribute spelling`() {
        val xmp = fixtures.packet("", """
            <c:MotionPhoto>1</c:MotionPhoto><c:MotionPhotoPresentationTimestampUs>-1</c:MotionPhotoPresentationTimestampUs>
            <b:Directory><r:Seq>
            <r:li><b:Item><i:Mime>image/jpeg</i:Mime><i:Semantic>Primary</i:Semantic></b:Item></r:li>
            <r:li><b:Item><i:Mime>video/mp4</i:Mime><i:Semantic>MotionPhoto</i:Semantic><i:Length>${fixtures.video.size}</i:Length></b:Item></r:li>
            </r:Seq></b:Directory>
        """.trimIndent())
        val jpeg = fixtures.jpeg(xmp)

        val result = parse(jpeg + fixtures.video)

        assertEquals(jpeg.size.toLong(), result?.imageLength)
        assertNull(result?.presentationTimestampUs)
    }

    @Test
    fun `ISO 64-bit and zero size declarations cannot escape their parent`() {
        val ftyp = fixtures.box("ftyp", "heic".toByteArray() + fixtures.integer(0))
        val cases = listOf(
            fixtures.integer(0) + "mpvd".toByteArray() + fixtures.video,
            fixtures.integer(1) + "mpvd".toByteArray(),
            fixtures.integer(1) + "mpvd".toByteArray() + fixtures.integer(Long.MAX_VALUE, 8),
            fixtures.integer(1) + "mpvd".toByteArray() + fixtures.integer(Long.MIN_VALUE, 8),
            fixtures.integer(4) + "free".toByteArray(),
            fixtures.integer(1) + "free".toByteArray() + fixtures.integer(12, 8)
        )
        for (suffix in cases) {
            val error = assertThrows(MediaSourceException::class.java) { parse(ftyp + suffix) }
            assertEquals(MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `HEIF XMP extents reject out of bounds and huge length declarations`() {
        val xmp = fixtures.directory("image/heic", padding = 8).toByteArray()
        val ftyp = fixtures.box("ftyp", "heic".toByteArray() + fixtures.integer(0))
        val meta = fixtures.metadata(xmp)
        val iloc = meta.indices.first { index -> index + 4 <= meta.size && meta.copyOfRange(index, index + 4).contentEquals("iloc".toByteArray()) }
        val firstExtentLength = iloc + 4 + 6 + 2 + 2 + 2 + 2 + 2 + 4
        for (length in listOf(1_000_000L, Long.MAX_VALUE)) {
            val changed = meta.copyOf()
            fixtures.integer(length).copyInto(changed, firstExtentLength)

            val error = assertThrows(MediaSourceException::class.java) { parse(ftyp + changed + fixtures.box("mpvd", fixtures.video)) }

            assertEquals(MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `XMP directory must match the actual mpvd header and payload`() {
        val cases = listOf(
            fixtures.heif(xmp = fixtures.directory("image/heic", padding = 8), extended = true),
            fixtures.heif(xmp = fixtures.directory("image/heic", padding = 8, length = fixtures.video.size - 1L)),
            fixtures.heif(xmp = fixtures.directory("image/avif", padding = 8))
        )
        for (bytes in cases) {
            val error = assertThrows(MediaSourceException::class.java) { parse(bytes) }
            assertEquals(MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `malformed XML entities and conflicting declarations are rejected`() {
        val cases = listOf(
            "<!DOCTYPE x [<!ENTITY value SYSTEM 'file:///nonexistent'>]>" + fixtures.legacy(),
            fixtures.legacy().dropLast(10),
            fixtures.legacy(extra = "c:MotionPhotoPresentationTimestampUs=\"-2\""),
            fixtures.packet("c:MicroVideo=\"1\" c:MicroVideoOffset=\"${fixtures.video.size}\"", "<c:MicroVideoOffset>1</c:MicroVideoOffset>"),
            fixtures.directory().replace("i:Length=", "wrong:Length=").replace("<x:xmpmeta", "<x:xmpmeta xmlns:wrong=\"urn:wrong\"")
        )
        for (xmp in cases) {
            val error = assertThrows(MediaSourceException::class.java) { parse(fixtures.jpeg(xmp) + fixtures.video) }
            assertEquals(MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `MP4 signature alone cannot disguise audio or empty media as motion video`() {
        val audio = fixtures.video.copyOf()
        val handler = audio.indices.first { it + 4 <= audio.size && audio.copyOfRange(it, it + 4).contentEquals("vide".toByteArray()) }
        "soun".toByteArray().copyInto(audio, handler)
        val empty = fixtures.video.copyOfRange(0, fixtures.video.size - 12) + fixtures.box("mdat", byteArrayOf())
        for (video in listOf(audio, empty)) {
            val error = assertThrows(MediaSourceException::class.java) {
                parse(fixtures.jpeg(fixtures.legacy(video.size.toString())) + video)
            }
            assertEquals(MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, error.reason)
        }
    }

    @Test
    fun `provider asset offsets and lengths exclude neighboring bytes`() {
        val jpeg = fixtures.jpeg(fixtures.legacy())
        val bytes = jpeg + fixtures.video
        val file = temporary.newFile().apply { writeBytes(ByteArray(57) + bytes + ByteArray(123)) }
        RandomAccessFile(file, "r").use {
            val result = MotionPhotoParser(SourceData(it.channel, bytes.size.toLong(), 57)) {}.parse()

            assertEquals(jpeg.size.toLong(), result?.offset)
            assertEquals(fixtures.video.size.toLong(), result?.length)
        }
    }

    private fun parse(bytes: ByteArray): EmbeddedVideo? {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return RandomAccessFile(file, "r").use { MotionPhotoParser(SourceData(it.channel, it.length())) {}.parse() }
    }
}
