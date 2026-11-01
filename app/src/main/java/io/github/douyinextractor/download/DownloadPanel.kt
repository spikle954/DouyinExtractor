package io.github.douyinextractor.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.douyinextractor.core.download.*
import io.github.douyinextractor.core.model.MediaInfo
import kotlinx.coroutines.launch

@Composable
fun DownloadPanel(media: MediaInfo, selectedFormat: Int, onStarted: (DownloadIdentity) -> Unit,
    content: @Composable (folder: @Composable () -> Unit, download: @Composable () -> Unit) -> Unit = { folder, download ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            folder(); download()
            Text("支持后台排队，可继续解析其他视频。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
    val context = LocalContext.current
    val runtime = remember { DownloadRuntime.get(context) }
    val record by runtime.state.collectAsStateWithLifecycle()
    val destination by runtime.destination.collectAsStateWithLifecycle()
    val destinationLabel = remember(destination) { DownloadLocations.label(context, destination) }
    val actions = rememberSavedVideoActions()
    val scope = rememberCoroutineScope()
    var chooseDestination by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<MediaInfo, Int>?>(null) }
    var duplicate by remember { mutableStateOf<SavedDuplicate?>(null) }
    var allowSavedDuplicate by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val permissions = remember { context.getSharedPreferences("download_permissions", Context.MODE_PRIVATE) }
    fun begin() {
        val candidate = pending ?: return
        val allow = allowSavedDuplicate
        scope.launch {
            checking = true
            try {
                when (val result = runtime.submit(candidate.first, candidate.second, allow)) {
                    is DownloadSubmission.Saved -> duplicate = result.duplicate
                    else -> {
                        if (result == DownloadSubmission.STARTED) onStarted(DownloadIdentity.from(candidate.first))
                        pending = null
                        allowSavedDuplicate = false
                        val text = when (result) {
                            DownloadSubmission.STARTED -> "已开始下载"
                            DownloadSubmission.QUEUED -> "已加入下载队列"
                            DownloadSubmission.ALREADY_QUEUED -> "这个视频已在下载或队列中，请查看下载队列"
                            DownloadSubmission.FULL -> "队列已满，请等待完成或移除部分任务"
                            else -> "暂时无法开始，请查看队列或重新选择保存位置"
                        }
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    }
                }
            } finally { checking = false }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }
    fun notifyThenBegin() {
        if (Build.VERSION.SDK_INT >= 33 && !permissions.getBoolean("asked", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.edit().putBoolean("asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else begin()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                runtime.setDestination(uri.toString())
                if (pending != null) notifyThenBegin()
            } catch (_: Exception) {
                pending = null
                Toast.makeText(context, "无法使用这个文件夹，请重新选择", Toast.LENGTH_LONG).show()
            }
        } else pending = null
    }
    fun pickFolder() {
        chooseDestination = false
        folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
    }
    fun chooseOrBegin() {
        if (destination == null) chooseDestination = true else notifyThenBegin()
    }
    // Keep one set of submission state and callbacks when the screen positions these controls separately.
    content({
        Surface(onClick = { pending = null; chooseDestination = true }, enabled = record.ready && !checking,
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("保存位置 · 点按更改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destinationLabel, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }, {
        Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = record.ready && !checking,
                    onClick = {
                        pending = media to selectedFormat
                        allowSavedDuplicate = false
                        scope.launch {
                            checking = true
                            try {
                                duplicate = runtime.checkSavedDuplicate(media)
                                if (duplicate == null) chooseOrBegin()
                            } finally { checking = false }
                        }
                    }) { Text(if (checking) "正在检查下载记录…" else if (record.active) "加入下载队列" else "下载视频") }
    })
    if (chooseDestination) AlertDialog(onDismissRequest = { chooseDestination = false; pending = null },
        title = { Text("选择保存位置") },
        text = { Text(if (Build.VERSION.SDK_INT >= 29) "推荐保存到手机存储中的 Movies/拾影，可在文件管理器和相册中查找。也可选择其他本机文件夹；拾影会记住你的选择。"
            else "请选择本机文件夹。拾影会记住你的选择，无需访问整个存储空间。") },
        confirmButton = {
            if (Build.VERSION.SDK_INT >= 29) TextButton(onClick = {
                runtime.setDestination(DownloadRuntime.DEFAULT); chooseDestination = false
                if (pending != null) notifyThenBegin()
            }) { Text("保存到 Movies/拾影") }
            else TextButton(onClick = { pickFolder() }) { Text("选择文件夹") }
        },
        dismissButton = {
            if (Build.VERSION.SDK_INT >= 29) TextButton(onClick = { pickFolder() }) { Text("选择其他文件夹") }
            else TextButton(onClick = { chooseDestination = false; pending = null }) { Text("取消") }
        })
    duplicate?.let { saved ->
        AlertDialog(onDismissRequest = { duplicate = null; pending = null; allowSavedDuplicate = false },
            title = { Text(if (saved.access == SavedFileAccess.AVAILABLE) "这个视频已经下载过" else "这个视频有下载记录") },
            text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(saved.item.fileName)
                Text("保存位置：${SaveLocations.display(saved.item.location)}")
                if (saved.item.quality.isNotBlank()) Text("此前资源：${saved.item.quality}")
                Text(if (saved.access == SavedFileAccess.AVAILABLE) "可以直接播放已有文件。若想换画质或另存一份，也可以再次下载。"
                    else "暂时无法确认旧文件能否访问，可能是目录授权已变更。可以再次下载，或到原文件夹查找。")
            } },
            confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (saved.access == SavedFileAccess.AVAILABLE) Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    duplicate = null; pending = null; allowSavedDuplicate = false; actions.play(saved.item.uri)
                }) { Text("播放已有视频") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { duplicate = null; allowSavedDuplicate = true; chooseOrBegin() }) { Text("仍然下载") }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { duplicate = null; pending = null; allowSavedDuplicate = false }) { Text("取消") }
            } })
    }
}
