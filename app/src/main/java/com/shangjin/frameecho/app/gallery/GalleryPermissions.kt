package com.shangjin.frameecho.app.gallery

import android.Manifest

/** What the in-app gallery is allowed to list. */
data class GalleryAccess(
    val videos: Boolean,
    val images: Boolean,
    /** Android 14+ "Select photos and videos": only items the user picked are visible. */
    val selectedOnly: Boolean
) {
    val canList: Boolean get() = videos || images || selectedOnly
    val isFull: Boolean get() = videos && images && !selectedOnly
}

/**
 * Media permissions differ across the API levels FrameEcho supports:
 *
 * - API 26-32: one READ_EXTERNAL_STORAGE covers videos and images.
 * - API 33: READ_MEDIA_VIDEO and READ_MEDIA_IMAGES are separate; images are needed only
 *   to find motion photos, so videos alone still give a useful gallery.
 * - API 34+: the user may grant READ_MEDIA_VISUAL_USER_SELECTED instead, which exposes
 *   only the items picked in the system dialog. Requesting it alongside the other two
 *   is what makes the dialog offer "Select photos and videos".
 */
object GalleryPermissions {
    private const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun requested(sdkInt: Int): List<String> = when {
        sdkInt >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            READ_MEDIA_VISUAL_USER_SELECTED
        )
        sdkInt >= 33 -> listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(sdkInt: Int, isGranted: (String) -> Boolean): GalleryAccess = when {
        sdkInt >= 33 -> {
            val videos = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            val images = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            GalleryAccess(
                videos = videos,
                images = images,
                selectedOnly = sdkInt >= 34 && !videos && !images && isGranted(READ_MEDIA_VISUAL_USER_SELECTED)
            )
        }
        else -> isGranted(Manifest.permission.READ_EXTERNAL_STORAGE).let { GalleryAccess(it, it, false) }
    }
}
