package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.VideoMetadata
import kotlinx.coroutines.test.runTest
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(Parameterized::class)
class FrameExporterSecurityTest(private val motionPhoto: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "motionPhoto={0}")
        fun exporters() = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val treeUri = mockk<Uri>()
    private val createdUri = mockk<Uri>()
    private val tree = mockk<DocumentFile>()
    private val document = mockk<DocumentFile>()
    private val jpegBytes = byteArrayOf(1, 2, 3)
    private val videoBytes = byteArrayOf(4, 5, 6)
    private lateinit var videoFile: File

    @Before
    fun setup() {
        mockkStatic(DocumentFile::class, DocumentsContract::class)
        every { context.contentResolver } returns resolver
        every { DocumentFile.fromTreeUri(context, treeUri) } returns tree
        every { tree.createFile(any(), any()) } returns document
        every { tree.findFile(any()) } returns null
        every { resolver.openFileDescriptor(createdUri, "r") } returns null
        every { document.uri } returns createdUri
        every { treeUri.authority } returns "selected.provider"
        every { createdUri.scheme } returns ContentResolver.SCHEME_CONTENT
        every { createdUri.authority } returns "selected.provider"
        every { DocumentsContract.isDocumentUri(context, createdUri) } returns true
        every { DocumentsContract.isTreeUri(createdUri) } returns true
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "selected-tree"
        every { DocumentsContract.getTreeDocumentId(createdUri) } returns "selected-tree"
        every { DocumentsContract.deleteDocument(resolver, createdUri) } returns true
        videoFile = temporaryFolder.newFile("clip.mp4").apply { writeBytes(videoBytes) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reject file URI before opening or deleting it`() {
        every { createdUri.scheme } returns ContentResolver.SCHEME_FILE
        assertRejected()
    }

    @Test
    fun `reject other non-content schemes`() {
        every { createdUri.scheme } returns ContentResolver.SCHEME_ANDROID_RESOURCE
        assertRejected()
    }

    @Test
    fun `reject a different provider even with the same tree ID`() {
        every { createdUri.authority } returns "other.provider"
        assertRejected()
    }

    @Test
    fun `reject a different tree in the selected provider`() {
        every { DocumentsContract.getTreeDocumentId(createdUri) } returns "other-tree"
        assertRejected()
    }

    @Test
    fun `reject document URI without tree context`() {
        every { DocumentsContract.isTreeUri(createdUri) } returns false
        assertRejected()
    }

    @Test
    fun `reject tree root or malformed URI that is not a document`() {
        every { DocumentsContract.isDocumentUri(context, createdUri) } returns false
        assertRejected()
    }

    @Test
    fun `write and return the validated document URI`() {
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdUri) } returns output

        assertSame(createdUri, export())
        assertArrayEquals(if (motionPhoto) jpegBytes + videoBytes else jpegBytes, output.toByteArray())
        verify(exactly = 1) { resolver.openOutputStream(createdUri) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    @Test
    fun `clean up only the validated URI when opening fails`() {
        every { resolver.openOutputStream(createdUri) } returns null

        assertThrows(IOException::class.java) { export() }

        verify(exactly = 1) { DocumentsContract.deleteDocument(resolver, createdUri) }
        verify(exactly = 0) { document.delete() }
    }

    @Test
    fun `clean up only the validated URI when writing fails`() {
        val failure = IOException("Write failed")
        val output = mockk<OutputStream>(relaxed = true)
        every { output.write(any<ByteArray>()) } throws failure
        every { output.write(any<ByteArray>(), any(), any()) } throws failure
        every { resolver.openOutputStream(createdUri) } returns output

        assertSame(failure, assertThrows(IOException::class.java) { export() })

        verify(exactly = 1) { DocumentsContract.deleteDocument(resolver, createdUri) }
        verify(exactly = 0) { document.delete() }
    }

    private fun assertRejected() {
        assertThrows(IOException::class.java) { export() }
        verify { resolver wasNot Called }
        verify(exactly = 0) { document.delete() }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
    }

    private fun export(): Uri {
        var result: Uri? = null
        runTest {
            result = ExportStorage(context).save(ExportDestination(
                "photo.jpg", ExportFormat.JPEG, ExportConfig(), treeUri
            )) { output ->
                output.write(jpegBytes)
                if (motionPhoto) videoFile.inputStream().use { it.copyTo(output) }
            }.uri
        }
        return requireNotNull(result)
    }

    @Test
    fun `existing document receives a new collision suffix without overwriting`() {
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdUri) } returns output
        every { tree.findFile("photo.jpg") } returns document
        export()
        verify { tree.createFile("image/jpeg", "photo_1.jpg") }
        assertArrayEquals(if (motionPhoto) jpegBytes + videoBytes else jpegBytes, output.toByteArray())
    }

    @Test
    fun `cancelled writers remove the validated output`() {
        every { resolver.openOutputStream(createdUri) } returns ByteArrayOutputStream()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runTest {
                ExportStorage(context).save(ExportDestination(
                    "photo.jpg", ExportFormat.JPEG, ExportConfig(), treeUri
                )) { throw kotlinx.coroutines.CancellationException("Cancelled export") }
            }
        }
        verify { DocumentsContract.deleteDocument(resolver, createdUri) }
    }

    @Test
    fun `original still export copies bytes and cleans up on storage failure`() = runTest {
        mockkStatic(BitmapFactory::class)
        val original = byteArrayOf(255.toByte(), 216.toByte(), 42, 43, 255.toByte(), 217.toByte())
        val sourceUri = mockk<Uri>()
        val frame = CapturedFrame(150_000, 4, 3, ColorSpaceInfo(), VideoMetadata(dateTimePositionUs = 150_000))
        every { context.cacheDir } returns temporaryFolder.root
        every { resolver.openInputStream(sourceUri) } answers { ByteArrayInputStream(original + videoBytes) }
        every { BitmapFactory.decodeFile(any(), any()) } answers {
            secondArg<BitmapFactory.Options>().outWidth = 4
            secondArg<BitmapFactory.Options>().outHeight = 3
            null
        }
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdUri) } returns output
        val result = FrameExporter(context).exportOriginalImage(sourceUri, original.size.toLong(), "image/jpeg", frame,
            ExportConfig(originalImage = true), treeUri)
        org.junit.Assert.assertTrue(result is com.shangjin.frameecho.core.model.ExportResult.Success)
        org.junit.Assert.assertTrue((result as com.shangjin.frameecho.core.model.ExportResult.Success).originalImagePreserved)
        assertArrayEquals(original, output.toByteArray())

        every { resolver.openOutputStream(createdUri) } returns null
        val failed = FrameExporter(context).exportOriginalImage(sourceUri, original.size.toLong(), "image/jpeg", frame,
            ExportConfig(originalImage = true), treeUri)
        org.junit.Assert.assertTrue(failed is com.shangjin.frameecho.core.model.ExportResult.Error)
        verify(exactly = 1) { DocumentsContract.deleteDocument(resolver, createdUri) }
        org.junit.Assert.assertEquals(listOf("clip.mp4"), temporaryFolder.root.listFiles()!!.map { it.name })
    }

    @Test
    fun `original still export rejects a different frame before accessing source or storage`() = runTest {
        val frame = CapturedFrame(151_000, 4, 3, ColorSpaceInfo(), VideoMetadata(dateTimePositionUs = 150_000))
        val result = FrameExporter(context).exportOriginalImage(mockk(), 100, "image/jpeg", frame, ExportConfig(originalImage = true), treeUri)
        org.junit.Assert.assertTrue(result is com.shangjin.frameecho.core.model.ExportResult.Error)
        org.junit.Assert.assertTrue((result as com.shangjin.frameecho.core.model.ExportResult.Error).message.contains("cover timestamp"))
        verify { resolver wasNot Called }
    }
}
