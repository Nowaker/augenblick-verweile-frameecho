package com.shangjin.frameecho.core.media.source

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException

class SourceAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `nonseekable picture is staged and removed after parsing`() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        val asset = mockk<AssetFileDescriptor>(relaxed = true)
        val input = mockk<FileInputStream>(relaxed = true)
        val channel = mockk<FileChannel>()
        val cache = temporary.newFolder()
        val image = MotionPhotoFixtures.jpeg(MotionPhotoFixtures.legacy())
        val bytes = image + MotionPhotoFixtures.video
        every { uri.scheme } returns "content"
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns cache
        every { resolver.openAssetFileDescriptor(uri, "r") } returns asset
        every { asset.createInputStream() } returns input
        every { input.channel } returns channel
        every { channel.position() } throws IOException("Illegal seek")
        every { resolver.openInputStream(uri) } answers { bytes.inputStream() }

        val result = SourceAccess(context) {}.read(uri) { MotionPhotoParser(it) {}.parse() }

        assertEquals(image.size.toLong(), result?.offset)
        assertEquals(emptyList<File>(), cache.listFiles()!!.toList())
    }

    @Test
    fun `cancellation during staging deletes the partial cache file`() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        val asset = mockk<AssetFileDescriptor>(relaxed = true)
        val input = mockk<FileInputStream>(relaxed = true)
        val channel = mockk<FileChannel>()
        val cache = temporary.newFolder()
        every { uri.scheme } returns "content"
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns cache
        every { resolver.openAssetFileDescriptor(uri, "r") } returns asset
        every { asset.createInputStream() } returns input
        every { input.channel } returns channel
        every { channel.position() } throws IOException("Illegal seek")
        every { resolver.openInputStream(uri) } answers { ByteArray(128 * 1024).inputStream() }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            SourceAccess(context) { if (++checks == 2) throw CancellationException() }.read(uri) { error("Parser must not run") }
        }

        assertEquals(emptyList<File>(), cache.listFiles()!!.toList())
    }
}
