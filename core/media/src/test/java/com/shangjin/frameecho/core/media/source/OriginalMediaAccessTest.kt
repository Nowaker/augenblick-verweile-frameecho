package com.shangjin.frameecho.core.media.source

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalMediaAccessTest {

    private fun uri(scheme: String, authority: String, vararg segments: String): Uri = mockk {
        every { this@mockk.scheme } returns scheme
        every { this@mockk.authority } returns authority
        every { pathSegments } returns segments.toList()
    }

    private val original = mockk<Uri>()
    private val requireOriginal: (Uri) -> Uri = { original }

    @Test
    fun `only items of MediaStore image, video and file collections qualify`() {
        assertTrue(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external", "images", "media", "42")))
        assertTrue(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external_primary", "video", "media", "7")))
        assertTrue(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external", "file", "9")))

        // Photo picker, documents, other collections, collection roots and other schemes.
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(
            uri("content", "media", "picker", "0", "com.android.providers.media.photopicker", "media", "42")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(
            uri("content", "media", "picker_get_content", "0", "com.android.providers.media.photopicker", "media", "42")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(
            uri("content", "com.android.providers.media.documents", "document", "image:42")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external", "audio", "media", "3")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external", "downloads", "5")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(uri("content", "media", "external", "images", "media")))
        assertFalse(OriginalMediaAccess.isMediaStoreCollectionItem(uri("file", "", "sdcard", "DCIM", "1")))
    }

    @Test
    fun `the original is requested only with consent on Android 10 and later`() {
        val item = uri("content", "media", "external", "images", "media", "42")

        assertSame(original, OriginalMediaAccess.readUri(item, sdkInt = 29, locationGranted = true, requireOriginal))
        assertSame(item, OriginalMediaAccess.readUri(item, sdkInt = 34, locationGranted = false, requireOriginal))
        assertSame(item, OriginalMediaAccess.readUri(item, sdkInt = 28, locationGranted = true, requireOriginal))
    }

    @Test
    fun `picker and document uris keep their provided contract even with consent`() {
        val picker = uri("content", "media", "picker", "0", "com.android.providers.media.photopicker", "media", "42")
        val document = uri("content", "com.android.providers.media.documents", "document", "video:7")

        assertSame(picker, OriginalMediaAccess.readUri(picker, 34, true, requireOriginal))
        assertSame(document, OriginalMediaAccess.readUri(document, 34, true, requireOriginal))
    }
}
