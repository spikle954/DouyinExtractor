package io.github.douyinextractor.core.download

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.*

/** A newly created destination, never an existing file selected for overwrite. */
interface VideoFileTarget {
    fun create()
    fun open(): OutputStream
    fun checkSize(expected: Long)
    fun commit(): String
    fun rollback()
}

class VideoFilePublisher {
    suspend fun publish(file: File, target: VideoFileTarget, progress: (DownloadProgress) -> Unit,
        onSaved: (String) -> Unit): String = withContext(Dispatchers.IO) {
        var committed = false
        try {
            ensureActive()
            Mp4Inspector.verify(file)
            target.create()
            val total = file.length()
            var copied = 0L
            var last = 0L
            target.open().use { output ->
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.nanoTime()
                        if (now - last >= 250_000_000) { progress(DownloadProgress(copied, total)); last = now }
                    }
                }
                output.flush()
            }
            if (copied != total) throw DownloadException(DownloadProblem.STORAGE)
            target.checkSize(total)
            ensureActive()
            withContext(NonCancellable) {
                val uri = target.commit()
                committed = true
                // Commit and record completion form one non-cancellable stage. A late cancel is completion.
                onSaved(uri)
                uri
            }
        } catch (error: CancellationException) { throw error }
        catch (error: DownloadException) { throw error }
        catch (_: Exception) { throw DownloadException(DownloadProblem.STORAGE) }
        finally {
            if (!committed) withContext(NonCancellable) { runCatching { target.rollback() } }
        }
    }
}
