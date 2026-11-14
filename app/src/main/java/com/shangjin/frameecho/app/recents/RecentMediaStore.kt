package com.shangjin.frameecho.app.recents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.recentMediaDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "recent_media")

/** Where the player reports successful opens and saves. */
interface RecentMediaRecorder {
    suspend fun recordOpened(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess)
    suspend fun recordSaved(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess)
}

/**
 * Persists [RecentMediaHistory]. [onEvicted] receives entries that left the list, so the
 * caller can give back read grants nothing refers to any more.
 */
class RecentMediaStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEvicted: (List<RecentMedia>) -> Unit = {}
) : RecentMediaRecorder {
    val entries: Flow<List<RecentMedia>> = dataStore.data
        .map { RecentMediaCodec.decode(it[KEY_ENTRIES]) }
        .distinctUntilChanged()

    override suspend fun recordOpened(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess) = update {
        RecentMediaHistory.recordOpened(it, uri.toString(), displayName, mimeType, access, clock())
    }

    override suspend fun recordSaved(uri: Uri, displayName: String, mimeType: String?, access: RecentAccess) = update {
        RecentMediaHistory.recordSaved(it, uri.toString(), displayName, mimeType, access, clock())
    }

    suspend fun remove(uri: String) = update { RecentMediaHistory.remove(it, uri) }

    private suspend fun update(edit: (List<RecentMedia>) -> RecentMediaUpdate) {
        var evicted = emptyList<RecentMedia>()
        dataStore.edit { prefs ->
            val result = edit(RecentMediaCodec.decode(prefs[KEY_ENTRIES]))
            prefs[KEY_ENTRIES] = RecentMediaCodec.encode(result.entries)
            evicted = result.evicted
        }
        if (evicted.isNotEmpty()) onEvicted(evicted)
    }

    companion object {
        private val KEY_ENTRIES = stringPreferencesKey("entries")

        fun create(context: Context): RecentMediaStore {
            val appContext = context.applicationContext
            return RecentMediaStore(appContext.recentMediaDataStore) { evicted ->
                evicted.filter { it.access == RecentAccess.PERSISTED_GRANT }.forEach { entry ->
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            entry.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
            }
        }

        /**
         * Whether [entry] can still be opened. A temporary grant ends with the task that
         * received it and a deleted file disappears from its provider; both read as false
         * instead of failing later inside the player.
         */
        suspend fun isReadable(context: Context, entry: RecentMedia): Boolean = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(
                    entry.uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { it.moveToFirst() } ?: false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
        }
    }
}
