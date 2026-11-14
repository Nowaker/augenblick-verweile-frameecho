package com.shangjin.frameecho.core.media.source

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files

internal class SourceAccess(private val context: Context, private val checkCancellation: () -> Unit) {
    fun <T> read(uri: Uri, block: (SourceData) -> T): T {
        if (uri.scheme == "file") {
            FileInputStream(uri.path ?: throw IOException("Missing media path")).use {
                return block(SourceData(it.channel, it.channel.size()))
            }
        }
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open media source")
        descriptor.use { asset ->
            asset.createInputStream().use { input ->
                val size = try {
                    input.channel.position()
                    input.channel.size().takeIf { it > 0 }
                } catch (_: IOException) {
                    null
                }
                if (size != null) {
                    val length = if (asset.declaredLength >= 0) asset.declaredLength else size - asset.startOffset
                    if (asset.startOffset > size || length > size - asset.startOffset) malformed("Truncated provider asset")
                    return block(SourceData(input.channel, length, asset.startOffset))
                }
            }
        }
        val staged = File.createTempFile("motion_source_", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancellation()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 512L * 1024 * 1024) throw IOException("Streamed Motion Photo exceeds the 512 MiB cache limit")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Cannot open media stream")
            FileInputStream(staged).use { return block(SourceData(it.channel, it.channel.size())) }
        } finally {
            Files.deleteIfExists(staged.toPath())
        }
    }
}
