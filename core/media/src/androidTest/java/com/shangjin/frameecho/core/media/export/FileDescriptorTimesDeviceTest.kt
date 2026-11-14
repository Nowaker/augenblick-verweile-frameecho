package com.shangjin.frameecho.core.media.export

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FileDescriptorTimesDeviceTest {
    @Test
    fun restoresNanosecondsOnAnOwnedFileWithoutClosingItsDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("frameecho_time_", ".data", context.cacheDir)
        val expected = Instant.parse("2026-11-14T06:49:57.356789123Z")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                assertEquals(0, FileDescriptorTimes.restore(descriptor.fd, expected))
                assertEquals(expected, FileDescriptorTimes.read(descriptor.fd))
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                assertEquals(expected, FileDescriptorTimes.read(descriptor.fd))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun reportsTheKernelErrorForAnInvalidDescriptor() {
        assertEquals(OsConstants.EBADF, FileDescriptorTimes.restore(-1, Instant.EPOCH))
    }
}
