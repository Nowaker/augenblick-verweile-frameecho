package com.shangjin.frameecho.core.media.source

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class MediaSourceResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()
    private lateinit var cache: File
    private lateinit var source: File
    private val uri = mockk<Uri>()

    @Before
    fun setup() {
        cache = temporary.newFolder("cache")
        source = temporary.newFile("IMG_20241031_MP.jpg")
        every { context.applicationContext } returns context
        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cache
        every { uri.scheme } returns "file"
        every { uri.path } answers { source.absolutePath }
        every { uri.lastPathSegment } answers { source.name }
        every { contentResolver.getType(uri) } returns "image/jpeg"
        every { contentResolver.openInputStream(uri) } answers { source.inputStream() }
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers {
            val path = firstArg<File>().absolutePath
            mockk<Uri> { every { this@mockk.path } returns path }
        }
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Uri::class, Log::class)
    }

    @Test
    fun `resolution extracts only embedded video and release never deletes original`() = runBlocking {
        val image = MotionPhotoFixtures.jpeg(MotionPhotoFixtures.legacy())
        source.writeBytes(image + MotionPhotoFixtures.video)

        val resolved = MediaSourceResolver(context).resolve(uri)

        assertSame(uri, resolved.sourceUri)
        assertEquals(source.name, resolved.sourceFileName)
        assertEquals(source.name, resolved.sourceMetadata?.sourceFileName)
        assertEquals(123456L, resolved.sourceMetadata?.dateTimePositionUs)
        assertEquals(image.size.toLong(), resolved.originalImageLength)
        assertEquals("image/jpeg", resolved.originalImageMimeType)
        val extracted = File(resolved.videoUri.path!!)
        assertArrayEquals(MotionPhotoFixtures.video, extracted.readBytes())
        resolved.release()
        resolved.release()
        assertFalse(extracted.exists())
        assertTrue(source.exists())
    }

    @Test
    fun `standalone video retains original uri without a cache copy`() = runBlocking {
        source.writeBytes(MotionPhotoFixtures.video)
        every { contentResolver.getType(uri) } returns "video/mp4"

        val resolved = MediaSourceResolver(context).resolve(uri)

        assertSame(uri, resolved.videoUri)
        assertNull(resolved.sourceMetadata)
        assertNull(resolved.originalImageLength)
        resolved.release()
        assertEquals(emptyList<File>(), cache.listFiles()!!.toList())
        assertTrue(source.exists())
    }

    @Test
    fun `original bytes are read through the read uri while the picked uri stays the identity`() = runBlocking {
        val image = MotionPhotoFixtures.jpeg(MotionPhotoFixtures.legacy())
        source.writeBytes(image + MotionPhotoFixtures.video)
        // The redacted view (picked uri) must never be read once an original is available.
        every { contentResolver.openInputStream(uri) } throws AssertionError("read the redacted uri")
        val original = mockk<Uri> {
            every { scheme } returns "file"
            every { path } answers { source.absolutePath }
        }
        every { contentResolver.openInputStream(original) } answers { source.inputStream() }

        val motion = MediaSourceResolver(context) { original }.resolve(uri)
        assertSame(uri, motion.sourceUri)
        assertEquals(source.name, motion.sourceFileName)
        assertEquals(image.size.toLong(), motion.originalImageLength)
        motion.release()

        source.writeBytes(MotionPhotoFixtures.video)
        every { contentResolver.getType(uri) } returns "video/mp4"
        val video = MediaSourceResolver(context) { original }.resolve(uri)
        assertSame(uri, video.sourceUri)
        assertSame(original, video.videoUri)
        video.release()
    }

    @Test
    fun `provider display name survives standalone resolution`() = runBlocking {
        source.writeBytes(MotionPhotoFixtures.video)
        every { uri.scheme } returns "content"
        every { uri.authority } returns "com.example.files"
        every { contentResolver.getType(uri) } returns "video/mp4"
        val cursor = mockk<Cursor>(relaxed = true)
        every { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) } returns cursor
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns "Camera original.mp4"

        val resolved = MediaSourceResolver(context).resolve(uri)

        assertEquals("Camera original.mp4", resolved.sourceFileName)
        resolved.release()
    }

    @Test
    fun `plain still rejection leaves no cache files`() {
        source.writeBytes(MotionPhotoFixtures.jpeg())

        val error = assertThrows(MediaSourceException::class.java) {
            runBlocking { MediaSourceResolver(context).resolve(uri) }
        }

        assertEquals(MediaSourceException.Reason.NO_EMBEDDED_VIDEO, error.reason)
        assertEquals(emptyList<File>(), cache.listFiles()!!.toList())
    }

    @Test
    fun `cancellation while returning resolved video cleans the undelivered file`() {
        source.writeBytes(MotionPhotoFixtures.jpeg(MotionPhotoFixtures.legacy()) + MotionPhotoFixtures.video)
        val job = Job()
        every { Uri.fromFile(any()) } answers {
            job.cancel()
            mockk<Uri>()
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { MediaSourceResolver(context).resolve(uri) } }
        }

        assertEquals(emptyList<File>(), cache.listFiles()!!.toList())
        assertTrue(source.exists())
    }

    @Test
    fun `EXIF original date subseconds offset and camera survive extraction`() = runBlocking {
        val image = MotionPhotoFixtures.withExif(MotionPhotoFixtures.jpeg(MotionPhotoFixtures.legacy()))
        source.writeBytes(image + MotionPhotoFixtures.video)

        val resolved = MediaSourceResolver(context).resolve(uri)

        try {
            val metadata = resolved.sourceMetadata!!
            assertEquals("2024-10-31T23:45:01.123456+05:30", metadata.dateTime)
            assertEquals("Google", metadata.make)
            assertEquals("Pixel 9", metadata.model)
            assertEquals(200, metadata.iso)
            assertEquals(1.8, metadata.fNumber!!, 0.0001)
            assertEquals(51.5, metadata.latitude!!, 0.0001)
            assertEquals(-0.125, metadata.longitude!!, 0.0001)
        } finally {
            resolved.release()
        }
    }
}
