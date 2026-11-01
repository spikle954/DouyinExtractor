package io.github.douyinextractor.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class SavedVideoActions(private val context: Context, private val browse: (Intent) -> Unit) {
    fun play(raw: String) = open(raw, false)
    fun share(raw: String) = open(raw, true)

    private fun open(raw: String, share: Boolean) {
        try {
            val uri = Uri.parse(raw)
            require(uri.scheme == "content")
            val intent = if (share) Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("视频", uri)
            context.startActivity(if (share) Intent.createChooser(intent, "分享视频") else intent)
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开视频。文件可能已移动或删除，请打开保存文件夹，或在文件管理器中按文件名搜索。", Toast.LENGTH_LONG).show()
        }
    }

    fun folder(item: SavedDownload) {
        val folder = item.folderUri ?: DownloadLocations.legacyFolderUri(item.location)
        val uri = folder?.let(Uri::parse)?.takeIf { it.scheme == "content" }
        if (uri != null) {
            // Some file managers support viewing a document directory. Do not require one vendor's app.
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            if (uri.pathSegments.contains("tree")) view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { context.startActivity(view); return } catch (_: Exception) { /* Use the system file picker below. */ }
        }
        try {
            browse(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri ?: Uri.parse(item.uri)))
        } catch (_: Exception) {
            Toast.makeText(context, "手机未能打开文件管理器。保存位置：${SaveLocations.display(item.location)}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun rememberSavedVideoActions(): SavedVideoActions {
    val context = LocalContext.current
    val browser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let {
            SavedVideoActions(context) {}.play(it.toString())
        }
    }
    return remember(context, browser) { SavedVideoActions(context) { browser.launch(it) } }
}
