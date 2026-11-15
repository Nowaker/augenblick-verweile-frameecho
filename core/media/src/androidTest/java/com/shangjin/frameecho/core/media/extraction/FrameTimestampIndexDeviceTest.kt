package com.shangjin.frameecho.core.media.extraction

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FrameTimestampIndexDeviceTest {
    @Test
    fun syncSelectionUsesPresentationOrderForLeadingAndTrailingPictures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "frameecho-sync-index.mp4")
        instrumentation.context.assets.open("frameecho-open-gop.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val syncs = mutableListOf<Long>()
            while (extractor.sampleTime >= 0L) {
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs += extractor.sampleTime
                if (!extractor.advance()) break
            }
            syncs.sort()
            assertTrue("The fixture must contain multiple open GOPs", syncs.size >= 3)
            val sync = syncs[1]
            val uri = Uri.fromFile(source)
            val index = FrameTimestampIndex(context)
            index.nearest(uri, sync - 1L)
            assertEquals(syncs[0], index.syncAtOrBefore(uri, sync - 1L))
            assertEquals(sync, index.syncAtOrBefore(uri, sync + 66_666L))
            extractor.seekTo(index.syncAtOrBefore(uri, sync - 1L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            assertEquals(syncs[0], extractor.sampleTime)
            extractor.seekTo(index.syncAtOrBefore(uri, sync + 66_666L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            assertEquals(sync, extractor.sampleTime)
        } finally {
            extractor.release()
        }
    }
}
