package io.github.douyinextractor.download

import kotlinx.coroutines.*

/** Shared serial worker lifecycle, independent of Android so handoff and cancellation can be tested. */
object DownloadQueueWorker {
    suspend fun run(
        first: DownloadRequest,
        next: () -> DownloadRequest?,
        finish: (String) -> Unit,
        interrupted: (DownloadRequest) -> Unit,
        onTask: (Job?) -> Unit,
        execute: suspend (DownloadRequest) -> Unit,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = coroutineScope {
        var current: DownloadRequest? = first
        var task: Job? = null
        try {
            while (isActive) {
                val item = current ?: break
                task = launch(dispatcher, start = CoroutineStart.LAZY) { execute(item) }
                onTask(task)
                task.start()
                task.join()
                finish(item.token)
                current = null
                ensureActive()
                current = next()
            }
        } finally {
            withContext(NonCancellable) {
                task?.cancel()
                task?.join()
                current?.let { interrupted(it); finish(it.token) }
                onTask(null)
            }
        }
    }
}
