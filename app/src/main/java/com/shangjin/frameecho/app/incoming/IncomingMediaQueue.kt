package com.shangjin.frameecho.app.incoming

import android.net.Uri
import com.shangjin.frameecho.app.recents.RecentAccess

/** One file another app shared with or opened in FrameEcho. */
data class IncomingItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val access: RecentAccess,
    /** False for types FrameEcho cannot open at all (documents, audio, ...). */
    val supported: Boolean,
    val opened: Boolean = false
)

/** What the shared files lead to: opening one at once, a chooser, or an explanation. */
sealed interface IncomingOutcome {
    data class Open(val item: IncomingItem) : IncomingOutcome
    data object ShowChooser : IncomingOutcome
    data class NothingSupported(val onlyItemName: String?) : IncomingOutcome
}

/**
 * Files received from SEND, SEND_MULTIPLE and VIEW intents, kept until the user closes
 * the chooser. A single openable file opens at once; several are listed so the user
 * picks one now and can come back for the others after closing the player.
 */
data class IncomingMediaQueue(
    val items: List<IncomingItem> = emptyList(),
    val chooserVisible: Boolean = false
) {
    val remaining: List<IncomingItem> get() = items.filter { it.supported && !it.opened }

    /** A new intent replaces the previous batch: it is what the user just shared. */
    fun receive(received: List<IncomingItem>): Pair<IncomingMediaQueue, IncomingOutcome?> {
        if (received.isEmpty()) return this to null
        val supported = received.filter { it.supported }
        return when {
            supported.isEmpty() -> IncomingMediaQueue() to
                IncomingOutcome.NothingSupported(received.singleOrNull()?.displayName)
            received.size == 1 -> IncomingMediaQueue() to IncomingOutcome.Open(supported.single())
            else -> IncomingMediaQueue(received, chooserVisible = true) to IncomingOutcome.ShowChooser
        }
    }

    fun pick(uri: Uri): Pair<IncomingMediaQueue, IncomingOutcome?> {
        val item = items.firstOrNull { it.uri == uri && it.supported } ?: return this to null
        return copy(
            items = items.map { if (it.uri == uri) it.copy(opened = true) else it },
            chooserVisible = false
        ) to IncomingOutcome.Open(item)
    }

    /** Back from the player: offer the files that weren't opened yet. */
    fun onMediaClosed(): IncomingMediaQueue =
        if (remaining.isNotEmpty()) copy(chooserVisible = true) else IncomingMediaQueue()

    fun dismiss(): IncomingMediaQueue = IncomingMediaQueue()

    companion object {
        /**
         * Videos open directly and pictures may be motion photos, which the resolver tells
         * apart from plain stills with its own message. Anything else is refused up front.
         * A file of unknown type is attempted, since some providers report none.
         */
        fun isSupported(mimeType: String?, intentType: String?): Boolean {
            val type = mimeType?.takeIf { it.isNotBlank() && it != "*/*" && it != "application/octet-stream" }
                ?: intentType?.takeIf { it.isNotBlank() && !it.startsWith("*/") }
                ?: return true
            return type.startsWith("video/") || type.startsWith("image/")
        }
    }
}
