package com.shangjin.frameecho.core.media.source

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A bounded view of a provider asset; offsets are relative to that asset, not its descriptor. */
internal class SourceData(
    private val channel: FileChannel,
    val length: Long,
    private val startOffset: Long = 0
) {
    init {
        if (startOffset < 0 || length < 0 || startOffset > Long.MAX_VALUE - length) {
            malformed("Invalid source extent")
        }
    }

    fun read(offset: Long, count: Int): ByteArray {
        checkRange(offset, count.toLong())
        val bytes = ByteArray(count)
        val buffer = ByteBuffer.wrap(bytes)
        var position = startOffset + offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) throw EOFException("Truncated media source")
            position += read
        }
        return bytes
    }

    fun checkRange(offset: Long, count: Long) {
        if (offset < 0 || count < 0 || offset > length || count > length - offset) {
            malformed("Media extent is outside the source")
        }
    }

    fun stream(limit: Long = length): InputStream {
        checkRange(0, limit)
        return object : InputStream() {
            private var position = 0L

            override fun read(): Int = if (position == limit) -1 else {
                this@SourceData.read(position++, 1)[0].toInt() and 0xff
            }

            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (offset < 0 || count < 0 || offset > bytes.size - count) {
                    throw IndexOutOfBoundsException()
                }
                if (count == 0) return 0
                if (position == limit) return -1
                val size = minOf(count.toLong(), limit - position).toInt()
                val buffer = ByteBuffer.wrap(bytes, offset, size)
                val read = channel.read(buffer, startOffset + position)
                if (read <= 0) throw EOFException("Truncated media source")
                position += read
                return read
            }

            override fun skip(count: Long): Long {
                val skipped = count.coerceIn(0, limit - position)
                position += skipped
                return skipped
            }

            override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), limit - position).toInt()
        }
    }
}

internal fun ByteArray.uint32(offset: Int = 0): Long =
    (0 until 4).fold(0L) { value, index -> (value shl 8) or (this[offset + index].toLong() and 0xff) }

internal fun ByteArray.fourcc(offset: Int = 0): String = String(this, offset, 4, Charsets.ISO_8859_1)

internal const val MAX_XMP_BYTES = 1_048_576
