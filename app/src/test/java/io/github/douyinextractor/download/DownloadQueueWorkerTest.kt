package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadQueueWorkerTest {
    private fun item(number: Int) = QueuedDownload(
        DownloadRecord(token = number.toString(), videoId = "769014582216875056$number"),
        DownloadRequest(number.toString(), "769014582216875056$number", "$number.mp4", "https://example.invalid/$number.mp4", null))

    @Test fun `production worker drains FIFO without overlapping and picks up a newly added item`() = runBlocking {
        val queue = DownloadQueue(); queue.add(item(1)); queue.add(item(2))
        val order = mutableListOf<String>(); var running = 0; var peak = 0
        DownloadQueueWorker.run(queue.claim()!!.request!!, { queue.claim()?.request }, { queue.finish(it) }, {}, {}, { request ->
            running++; peak = maxOf(peak, running)
            order += request.token
            if (request.token == "1") queue.add(item(3))
            delay(5)
            running--
        }, Dispatchers.Unconfined)
        assertEquals(listOf("1", "2", "3"), order)
        assertEquals(1, peak)
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun `failed transfer is retained for reparse while the following task completes`() = runBlocking {
        val queue = DownloadQueue(); queue.add(item(1)); queue.add(item(2))
        val failed = mutableSetOf<String>(); val completed = mutableListOf<String>()
        DownloadQueueWorker.run(queue.claim()!!.request!!, { queue.claim()?.request }, { token ->
            queue.finish(token, queue.active()!!.record.copy(phase = DownloadPhase.FAILED).takeIf { token in failed })
        }, {}, {}, { request ->
            if (request.token == "1") failed += request.token else completed += request.token
        }, Dispatchers.Unconfined)
        assertEquals(listOf("2"), completed)
        assertEquals("1", queue.pending().single().record.token)
        assertTrue(queue.pending().single().needsReparse)
    }

    @Test fun `canceling only the current transfer keeps the serial worker and next task alive`() = runBlocking {
        val queue = DownloadQueue(); queue.add(item(1)); queue.add(item(2))
        var activeTask: Job? = null
        val completed = mutableListOf<String>()
        DownloadQueueWorker.run(queue.claim()!!.request!!, { queue.claim()?.request }, { queue.finish(it) }, {}, { activeTask = it }, { request ->
            if (request.token == "1") { activeTask!!.cancel(); yield() }
            else completed += request.token
        }, Dispatchers.Unconfined)
        assertEquals(listOf("2"), completed)
        assertNull(activeTask)
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun `system cancellation waits for cleanup and never claims the next task`() = runBlocking {
        val queue = DownloadQueue(); queue.add(item(1)); queue.add(item(2))
        val started = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        var finished = 0
        val runner = launch {
            DownloadQueueWorker.run(queue.claim()!!.request!!, { queue.claim()?.request }, {
                assertTrue(cleanup.isCompleted)
                finished++
                queue.finish(it, queue.active()!!.record.copy(phase = DownloadPhase.INTERRUPTED))
            }, {}, {}, {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { delay(5); cleanup.complete(Unit) } }
            }, Dispatchers.Unconfined)
        }
        withTimeout(2000) { started.await(); runner.cancelAndJoin() }
        assertTrue(cleanup.isCompleted)
        assertEquals(1, finished)
        assertNull(queue.active())
        assertEquals(listOf("2", "1"), queue.pending().map { it.record.token })
        assertFalse(queue.pending().first().needsReparse)
        assertTrue(queue.pending().last().needsReparse)
    }
}
