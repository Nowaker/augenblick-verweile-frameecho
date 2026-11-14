package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.shangjin.frameecho.core.model.CaptureTime
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportDirectory
import com.shangjin.frameecho.core.model.ExportFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant

class ExportStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val numericValues = mutableListOf<Pair<String, Long>>()
    private val config = ExportConfig(exportDirectory = ExportDirectory.PICTURES)

    @Before
    fun setup() {
        mockkConstructor(ContentValues::class)
        mockkStatic(Environment::class)
        every { context.contentResolver } returns resolver
        every { Environment.getExternalStoragePublicDirectory(any()) } returns temporaryFolder.root
        every { anyConstructed<ContentValues>().put(any<String>(), any<String>()) } returns Unit
        every { anyConstructed<ContentValues>().put(any<String>(), any<Long>()) } answers {
            numericValues += firstArg<String>() to secondArg<Long>()
        }
        every { anyConstructed<ContentValues>().size() } returns 1
        every { resolver.insert(any(), any()) } returns uri
        every { resolver.update(uri, any(), null, null) } returns 1
        every { resolver.delete(uri, null, null) } returns 1
    }

    @After
    fun tearDown() { unmockkAll() }

    @Test
    fun `final metadata bytes retain microsecond mtime and correct MediaStore date units`() = runTest {
        val file = java.io.File(temporaryFolder.root, "frame.jpg")
        val bytes = byteArrayOf(1, 2, 3, 4)
        every { resolver.openOutputStream(uri) } answers { file.outputStream() }
        every { resolver.update(uri, any(), null, null) } answers {
            Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse("2026-11-14T13:47:01Z")))
            1
        }
        mockkObject(FileDescriptorTimes)
        every { FileDescriptorTimes.restore(any(), any()) } answers {
            Files.setLastModifiedTime(file.toPath(), FileTime.from(secondArg<Instant>()))
            0
        }
        every { FileDescriptorTimes.read(any()) } answers { Files.getLastModifiedTime(file.toPath()).toInstant() }
        every { resolver.openFileDescriptor(uri, "r") } answers {
            val handle = RandomAccessFile(file, "r")
            val descriptor = mockk<ParcelFileDescriptor>()
            val number = Files.list(Paths.get("/proc/self/fd")).use { paths ->
                paths.filter { path ->
                    try { Files.readSymbolicLink(path) == file.toPath() } catch (_: IOException) { false }
                }.findFirst().orElseThrow().fileName.toString().toInt()
            }
            every { descriptor.fd } returns number
            every { descriptor.close() } answers { handle.close() }
            descriptor
        }
        val stored = ExportStorage(context).save(ExportDestination("frame.jpg", ExportFormat.JPEG, config,
            captureTime = CaptureTime.parse("2024-01-01T00:00:00.123456Z"))) { it.write(bytes) }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(Instant.parse("2024-01-01T00:00:00.123456Z"), Files.getLastModifiedTime(file.toPath()).toInstant())
        assertEquals(listOf(1704067200123L, 1704067200123L), numericValues.filter { it.first == MediaStore.Images.Media.DATE_TAKEN }.map { it.second })
        assertEquals(listOf(1704067200L, 1704067200L), numericValues.filter { it.first == MediaStore.Images.Media.DATE_MODIFIED }.map { it.second })
        assertEquals(4L, stored.size)
        assertTrue(stored.timestampWarnings.any { it.contains("ctime") })
    }

    @Test
    fun `legacy name collisions preserve existing bytes`() = runTest {
        val existing = temporaryFolder.newFile("frame.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val next = java.io.File(temporaryFolder.root, "frame_1.jpg")
        every { resolver.openOutputStream(uri) } answers { next.outputStream() }
        every { resolver.openFileDescriptor(uri, "r") } returns null
        ExportStorage(context).save(ExportDestination("frame.jpg", ExportFormat.JPEG, config)) { it.write(byteArrayOf(1)) }
        assertArrayEquals(byteArrayOf(9, 8, 7), existing.readBytes())
        assertArrayEquals(byteArrayOf(1), next.readBytes())
    }
}
