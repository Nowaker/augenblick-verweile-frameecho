package com.shangjin.frameecho.app.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class GalleryItemKind { VIDEO, MOTION_PHOTO }

data class GalleryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    /** Capture time when known, else when the file was added, in epoch ms. */
    val dateMs: Long,
    val durationMs: Long?,
    val sizeBytes: Long,
    /** Changes whenever the file is edited; part of the motion-photo detection cache key. */
    val modifiedSeconds: Long,
    val kind: GalleryItemKind
) {
    val key: String get() = uri.toString()
}

/** Where the gallery gets its items; MediaStore in the app, a fake in tests. */
interface GallerySource {
    suspend fun videos(): List<GalleryItem>

    /** Newest stills in a format a motion photo can have, at most [limit]. */
    suspend fun stillCandidates(limit: Int): List<GalleryItem>

    suspend fun isMotionPhoto(item: GalleryItem): Boolean
}

class MediaStoreGallery(context: Context) : GallerySource {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun videos(): List<GalleryItem> = query(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        selection = null,
        selectionArgs = null,
        limit = MAX_VIDEOS,
        kind = GalleryItemKind.VIDEO
    )

    override suspend fun stillCandidates(limit: Int): List<GalleryItem> = query(
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        selection = "${MediaStore.MediaColumns.MIME_TYPE} IN (${STILL_MIME_TYPES.joinToString { "?" }})",
        selectionArgs = STILL_MIME_TYPES.toTypedArray(),
        limit = limit,
        kind = GalleryItemKind.MOTION_PHOTO
    )

    override suspend fun isMotionPhoto(item: GalleryItem): Boolean = withContext(Dispatchers.IO) {
        MotionPhotoDetector.isMotionPhoto(resolver, item.uri, item.sizeBytes)
    }

    private suspend fun query(
        collection: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
        kind: GalleryItemKind
    ): List<GalleryItem> = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(DATE_TAKEN)
            if (kind == GalleryItemKind.VIDEO) add(DURATION)
        }.toTypedArray()
        val items = mutableListOf<GalleryItem>()
        resolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val taken = cursor.getColumnIndex(DATE_TAKEN)
            val duration = cursor.getColumnIndex(DURATION)
            while (cursor.moveToNext() && items.size < limit) {
                currentCoroutineContext().ensureActive()
                val takenMs = if (taken >= 0 && !cursor.isNull(taken)) cursor.getLong(taken) else 0L
                items += GalleryItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(id)),
                    displayName = cursor.getString(name) ?: "",
                    mimeType = cursor.getString(mime),
                    dateMs = takenMs.takeIf { it > 0L } ?: (cursor.getLong(added) * 1000L),
                    durationMs = if (duration >= 0 && !cursor.isNull(duration)) cursor.getLong(duration) else null,
                    sizeBytes = cursor.getLong(size),
                    modifiedSeconds = cursor.getLong(modified),
                    kind = kind
                )
            }
        }
        items
    }

    private companion object {
        /** MediaColumns.DATE_TAKEN / DURATION exist on every API level for these tables. */
        const val DATE_TAKEN = "datetaken"
        const val DURATION = "duration"
        const val MAX_VIDEOS = 2_000
        val STILL_MIME_TYPES = listOf("image/jpeg", "image/heic", "image/heif", "image/avif")
    }
}
