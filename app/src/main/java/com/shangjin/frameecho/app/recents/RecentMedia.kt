package com.shangjin.frameecho.app.recents

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * How long FrameEcho can keep reading a recent file. Ordered from least to most durable.
 */
enum class RecentAccess {
    /**
     * Shared or opened through a one-off grant (ACTION_SEND, ACTION_VIEW). Readable only
     * while the sending app's grant lasts, usually until FrameEcho's task goes away.
     */
    TEMPORARY_GRANT,

    /** A MediaStore item, readable as long as the media permission is granted. */
    MEDIA_STORE,

    /** Picked with ACTION_OPEN_DOCUMENT and its read grant persisted. */
    PERSISTED_GRANT
}

/**
 * A file the user opened in FrameEcho. [uri] is the original content URI, never the
 * extracted motion-photo video, so reopening resolves the source again.
 */
data class RecentMedia(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val access: RecentAccess,
    val lastOpenedMs: Long,
    val lastSavedMs: Long? = null,
    val savedFrameCount: Int = 0
) {
    val lastAccessMs: Long get() = maxOf(lastOpenedMs, lastSavedMs ?: Long.MIN_VALUE)
    val hasSavedFrames: Boolean get() = savedFrameCount > 0
}

/** The outcome of a history edit: the entries to keep and those that fell off the end. */
data class RecentMediaUpdate(val entries: List<RecentMedia>, val evicted: List<RecentMedia>)

/**
 * Pure operations on the recent-files list. Only a successful open and a successful
 * frame save are recorded; browsing, failed opens and failed exports leave no trace.
 */
object RecentMediaHistory {
    /**
     * Upper bound on remembered files. Each persisted grant counts against Android's
     * per-app limit (128 before Android 11), so evicted entries also give their grant back.
     */
    const val MAX_ENTRIES = 60

    fun recordOpened(
        entries: List<RecentMedia>,
        uri: String,
        displayName: String,
        mimeType: String?,
        access: RecentAccess,
        nowMs: Long
    ): RecentMediaUpdate {
        val existing = entries.firstOrNull { it.uri == uri }
        val updated = existing?.copy(
            displayName = displayName,
            mimeType = mimeType ?: existing.mimeType,
            // Reopening a file through a share must not forget the durable grant it
            // was picked with earlier.
            access = maxOf(existing.access, access),
            lastOpenedMs = nowMs
        ) ?: RecentMedia(uri, displayName, mimeType, access, lastOpenedMs = nowMs)
        return trim(listOf(updated) + entries.filterNot { it.uri == uri })
    }

    fun recordSaved(
        entries: List<RecentMedia>,
        uri: String,
        displayName: String,
        mimeType: String?,
        access: RecentAccess,
        nowMs: Long
    ): RecentMediaUpdate {
        val existing = entries.firstOrNull { it.uri == uri }
            ?: RecentMedia(uri, displayName, mimeType, access, lastOpenedMs = nowMs)
        val updated = existing.copy(
            access = maxOf(existing.access, access),
            lastSavedMs = nowMs,
            savedFrameCount = existing.savedFrameCount + 1
        )
        return trim(listOf(updated) + entries.filterNot { it.uri == uri })
    }

    fun remove(entries: List<RecentMedia>, uri: String): RecentMediaUpdate {
        val (removed, kept) = entries.partition { it.uri == uri }
        return RecentMediaUpdate(kept, removed)
    }

    /** Most recently accessed first; optionally only files a frame was saved from. */
    fun visible(entries: List<RecentMedia>, onlyWithSavedFrames: Boolean): List<RecentMedia> =
        entries.filter { !onlyWithSavedFrames || it.hasSavedFrames }
            .sortedWith(compareByDescending<RecentMedia> { it.lastAccessMs }.thenBy { it.uri })

    private fun trim(entries: List<RecentMedia>): RecentMediaUpdate {
        val sorted = visible(entries, onlyWithSavedFrames = false)
        return RecentMediaUpdate(sorted.take(MAX_ENTRIES), sorted.drop(MAX_ENTRIES))
    }
}

/**
 * Line-based storage format: a version line, then one entry per line with URL-encoded,
 * tab-separated fields. Malformed lines are skipped rather than failing the whole list.
 */
object RecentMediaCodec {
    private const val VERSION = "v1"
    private const val FIELD_COUNT = 7

    fun encode(entries: List<RecentMedia>): String = buildString {
        append(VERSION)
        entries.forEach { entry ->
            append('\n')
            append(
                listOf(
                    entry.uri,
                    entry.displayName,
                    entry.mimeType.orEmpty(),
                    entry.access.name,
                    entry.lastOpenedMs.toString(),
                    entry.lastSavedMs?.toString().orEmpty(),
                    entry.savedFrameCount.toString()
                ).joinToString("\t") { URLEncoder.encode(it, Charsets.UTF_8.name()) }
            )
        }
    }

    fun decode(value: String?): List<RecentMedia> {
        val lines = value?.split('\n') ?: return emptyList()
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val fields = line.split('\t').map {
                runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() ?: return@mapNotNull null
            }
            if (fields.size != FIELD_COUNT || fields[0].isBlank()) return@mapNotNull null
            RecentMedia(
                uri = fields[0],
                displayName = fields[1],
                mimeType = fields[2].ifEmpty { null },
                access = RecentAccess.entries.firstOrNull { it.name == fields[3] } ?: return@mapNotNull null,
                lastOpenedMs = fields[4].toLongOrNull() ?: return@mapNotNull null,
                lastSavedMs = fields[5].ifEmpty { null }?.let { it.toLongOrNull() ?: return@mapNotNull null },
                savedFrameCount = fields[6].toIntOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            )
        }.distinctBy { it.uri }
    }
}
