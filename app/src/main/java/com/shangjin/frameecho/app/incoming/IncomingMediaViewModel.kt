package com.shangjin.frameecho.app.incoming

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangjin.frameecho.app.recents.RecentAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Name and type of a received file, as its provider reports them. */
data class IncomingDescription(val displayName: String, val mimeType: String?)

/**
 * Activity-scoped holder of [IncomingMediaQueue]. Looking up names and types happens off
 * the main thread; [openRequests] tells the screen which file to open.
 */
class IncomingMediaViewModel(
    private val describe: suspend (Uri) -> IncomingDescription,
    /** Keeps read access past this task when the sender allowed it; true on success. */
    private val takePersistableRead: (Uri) -> Boolean
) : ViewModel() {

    private val _queue = MutableStateFlow(IncomingMediaQueue())
    val queue: StateFlow<IncomingMediaQueue> = _queue.asStateFlow()

    private val _notice = MutableStateFlow<IncomingOutcome.NothingSupported?>(null)
    val notice: StateFlow<IncomingOutcome.NothingSupported?> = _notice.asStateFlow()

    private val _openRequests = Channel<IncomingItem>(Channel.BUFFERED)
    val openRequests: Flow<IncomingItem> = _openRequests.receiveAsFlow()

    /** True from the moment an intent with files arrives until it has been handled. */
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private var receiveJob: Job? = null

    /**
     * Handle the files of one intent. [persistable] is whether the sender granted
     * FLAG_GRANT_PERSISTABLE_URI_PERMISSION, as ACTION_OPEN_DOCUMENT-backed apps do.
     */
    fun receive(uris: List<Uri>, intentType: String?, persistable: Boolean) {
        if (uris.isEmpty()) return
        _pending.value = true
        receiveJob?.cancel()
        receiveJob = viewModelScope.launch {
            try {
                val items = uris.map { uri ->
                    val description = describe(uri)
                    val durable = persistable && takePersistableRead(uri)
                    IncomingItem(
                        uri = uri,
                        displayName = description.displayName,
                        mimeType = description.mimeType,
                        access = if (durable) RecentAccess.PERSISTED_GRANT else RecentAccess.TEMPORARY_GRANT,
                        supported = IncomingMediaQueue.isSupported(description.mimeType, intentType)
                    )
                }
                apply(_queue.value.receive(items))
            } finally {
                _pending.value = false
            }
        }
    }

    fun pick(item: IncomingItem) = apply(_queue.value.pick(item.uri))

    fun onMediaClosed() {
        _queue.value = _queue.value.onMediaClosed()
    }

    fun dismiss() {
        _queue.value = _queue.value.dismiss()
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun apply(result: Pair<IncomingMediaQueue, IncomingOutcome?>) {
        _queue.value = result.first
        when (val outcome = result.second) {
            is IncomingOutcome.Open -> _openRequests.trySend(outcome.item)
            is IncomingOutcome.NothingSupported -> _notice.value = outcome
            IncomingOutcome.ShowChooser, null -> Unit
        }
    }

    companion object {
        fun create(context: Context): IncomingMediaViewModel {
            val resolver = context.applicationContext.contentResolver
            return IncomingMediaViewModel(
                describe = { uri ->
                    withContext(Dispatchers.IO) {
                        val name = runCatching {
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                            }
                        }.getOrNull()
                        IncomingDescription(
                            displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: uri.toString(),
                            mimeType = runCatching { resolver.getType(uri) }.getOrNull()
                        )
                    }
                },
                takePersistableRead = { uri ->
                    try {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        true
                    } catch (_: SecurityException) {
                        false
                    }
                }
            )
        }
    }
}
