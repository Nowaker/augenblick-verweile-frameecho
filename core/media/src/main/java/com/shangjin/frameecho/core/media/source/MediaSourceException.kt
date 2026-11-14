package com.shangjin.frameecho.core.media.source

import java.io.IOException

/** A readable input that cannot supply the requested video. */
class MediaSourceException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { NO_EMBEDDED_VIDEO, MALFORMED_MOTION_PHOTO }
}

internal fun malformed(message: String): Nothing = throw MediaSourceException(
    MediaSourceException.Reason.MALFORMED_MOTION_PHOTO, message
)

internal fun noVideo(): Nothing = throw MediaSourceException(
    MediaSourceException.Reason.NO_EMBEDDED_VIDEO,
    "This picture has no embedded motion video. A paired Live Photo requires its separate video file."
)
