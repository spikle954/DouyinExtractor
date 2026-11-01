package io.github.douyinextractor.download

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.douyinextractor.MainActivity
import io.github.douyinextractor.core.download.*
import java.io.File
import kotlinx.coroutines.*

/** Started only by the visible download button, never by a boot receiver or a background parse. */
class VideoDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var runtime: DownloadRuntime
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeout = false
    private var lastNotification = 0L

    override fun onCreate() {
        super.onCreate()
        runtime = DownloadRuntime.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "视频下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            runtime.cancel(intent.getStringExtra("token").orEmpty())
            if (worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker != null) { updateNotification(force = true); return START_NOT_STICKY }
        val request = runtime.request(intent?.getStringExtra("token"))
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(runtime.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: RuntimeException) {
            runtime.update(request.token) { it.copy(phase = DownloadPhase.FAILED, message = "系统未允许启动后台下载，请返回拾影重试") }
            runtime.finishCurrent(request.token)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runtime.runnerStarted()
        worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                DownloadQueueWorker.run(request,
                    next = { if (timeout) null else runtime.claimNext().also { updateNotification(force = true) } },
                    finish = runtime::finishCurrent,
                    interrupted = { item ->
                        if (runtime.state.value.token == item.token && runtime.state.value.active)
                            phase(item, DownloadPhase.INTERRUPTED, "后台下载已中断，请重新解析；其余任务可返回拾影继续")
                    }, onTask = { runtime.runningJob = it }, execute = ::downloadOne)
            } finally {
                withContext(NonCancellable) {
                    worker = null
                    runtime.runnerStopped()
                    ServiceCompat.stopForeground(this@VideoDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    updateNotification(force = true)
                    stopSelf()
                }
            }
        }
        worker?.start()
        return START_NOT_STICKY
    }

    private suspend fun downloadOne(request: DownloadRequest) {
        val file = File(runtime.stagingDirectory(), "${request.token}.part")
        val storage = VideoStorage(this)
        try {
            if (runtime.cancelRequested) throw CancellationException()
            synchronized(this) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "$packageName:video-download").apply {
                    setReferenceCounted(false); acquire(45 * 60 * 1000L)
                }
            }
            withTimeout(40 * 60 * 1000L) {
                if (!storage.cleanupPending()) throw DownloadException(DownloadProblem.STORAGE)
                storage.checkDestination(request.tree)
                phase(request, DownloadPhase.DOWNLOADING, "正在下载视频")
                if (runtime.cancelRequested) throw CancellationException()
                val size = VideoDownloader(request.platform).download(request.url, file) { progress ->
                    runtime.update(request.token) { it.copy(progress = progress) }; updateNotification()
                }
                phase(request, DownloadPhase.VERIFYING, "正在检查视频文件")
                val details = storage.verifyVideo(file)
                ensureActive()
                runtime.update(request.token) { it.copy(progress = DownloadProgress(0, size)) }
                phase(request, DownloadPhase.SAVING, "正在保存到手机")
                storage.save(file, request, { progress ->
                    runtime.update(request.token) { it.copy(progress = progress) }; updateNotification()
                }) { uri ->
                    val savedFile = storage.savedFileDescription(uri, request)
                    runtime.update(request.token) { it.copy(phase = DownloadPhase.COMPLETE,
                        progress = DownloadProgress(size, size), savedUri = uri, details = details,
                        fileName = savedFile.fileName, location = savedFile.location, folderUri = savedFile.folderUri,
                        savedAt = System.currentTimeMillis(), message = "视频已保存到手机") }
                }
            }
        } catch (_: TimeoutCancellationException) {
            if (runtime.state.value.phase != DownloadPhase.COMPLETE) phase(request, DownloadPhase.FAILED, "下载超时，请检查网络后重新解析")
        } catch (_: CancellationException) {
            if (runtime.state.value.phase != DownloadPhase.COMPLETE) {
                val userCancelled = runtime.cancelRequested && !timeout
                phase(request, if (userCancelled) DownloadPhase.CANCELLED else DownloadPhase.INTERRUPTED,
                    if (userCancelled) "当前下载已取消，其他排队任务会继续" else "系统已中断后台下载，请返回拾影重新解析")
            }
        } catch (error: DownloadException) {
            phase(request, DownloadPhase.FAILED, error.problem.userMessage + (error.httpCode?.let { "（HTTP $it）" } ?: ""))
        } catch (_: Exception) {
            phase(request, DownloadPhase.FAILED, "下载未完成，请检查空间或重新解析；其余任务会继续")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() = synchronized(this) {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun phase(request: DownloadRequest, phase: DownloadPhase, text: String) {
        runtime.update(request.token) { it.copy(phase = phase, message = text) }
        updateNotification(force = true)
    }

    private fun notification(record: DownloadRecord): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (record.active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(record.message.ifBlank { "准备下载视频" })
            .setContentText(if (record.active) "${DownloadLabels.bytes(record.progress.bytes)} · ${record.progress.percent?.let { "$it%" } ?: "总大小未知"}"
                else if (record.phase == DownloadPhase.COMPLETE) "保存位置：${SaveLocations.display(record.location)}" else record.fileName)
            .setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(record.active).setAutoCancel(!record.active)
            .setSubText("等待下载：${runtime.queued.value.count { !it.needsReparse }}")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (record.active) {
            builder.setProgress(100, record.progress.percent ?: 0, record.progress.percent == null)
            val cancel = PendingIntent.getService(this, 1,
                Intent(this, VideoDownloadService::class.java).setAction(CANCEL).putExtra("token", record.token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消当前", cancel)
        }
        if (record.phase == DownloadPhase.COMPLETE) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("${record.fileName}\n保存位置：${SaveLocations.display(record.location)}\n点击回到拾影，可播放视频或打开保存文件夹。"))
            builder.addAction(android.R.drawable.ic_menu_view, "查看已保存的视频", launch)
        }
        return builder.build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotification < 1000) return
        lastNotification = now
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(runtime.state.value))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeout = true
        worker?.cancel()
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "video_downloads"
        private const val NOTIFICATION = 40
        private const val CANCEL = "io.github.douyinextractor.CANCEL_DOWNLOAD"
    }
}
