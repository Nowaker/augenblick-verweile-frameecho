package com.shangjin.frameecho.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shangjin.frameecho.app.intents.MediaShareIntents
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaShareIntentsDeviceTest {
    @Test
    fun nativeShareAndViewPayloadsPreserveOrderAndUseClipFallback() {
        val first = Uri.parse("content://frameecho.fixture/media/one")
        val second = Uri.parse("content://frameecho.fixture/media/two")
        val clips = ClipData.newRawUri("Fixture", first).apply { addItem(ClipData.Item(second)) }
        val cases = listOf(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first) to listOf(first),
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, arrayListOf(first, second, first)
            ) to listOf(first, second),
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { clipData = clips } to listOf(first, second),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "wrong extra type")
                .apply { clipData = clips } to listOf(first, second),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.invalid/"))
                .apply { clipData = clips } to listOf(first, second),
            Intent(Intent.ACTION_VIEW, first) to listOf(first),
            Intent(Intent.ACTION_VIEW, Uri.parse("CONTENT://frameecho.fixture/media/one")) to listOf(first),
            Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/fixture.mp4")) to
                listOf(Uri.parse("file:///sdcard/fixture.mp4")),
            Intent(Intent.ACTION_VIEW, Uri.parse("android.resource://frameecho.fixture/raw/video")) to
                listOf(Uri.parse("android.resource://frameecho.fixture/raw/video")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/video.mp4")) to emptyList(),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "not a media stream") to emptyList(),
            Intent(Intent.ACTION_MAIN, first) to emptyList()
        )

        for ((intent, expected) in cases) {
            val parcel = Parcel.obtain()
            try {
                intent.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val incoming = Intent.CREATOR.createFromParcel(parcel)

                assertEquals("Action ${intent.action}", expected, MediaShareIntents.readUris(incoming))
            } finally {
                parcel.recycle()
            }
        }
    }
}
