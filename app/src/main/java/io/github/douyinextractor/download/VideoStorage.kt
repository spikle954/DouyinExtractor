package io.github.douyinextractor.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.douyinextractor.core.download.*
import java.io.File
import kotlinx.coroutines.*

data class SavedVideoDetails(val width: Int, val height: Int, val durationMs: Long)
data class SavedFileDescription(val fileName: String, val location: String, val folderUri: String?)

/** Only URIs this app has just created enter the cleanup journal. Never updates an existing user file. */
class VideoStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private val journal = context.getSharedPreferences("download_pending", Context.MODE_PRIVATE)

    fun checkDestination(tree: String?) {
        if (tree == null) {
            if (Build.VERSION.SDK_INT < 29) throw DownloadException(DownloadProblem.STORAGE)
            return
        }
        if (resolver.persistedUriPermissions.none { it.uri.toString() == tree && it.isReadPermission && it.isWritePermission })
            throw DownloadException(DownloadProblem.STORAGE)
    }

    fun recoverPending(completedUri: String?): Boolean {
        if (completedUri != null && completedUri == journal.getString("uri", null)) {
            finishJournal()
            return true
        }
        return cleanupPending(preserveCompleteCopy = true)
    }

    fun verifyVideo(file: File): SavedVideoDetails {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" ||
                width <= 0 || height <= 0 || duration <= 0)
                throw DownloadException(DownloadProblem.INVALID_VIDEO)
            return SavedVideoDetails(width, height, duration)
        } catch (error: DownloadException) { throw error }
        catch (_: Exception) { throw DownloadException(DownloadProblem.INVALID_VIDEO) }
        finally { metadata.release() }
    }

    fun cleanupPending(preserveCompleteCopy: Boolean = false): Boolean {
        val raw = journal.getString("uri", null) ?: return true
        return try {
            val uri = Uri.parse(raw)
            if (journal.getBoolean("tree", false)) {
                val expected = journal.getLong("expectedBytes", -1)
                val fullCopy = preserveCompleteCopy && expected > 0 && resolver.openFileDescriptor(uri, "r")?.use { it.statSize == expected } == true
                if (fullCopy) { finishJournal(); return true }
                if (!DocumentsContract.deleteDocument(resolver, uri)) return false
            }
            else {
                // A crash just after publishing must never delete a now-public, completed video.
                if (Build.VERSION.SDK_INT >= 29) resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                    if (it.moveToFirst() && it.getInt(0) == 0) { finishJournal(); return true }
                }
                resolver.delete(uri, null, null)
            }
            journal.edit().clear().commit()
            true
        } catch (_: java.io.FileNotFoundException) { journal.edit().clear().commit(); true }
        catch (_: Exception) { false }
    }

    suspend fun save(file: File, request: DownloadRequest, progress: (DownloadProgress) -> Unit, onSaved: (String) -> Unit): String {
        if (!cleanupPending()) throw DownloadException(DownloadProblem.STORAGE)
        var created: Uri? = null
        val target = object : VideoFileTarget {
            override fun create() {
                created = if (request.tree == null) {
                    if (Build.VERSION.SDK_INT < 29) throw DownloadException(DownloadProblem.STORAGE)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, SaveLocations.DEFAULT_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                } else {
                    val tree = Uri.parse(request.tree)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    DocumentsContract.createDocument(resolver, parent, "video/mp4", request.fileName)
                } ?: throw DownloadException(DownloadProblem.STORAGE)
                if (!journal.edit().putString("uri", created.toString()).putBoolean("tree", request.tree != null)
                        .putLong("expectedBytes", file.length()).commit())
                    throw DownloadException(DownloadProblem.STORAGE)
            }
            override fun open() = resolver.openOutputStream(created!!, "w") ?: throw DownloadException(DownloadProblem.STORAGE)
            override fun checkSize(expected: Long) {
                resolver.openFileDescriptor(created!!, "r")?.use { descriptor ->
                    if (descriptor.statSize >= 0 && descriptor.statSize != expected) throw DownloadException(DownloadProblem.STORAGE)
                } ?: throw DownloadException(DownloadProblem.STORAGE)
            }
            override fun commit(): String {
                if (request.tree == null && Build.VERSION.SDK_INT >= 29 &&
                    resolver.update(created!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                    throw DownloadException(DownloadProblem.STORAGE)
                return created.toString()
            }
            override fun rollback() {
                val uri = created ?: return
                if (journal.getString("uri", null) == uri.toString()) cleanupPending()
                else {
                    if (request.tree != null) DocumentsContract.deleteDocument(resolver, uri)
                    else resolver.delete(uri, null, null)
                }
            }
        }
        return VideoFilePublisher().publish(file, target, progress) { uri -> onSaved(uri); finishJournal() }
    }
    fun savedFileDescription(raw: String, request: DownloadRequest): SavedFileDescription {
        val target = request.tree ?: DownloadRuntime.DEFAULT
        val fallback = SavedFileDescription(request.fileName, DownloadLocations.label(context, target), DownloadLocations.folderUri(target))
        // Providers may rename colliding files or adjust the requested relative path. Display their final result.
        return runCatching {
            val columns = if (request.tree == null && Build.VERSION.SDK_INT >= 29)
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
                else arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            resolver.query(Uri.parse(raw), columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                val name = cursor.getString(0)?.takeIf(String::isNotBlank) ?: fallback.fileName
                val path = if (columns.size == 2) cursor.getString(1)?.trim('/')?.takeIf(String::isNotBlank) else null
                fallback.copy(fileName = name,
                    location = path?.let { SaveLocations.documentLabel("primary:$it") } ?: fallback.location,
                    folderUri = path?.let { DownloadLocations.legacyFolderUri("primary:$it") } ?: fallback.folderUri)
            } ?: fallback
        }.getOrDefault(fallback)
    }
    fun finishJournal() { journal.edit().clear().commit() }
}
