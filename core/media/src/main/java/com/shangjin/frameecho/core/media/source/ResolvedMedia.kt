package com.shangjin.frameecho.core.media.source

import android.net.Uri
import com.shangjin.frameecho.core.model.VideoMetadata
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Owns an extracted video until playback, extraction and export have all stopped using it. */
class ResolvedMedia internal constructor(
    val videoUri: Uri,
    val sourceUri: Uri,
    val sourceFileName: String,
    /** Still capture time, including subseconds and offset; anchored at [presentationTimestampUs]. */
    val sourceMetadata: VideoMetadata? = null,
    val presentationTimestampUs: Long? = null,
    /** Copy [0, originalImageLength) from sourceUri to preserve the encoded still unchanged. */
    val originalImageLength: Long? = null,
    val originalImageMimeType: String? = null,
    private var ownedVideo: File? = null
) : Closeable {
    @Synchronized
    fun release() {
        ownedVideo?.let { Files.deleteIfExists(it.toPath()) }
        ownedVideo = null
    }

    override fun close() = release()
}
