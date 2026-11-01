package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadQueueTest {
    private fun item(n: Int, page: Int = 1, bili: Boolean = false): QueuedDownload {
        val id = if (bili) "BV1GJ411x7h7" else "7690145822168${n.toString().padStart(6, '0')}"
        val token = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
        return QueuedDownload(DownloadRecord(token = token, videoId = id, platform = if (bili) "Bilibili" else "Douyin",
            page = page, fileName = "作者_标题_${id}.mp4", location = "手机存储 / Movies / 拾影", quality = "1080P"),
            DownloadRequest(token, id, "video.mp4", "https://example.invalid/video.mp4?token=SECRET", null, if (bili) "Bilibili" else "Douyin"))
    }

    @Test fun `queue preserves FIFO and one active slot while new shares are added`() {
        val queue = DownloadQueue()
        val a = item(1); val b = item(2); val c = item(3)
        assertEquals(QueueAddResult.ADDED, queue.add(a))
        assertEquals(a, queue.claim())
        queue.add(b); queue.add(c)
        assertEquals(a, queue.claim())
        assertEquals(listOf(b, c), queue.pending())
        assertFalse(queue.finish("stale-token"))
        assertEquals(a, queue.active())
        assertTrue(queue.finish(a.record.token))
        assertEquals(b, queue.claim())
        queue.finish(b.record.token)
        assertEquals(c, queue.claim())
        queue.finish(c.record.token)
        assertNull(queue.claim())
    }

    @Test fun `canceling waiting or active items leaves other tasks intact`() {
        val queue = DownloadQueue()
        val a = item(1); val b = item(2); val c = item(3)
        listOf(a, b, c).forEach(queue::add)
        queue.claim()
        assertFalse(queue.remove(a.record.token))
        assertTrue(queue.remove(b.record.token))
        assertEquals(listOf(c), queue.pending())
        queue.finish(a.record.token)
        assertEquals(c, queue.claim())
        queue.clearWaiting()
        assertEquals(c, queue.active())
    }

    @Test fun `failed item remains actionable and never blocks following ready tasks`() {
        val queue = DownloadQueue()
        val a = item(1); val b = item(2)
        queue.add(a); queue.add(b); queue.claim()
        queue.finish(a.record.token, a.record.copy(phase = DownloadPhase.FAILED, message = "地址过期"))
        assertEquals(b, queue.claim())
        assertTrue(queue.pending().single().needsReparse)
        val fresh = a.copy(request = a.request!!.copy(url = "https://example.invalid/new.mp4"))
        assertEquals(QueueAddResult.ADDED, queue.add(fresh))
        queue.finish(b.record.token)
        assertEquals(fresh, queue.claim())
    }

    @Test fun `same video is not queued twice but Bili parts are distinct tasks`() {
        val queue = DownloadQueue()
        val first = item(1, page = 1, bili = true)
        queue.add(first); queue.claim()
        assertEquals(QueueAddResult.ALREADY_QUEUED, queue.add(first.copy(record = first.record.copy(token = "other"))))
        assertEquals(QueueAddResult.ADDED, queue.add(item(2, page = 2, bili = true)))
        assertEquals(QueueAddResult.ALREADY_QUEUED, queue.add(item(3, page = 2, bili = true)))
        assertEquals(2, queue.snapshot().size)
    }

    @Test fun `recovery metadata never stores signed URLs and completed item is not restored`() {
        val queue = DownloadQueue()
        val a = item(1); val b = item(2, page = 2, bili = true)
        queue.add(a); queue.add(b); queue.claim()
        val encoded = DownloadQueueCodec.encode(queue.snapshot())
        assertFalse(encoded.values.any { it.contains("SECRET") || it.contains("https://") })
        val recovered = DownloadQueueCodec.decode(encoded)
        assertEquals(2, recovered.size)
        assertTrue(recovered.all { it.needsReparse })
        assertEquals(b.identity, recovered.last().identity)
        queue.restore(recovered, a.record.token)
        assertNull(queue.claim())
        assertEquals(listOf(b.identity), queue.pending().map { it.identity })
        assertEquals(QueueAddResult.ADDED, queue.add(b))
        assertEquals(b, queue.claim())
    }

    @Test fun `concurrent queue additions and claims respect duplicate and capacity limits`() {
        val queue = DownloadQueue()
        val pool = Executors.newFixedThreadPool(8)
        val tasks = (1..60).map { n -> pool.submit<QueueAddResult> { queue.add(item(n)) } }
        val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
        assertEquals(DownloadQueue.LIMIT, results.count { it == QueueAddResult.ADDED })
        assertEquals(DownloadQueue.LIMIT, queue.snapshot().size)
        val claimed = (1..8).map { pool.submit<QueuedDownload?> { queue.claim() } }.map { it.get(5, TimeUnit.SECONDS) }
        assertEquals(1, claimed.distinct().size)
        assertEquals(DownloadQueue.LIMIT - 1, queue.pending().size)
        pool.shutdownNow()
    }

    @Test fun `queued request freezes resource and destination chosen at submission`() {
        val queue = DownloadQueue()
        val a = item(1)
        val b = item(2).let { it.copy(request = it.request!!.copy(tree = "content://provider/tree/second"),
            record = it.record.copy(location = "所选文件夹 / second")) }
        queue.add(a); queue.add(b); queue.claim(); queue.finish(a.record.token)
        val claimed = queue.claim()!!
        assertEquals(b.request!!.url, claimed.request!!.url)
        assertEquals("content://provider/tree/second", claimed.request!!.tree)
        assertEquals(b.record.location, claimed.record.location)
    }

    @Test fun `malformed stored queue does not create runnable network tasks`() {
        assertTrue(DownloadQueueCodec.decode(mapOf("count" to "2147483647")).isEmpty())
        val bad = DownloadQueueCodec.encode(listOf(item(1))).toMutableMap()
        bad["0.id"] = "../../outside"; assertTrue(DownloadQueueCodec.decode(bad).isEmpty())
        bad["0.id"] = "7690145822168750565"; bad["0.platform"] = "Other"; assertTrue(DownloadQueueCodec.decode(bad).isEmpty())
    }
}
