package com.shangjin.frameecho.core.media.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.shangjin.frameecho.core.model.CaptureTime
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.ZoneId

internal data class ExportDestination(
    val fileName: String,
    val format: ExportFormat,
    val config: ExportConfig,
    val treeUri: Uri? = null,
    val captureTime: CaptureTime? = null
)

internal data class StoredExport(val uri: Uri, val size: Long, val timestampWarnings: List<String>)

/** Owns a pending output until the final bytes, metadata and timestamps have been written. */
internal class ExportStorage(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun save(destination: ExportDestination, write: suspend (OutputStream) -> Unit): StoredExport {
        val uri = destination.treeUri?.let { createDocument(destination, it) } ?: createMedia(destination)
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Failed to open export output")
            var size = 0L
            val counted = object : FilterOutputStream(output) {
                override fun write(value: Int) {
                    out.write(value)
                    size++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, length)
                    size += length
                }
            }
            counted.use { write(it) }
            currentCoroutineContext().ensureActive()
            val warnings = finish(uri, destination)
            return StoredExport(uri, size, warnings)
        } catch (failure: Throwable) {
            try {
                if (destination.treeUri != null) DocumentsContract.deleteDocument(resolver, uri)
                else resolver.delete(uri, null, null)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun createDocument(destination: ExportDestination, treeUri: Uri): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Cannot access custom directory")
        val base = destination.fileName.substringBeforeLast('.')
        val extension = destination.fileName.substringAfterLast('.')
        var name = destination.fileName
        var suffix = 1
        while (tree.findFile(name) != null) name = "${base}_${suffix++}.$extension"
        val uri = tree.createFile(destination.format.mimeType, name)?.uri ?: throw IOException("Cannot create export document")
        // Never open or delete an arbitrary URI returned by an untrusted document provider.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != treeUri.authority ||
            !DocumentsContract.isDocumentUri(context, uri) || !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(treeUri)
        ) throw IOException("Custom directory provider returned an invalid document URI")
        return uri
    }

    private fun createMedia(destination: ExportDestination): Uri {
        var legacyFile: File? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, destination.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, destination.format.mimeType)
                destination.captureTime?.let {
                    put(MediaStore.Images.Media.DATE_TAKEN, it.epochMillis)
                    put(MediaStore.Images.Media.DATE_MODIFIED, Math.floorDiv(it.epochMillis, 1000))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, destination.config.exportDirectory.relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    legacyFile = reserveLegacyFile(destination)
                    put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
                    put(MediaStore.Images.Media.DISPLAY_NAME, legacyFile!!.name)
                }
            }
            return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create MediaStore entry")
        } catch (failure: Throwable) {
            legacyFile?.delete()
            throw failure
        }
    }

    private fun reserveLegacyFile(destination: ExportDestination): File {
        val relativePath = destination.config.exportDirectory.relativePath
        val root = when (relativePath.substringBefore('/')) {
            "DCIM" -> Environment.DIRECTORY_DCIM
            "Movies" -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_PICTURES
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(root), relativePath.substringAfter('/', ""))
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create export directory")
        val base = destination.fileName.substringBeforeLast('.')
        val extension = destination.fileName.substringAfterLast('.')
        var candidate = File(directory, destination.fileName)
        var suffix = 1
        while (!candidate.createNewFile()) candidate = File(directory, "${base}_${suffix++}.$extension")
        return candidate
    }

    private fun finish(uri: Uri, destination: ExportDestination): List<String> {
        val warnings = mutableListOf("Filesystem creation/change time (ctime) cannot be restored on Android/Linux.")
        destination.captureTime?.let { time ->
            if (time.offset == null) warnings += "Source timezone is unavailable; epoch time uses the device timezone."
            try {
                val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("No writable descriptor")
                descriptor.use {
                    val instant = time.offset?.let { offset -> time.localDateTime.toInstant(offset) }
                        ?: time.localDateTime.atZone(ZoneId.systemDefault()).toInstant()
                    Files.setLastModifiedTime(Paths.get("/proc/self/fd/${it.fd}"), FileTime.from(instant))
                }
            } catch (_: Exception) {
                warnings += "Storage provider does not allow restoring the file modification time."
            }
        } ?: run { warnings += "Source capture time is unavailable; original file timestamps cannot be restored." }

        if (destination.treeUri == null) {
            val values = ContentValues().apply {
                destination.captureTime?.let {
                    put(MediaStore.Images.Media.DATE_TAKEN, it.epochMillis)
                    put(MediaStore.Images.Media.DATE_MODIFIED, Math.floorDiv(it.epochMillis, 1000))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            if (values.size() > 0 && resolver.update(uri, values, null, null) == 0) {
                throw IOException("Failed to publish MediaStore export")
            }
        } else {
            warnings += "Custom document providers control gallery dates and may limit timestamp precision."
        }
        return warnings
    }
}
