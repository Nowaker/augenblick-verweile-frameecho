package com.shangjin.frameecho.core.media.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

object HeicSupport {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 28 && try {
        supportsEncoding(Build.VERSION.SDK_INT, MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos)
    } catch (_: RuntimeException) {
        false
    }

    internal fun supportsEncoding(sdkInt: Int, codecs: Array<MediaCodecInfo>): Boolean = sdkInt >= 28 && codecs.any { codec ->
        codec.isEncoder && codec.supportedTypes.any { mime ->
            (mime.equals(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, true) || mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)) &&
                codec.getCapabilitiesForType(mime).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
    }
}
