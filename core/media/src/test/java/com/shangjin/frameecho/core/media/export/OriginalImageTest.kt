package com.shangjin.frameecho.core.media.export

import com.shangjin.frameecho.core.model.ExportFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

class OriginalImageTest {
    private val jpeg = byteArrayOf(255.toByte(), 216.toByte()) + ByteArray(130_000) { (it % 251).toByte() } +
        byteArrayOf(255.toByte(), 217.toByte())
    private val video = byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112, 109, 112, 52, 50)

    @Test
    fun `original primary range is copied byte for byte without appended video`() = runTest {
        val output = ByteArrayOutputStream()
        OriginalImage.fromMimeType(jpeg.size.toLong(), "image/jpeg").copy(ByteArrayInputStream(jpeg + video), output)
        assertArrayEquals(jpeg, output.toByteArray())
    }

    @Test
    fun `HEIC primary bytes are preserved independently of encoder capability`() = runTest {
        val heic = ByteBuffer.allocate(24).putInt(24).put("ftypheic".toByteArray()).putInt(0)
            .put("mif1heic".toByteArray()).array()
        val output = ByteArrayOutputStream()
        OriginalImage.fromMimeType(heic.size.toLong(), "image/heic").copy(ByteArrayInputStream(heic + video), output)
        assertArrayEquals(heic, output.toByteArray())
    }

    @Test
    fun `truncated or mismatched primary ranges fail instead of claiming passthrough`() {
        assertThrows(EOFException::class.java) {
            runTest { OriginalImage(jpeg.size.toLong() + 100, ExportFormat.JPEG).copy(ByteArrayInputStream(jpeg), ByteArrayOutputStream()) }
        }
        assertThrows(IOException::class.java) {
            runTest { OriginalImage(jpeg.size.toLong() - 1, ExportFormat.JPEG).copy(ByteArrayInputStream(jpeg + video), ByteArrayOutputStream()) }
        }
        assertThrows(IOException::class.java) {
            runTest { OriginalImage(video.size.toLong(), ExportFormat.JPEG).copy(ByteArrayInputStream(video + video), ByteArrayOutputStream()) }
        }
        assertThrows(IOException::class.java) {
            runTest { OriginalImage(jpeg.size.toLong(), ExportFormat.JPEG).copy(ByteArrayInputStream(jpeg), ByteArrayOutputStream()) }
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalImage.fromMimeType(100, "video/mp4") }
    }
}
