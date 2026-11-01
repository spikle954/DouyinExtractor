package io.github.douyinextractor.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object DownloadLocations {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun label(context: Context, target: String?): String = when (target) {
        null -> "首次下载时选择"
        DownloadRuntime.DEFAULT -> SaveLocations.DEFAULT_LABEL
        else -> runCatching {
            val tree = Uri.parse(target)
            val id = DocumentsContract.getTreeDocumentId(tree)
            if (tree.authority == EXTERNAL_STORAGE) SaveLocations.documentLabel(id) ?: "所选文件夹"
            else {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                context.contentResolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) "所选文件夹 / ${it.getString(0)}" else "所选文件夹"
                } ?: "所选文件夹"
            }
        }.getOrDefault("所选文件夹")
    }

    fun folderUri(target: String): String? = runCatching {
        if (target == DownloadRuntime.DEFAULT) documentUri("primary:${SaveLocations.DEFAULT_PATH}")
        else Uri.parse(target).let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)).toString() }
    }.getOrNull()

    fun legacyFolderUri(location: String): String? = SaveLocations.documentId(location)?.let(::documentUri)

    private fun documentUri(id: String): String = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, id).toString()
}
