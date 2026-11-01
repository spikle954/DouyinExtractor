package io.github.douyinextractor.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import io.github.douyinextractor.core.download.*
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class DownloadRecord(
    val token: String = "", val videoId: String = "", val fileName: String = "",
    val phase: DownloadPhase = DownloadPhase.IDLE, val progress: DownloadProgress = DownloadProgress(),
    val message: String = "", val savedUri: String? = null, val location: String = "",
    val ready: Boolean = true, val details: SavedVideoDetails? = null,
    val platform: String = "Douyin", val page: Int = 1,
    val folderUri: String? = null, val savedAt: Long = 0,
    val quality: String = "",
    val title: String = "", val author: String = "",
) {
    val active: Boolean get() = phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.SAVING)
    val reparseUrl: String? get() = runCatching {
        when (platform) { "Bilibili" -> BilibiliUrls.canonical(videoId, page); SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU -> SocialUrls.canonical(platform, videoId); "Douyin" -> "https://www.douyin.com/video/$videoId"; else -> null }
    }.getOrNull()
}

data class DownloadRequest(val token: String, val videoId: String, val fileName: String, val url: String, val tree: String?, val platform: String = "Douyin")

/** Serial user-added tasks. URLs exist only in memory; the local journal contains no credentials. */
class DownloadRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val historyPreferences = app.getSharedPreferences("saved_videos", Context.MODE_PRIVATE)
    private val queuePreferences = app.getSharedPreferences("download_queue", Context.MODE_PRIVATE)
    private val queue = DownloadQueue()
    private val mutableQueue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val queued = mutableQueue.asStateFlow()
    private val mutableState = MutableStateFlow(DownloadRecord(ready = false))
    val state = mutableState.asStateFlow()
    private val mutableHistory = MutableStateFlow<List<SavedDownload>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val mutableDestination = MutableStateFlow(preferences.getString("destination", null))
    val destination = mutableDestination.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runnerAttached = false
    @Volatile var runningJob: Job? = null
    @Volatile var cancelRequested = false
        private set

    init {
        scope.launch {
            val phase = runCatching { DownloadPhase.valueOf(preferences.getString("phase", "IDLE")!!) }.getOrDefault(DownloadPhase.IDLE)
            var restored = DownloadRecord(
                token = preferences.getString("token", "").orEmpty(), videoId = preferences.getString("videoId", "").orEmpty(),
                fileName = preferences.getString("fileName", "").orEmpty(), phase = phase,
                progress = DownloadProgress(preferences.getLong("bytes", 0)), message = preferences.getString("message", "").orEmpty(),
                savedUri = preferences.getString("savedUri", null), location = preferences.getString("location", "").orEmpty(),
                platform = preferences.getString("platform", "Douyin").orEmpty(), page = preferences.getInt("page", 1),
                folderUri = preferences.getString("folderUri", null), savedAt = preferences.getLong("savedAt", 0),
                quality = preferences.getString("quality", "").orEmpty(),
                title = preferences.getString("title", "").orEmpty(), author = preferences.getString("author", "").orEmpty(),
                details = preferences.getInt("width", 0).takeIf { it > 0 }?.let {
                    SavedVideoDetails(it, preferences.getInt("height", 0), preferences.getLong("durationMs", 0))
                },
            )
            if (restored.folderUri == null) restored = restored.copy(folderUri = DownloadLocations.legacyFolderUri(restored.location))
            mutableHistory.value = SavedDownloadHistory.decode(historyPreferences.all.mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap())
            queue.restore(DownloadQueueCodec.decode(queuePreferences.all.mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap()), restored.token.takeIf { phase == DownloadPhase.COMPLETE })
            persistQueue()
            val storage = VideoStorage(app)
            val cleaned = storage.recoverPending(restored.savedUri.takeIf { phase == DownloadPhase.COMPLETE })
            stagingDirectory().listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]+\\.part")) }?.forEach { it.delete() }
            if (restored.active) restored = restored.copy(phase = DownloadPhase.INTERRUPTED, savedUri = null,
                message = "上次下载被系统中断，请重新解析后下载")
            if (!cleaned) restored = restored.copy(phase = DownloadPhase.FAILED, message = "上次保存的临时文件未能清理，请恢复文件夹权限后重试")
            publish(restored)
        }
    }

    fun setDestination(value: String) {
        preferences.edit().putString("destination", value).apply()
        mutableDestination.value = value
    }

    suspend fun checkSavedDuplicate(media: MediaInfo): SavedDuplicate? = withContext(Dispatchers.IO) {
        SavedDuplicateFinder.find(history.value, DownloadIdentity.from(media)) { raw ->
            try {
                app.contentResolver.openFileDescriptor(Uri.parse(raw), "r")?.use { SavedFileAccess.AVAILABLE } ?: SavedFileAccess.MISSING
            } catch (_: java.io.FileNotFoundException) { SavedFileAccess.MISSING }
            catch (_: Exception) { SavedFileAccess.UNAVAILABLE }
        }
    }

    suspend fun submit(media: MediaInfo, index: Int, allowSavedDuplicate: Boolean = false): DownloadSubmission {
        if (!allowSavedDuplicate) checkSavedDuplicate(media)?.let { return DownloadSubmission.Saved(it) }
        return enqueue(media, index)
    }

    @Synchronized
    private fun enqueue(media: MediaInfo, index: Int): DownloadSubmission {
        if (!state.value.ready) return DownloadSubmission.NOT_READY
        val format = media.formats.getOrNull(index) ?: return DownloadSubmission.NOT_READY
        val target = destination.value ?: return DownloadSubmission.NOT_READY
        val token = UUID.randomUUID().toString()
        val fileId = when (media.platform) { "Bilibili" -> "${media.id}_P${media.selectedPage}"; SocialUrls.KUAISHOU -> "KS_${media.id}"; SocialUrls.XIAOHONGSHU -> "XHS_${media.id}"; else -> media.id }
        val name = runCatching { DownloadNames.fileName(media.author, media.title, fileId) }.getOrNull() ?: return DownloadSubmission.NOT_READY
        val request = DownloadRequest(token, media.id, name, format.url, target.takeUnless { it == DEFAULT }, media.platform)
        val quality = format.qualityLabel ?: if (format.width != null && format.height != null) "${format.width} × ${format.height}" else "资源 ${index + 1}"
        val item = QueuedDownload(DownloadRecord(token, media.id, name, DownloadPhase.PREPARING, message = "等待下载",
            location = DownloadLocations.label(app, target), platform = media.platform, page = media.selectedPage,
            folderUri = DownloadLocations.folderUri(target), quality = quality,
            title = media.title.orEmpty().take(2000), author = media.author.orEmpty().take(200)), request)
        when (queue.add(item)) {
            QueueAddResult.ALREADY_QUEUED -> return DownloadSubmission.ALREADY_QUEUED
            QueueAddResult.FULL -> return DownloadSubmission.FULL
            QueueAddResult.ADDED -> Unit
        }
        persistQueue()
        if (!runnerAttached && !resumeQueue()) return DownloadSubmission.NOT_READY
        return if (state.value.token == token) DownloadSubmission.STARTED else DownloadSubmission.QUEUED
    }

    @Synchronized
    fun resumeQueue(): Boolean {
        if (!state.value.ready) return false
        if (runnerAttached) return true
        val next = claimNext() ?: return false
        try {
            ContextCompat.startForegroundService(app, Intent(app, VideoDownloadService::class.java).putExtra("token", next.token))
        } catch (_: RuntimeException) {
            publish(state.value.copy(phase = DownloadPhase.FAILED, message = "系统未允许启动下载，请保持拾影在前台后重试"))
            finishCurrent(next.token)
            return false
        }
        return true
    }

    @Synchronized fun request(token: String?): DownloadRequest? = queue.active()?.request?.takeIf { it.token == token }

    @Synchronized fun claimNext(): DownloadRequest? {
        val item = queue.claim() ?: return null
        if (state.value.token != item.record.token || !state.value.active) {
            cancelRequested = false
            publish(item.record.copy(message = "准备下载…"))
        }
        persistQueue()
        return item.request
    }

    @Synchronized fun runnerStarted() { runnerAttached = true }
    @Synchronized fun runnerStopped() { runnerAttached = false; runningJob = null }

    @Synchronized fun removeQueued(token: String) { queue.remove(token); persistQueue() }
    @Synchronized fun clearWaiting() { queue.clearWaiting(); persistQueue() }

    @Synchronized fun finishCurrent(token: String) {
        val failed = state.value.takeIf { it.token == token && it.phase in setOf(DownloadPhase.FAILED, DownloadPhase.INTERRUPTED) }
        if (queue.finish(token, failed)) { runningJob = null; cancelRequested = false; persistQueue() }
    }

    private fun persistQueue() {
        val editor = queuePreferences.edit().clear()
        DownloadQueueCodec.encode(queue.snapshot()).forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
        mutableQueue.value = queue.pending()
    }
    fun cancel(token: String = state.value.token) {
        if (token != state.value.token || !state.value.active) return
        cancelRequested = true
        if (state.value.phase != DownloadPhase.PREPARING) runningJob?.cancel()
    }

    @Synchronized
    fun update(token: String, transform: (DownloadRecord) -> DownloadRecord) {
        if (state.value.token == token) publish(transform(state.value))
    }

    @Synchronized
    private fun publish(record: DownloadRecord) {
        val previous = state.value
        // Persist transitions before exposing them. Progress is in-memory and throttled by the transport.
        if (!previous.ready || previous.phase != record.phase || previous.token != record.token) {
            preferences.edit().putString("token", record.token).putString("videoId", record.videoId)
                .putString("platform", record.platform).putInt("page", record.page)
                .putString("fileName", record.fileName).putString("phase", record.phase.name)
                .putString("message", record.message).putString("savedUri", record.savedUri)
                .putString("folderUri", record.folderUri).putLong("savedAt", record.savedAt)
                .putString("quality", record.quality)
                .putString("title", record.title).putString("author", record.author)
                .putString("location", record.location).putLong("bytes", record.progress.bytes)
                .putInt("width", record.details?.width ?: 0).putInt("height", record.details?.height ?: 0)
                .putLong("durationMs", record.details?.durationMs ?: 0).commit()
        }
        if (record.phase == DownloadPhase.COMPLETE && (!previous.ready || previous.phase != record.phase || previous.token != record.token)) {
            record.savedDownload()?.let { saved ->
                val items = SavedDownloadHistory.add(mutableHistory.value, saved)
                val editor = historyPreferences.edit().clear()
                SavedDownloadHistory.encode(items).forEach { (key, value) -> editor.putString(key, value) }
                editor.commit()
                mutableHistory.value = items
            }
        }
        mutableState.value = record
    }

    fun stagingDirectory(): File = File(app.cacheDir, "video-downloads").apply { mkdirs() }

    companion object {
        const val DEFAULT = "default"
        @Volatile private var instance: DownloadRuntime? = null
        fun get(context: Context): DownloadRuntime = instance ?: synchronized(this) {
            instance ?: DownloadRuntime(context).also { instance = it }
        }
    }
}
