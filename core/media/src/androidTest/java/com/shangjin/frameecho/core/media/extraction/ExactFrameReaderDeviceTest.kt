package com.shangjin.frameecho.core.media.extraction

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExactFrameReaderDeviceTest {
    @Test
    fun separateReaderProfilesNeverBorrowAnotherReadersCachedFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "frameecho-profile-replay.mp4")
        instrumentation.context.assets.open("frameecho-open-gop.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val small = ExactFrameReader(context, Uri.fromFile(source), Size(320, 180))
        val full = ExactFrameReader(context, Uri.fromFile(source), Size(640, 360))
        try {
            val first = small.read(0L)
            assertEquals(320, first.bitmap.width)
            first.bitmap.recycle()
            val second = full.read(0L)
            try {
                assertEquals(0L, second.presentationTimeUs)
                assertEquals(640, second.bitmap.width)
                assertEquals(360, second.bitmap.height)
            } finally {
                second.bitmap.recycle()
            }
        } finally {
            small.release()
            full.release()
        }
    }

    @Test
    fun openGopSeekSequenceProducesRealFramesAndDoesNotBlockCapture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "frameecho-exact-replay.mp4")
        instrumentation.context.assets.open("frameecho-open-gop.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val uri = Uri.fromFile(source)
        val preview = ExactFrameReader(context, uri, Size(640, 360))
        val capture = ExactFrameReader(context, uri)
        fun status(message: String) = instrumentation.sendStatus(2, Bundle().apply { putString("replay", message) })
        try {
            supervisorScope {
            for ((positionUs, expectedUs) in listOf(0L to 0L, 33_000L to 33_333L, 66_000L to 66_666L, 100_000L to 100_000L, 133_000L to 133_333L, 166_000L to 166_666L, 333_000L to 333_333L, 800_000L to 800_000L, 1_400_000L to 1_400_000L)) {
                val started = SystemClock.elapsedRealtime()
                status("preview start targetUs=$positionUs")
                val frame = preview.read(positionUs)
                assertEquals(expectedUs, frame.presentationTimeUs)
                status("preview done targetUs=$positionUs actualUs=${frame.presentationTimeUs} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                frame.bitmap.recycle()
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                status("preview start targetUs=2266000")
                preview.read(2_266_000L).also {
                    assertEquals(2_266_666L, it.presentationTimeUs)
                    status("preview done targetUs=2266000 actualUs=${it.presentationTimeUs}")
                }
            }
            val completed = withTimeoutOrNull(13_000L) { pending.await() }
            status("capture start targetUs=3233333 previewComplete=${completed != null}")
            val full = capture.read(3_233_333L)
            assertEquals(3_233_333L, full.presentationTimeUs)
            assertEquals(640, full.bitmap.width)
            assertEquals(360, full.bitmap.height)
            status("capture done actualUs=${full.presentationTimeUs} size=${full.bitmap.width}x${full.bitmap.height}")
            full.bitmap.recycle()
            pending.await().bitmap.recycle()
            }
        } finally {
            preview.release()
            capture.release()
        }
    }
}
