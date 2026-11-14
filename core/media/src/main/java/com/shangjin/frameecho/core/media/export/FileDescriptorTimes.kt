package com.shangjin.frameecho.core.media.export

import android.system.ErrnoException
import java.time.Instant

internal object FileDescriptorTimes {
    private val library by lazy { System.loadLibrary("frameecho_file_times") }

    fun restore(fd: Int, time: Instant): Int {
        library
        return restoreNative(fd, time.epochSecond, time.nano)
    }

    fun read(fd: Int): Instant {
        library
        val values = readNative(fd)
        if (values[2] != 0L) throw ErrnoException("fstat", values[2].toInt())
        return Instant.ofEpochSecond(values[0], values[1])
    }

    private external fun restoreNative(fd: Int, seconds: Long, nanoseconds: Int): Int
    private external fun readNative(fd: Int): LongArray
}
