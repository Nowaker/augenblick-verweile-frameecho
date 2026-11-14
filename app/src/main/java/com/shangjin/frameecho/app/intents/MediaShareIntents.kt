package com.shangjin.frameecho.app.intents

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat

object MediaShareIntents {
    fun readUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_VIEW -> localUris(listOfNotNull(intent.data))
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                localUris(extraStreams(intent)).ifEmpty {
                    localUris(intent.clipData?.let { clip ->
                        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    }.orEmpty())
                }
            }
            else -> emptyList()
        }
    }

    private fun localUris(uris: List<Uri>): List<Uri> = uris.map { it.normalizeScheme() }
        .filter { it.scheme in READABLE_SCHEMES }.distinct()

    private fun extraStreams(intent: Intent): List<Uri> = try {
        when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )?.filterIsInstance<Uri>().orEmpty()
            else -> emptyList()
        }
    } catch (_: BadParcelableException) {
        emptyList()
    }

    private val READABLE_SCHEMES = setOf(
        ContentResolver.SCHEME_CONTENT,
        ContentResolver.SCHEME_FILE,
        ContentResolver.SCHEME_ANDROID_RESOURCE
    )
}
