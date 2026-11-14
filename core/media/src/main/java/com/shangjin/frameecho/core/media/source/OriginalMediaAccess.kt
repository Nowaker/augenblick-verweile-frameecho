package com.shangjin.frameecho.core.media.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Chooses the URI to read a source's bytes through, so its metadata arrives unredacted.
 *
 * Since Android 10, MediaStore strips location from photos and videos unless the app
 * holds ACCESS_MEDIA_LOCATION and asks for the original with
 * [MediaStore.setRequireOriginal]. That applies only to items of MediaStore's own
 * images, video and file collections: photo-picker and document URIs carry their
 * provider's own access contract, and forcing them through MediaStore would need read
 * grants the app was never given. Those, and everything when the permission is
 * missing, are read as provided.
 *
 * The returned URI is for reading only; recents, names and source identity keep the
 * URI the user picked. With the original requested, the provider either serves the
 * original bytes or throws, and that failure is reported rather than retried redacted.
 */
object OriginalMediaAccess {
    private const val API_ACCESS_MEDIA_LOCATION = 29
    private val COLLECTIONS = setOf("images", "video", "file")

    fun readUri(context: Context, uri: Uri): Uri {
        if (!isMediaStoreCollectionItem(uri)) return uri
        val granted = Build.VERSION.SDK_INT >= API_ACCESS_MEDIA_LOCATION && canReadOriginal(context)
        return readUri(uri, Build.VERSION.SDK_INT, granted, MediaStore::setRequireOriginal)
    }

    /** Whether the user allowed location metadata of photos and videos to be read. */
    fun canReadOriginal(context: Context): Boolean = Build.VERSION.SDK_INT >= API_ACCESS_MEDIA_LOCATION &&
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    internal fun readUri(uri: Uri, sdkInt: Int, locationGranted: Boolean, requireOriginal: (Uri) -> Uri): Uri =
        if (sdkInt >= API_ACCESS_MEDIA_LOCATION && locationGranted && isMediaStoreCollectionItem(uri)) {
            requireOriginal(uri)
        } else uri

    /**
     * An item of a MediaStore collection, e.g. `content://media/external/images/media/42`
     * or `content://media/external_primary/file/7`. Picker URIs such as
     * `content://media/picker/0/<authority>/media/42` are not.
     */
    internal fun isMediaStoreCollectionItem(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
        val segments = uri.pathSegments
        return segments.size >= 3 &&
            !segments[0].startsWith("picker") &&
            segments[1] in COLLECTIONS &&
            segments.last().let { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
