package io.github.douyinextractor.core.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoDownloaderTest {
    @Test fun `Bili transport uses site referer and anonymous requests across redirects`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.mp4").setHeader("Set-Cookie", "SESSDATA=secret"))
            server.enqueue(mp4())
            VideoDownloader(OkHttpClient(), { it.startsWith(server.url("/").toString()) },
                referer = "https://www.bilibili.com/", userAgent = "Bili test").download(server.url("/start.mp4").toString(), temporary.newFile()) {}
            repeat(2) { val request = server.takeRequest(); assertEquals("https://www.bilibili.com/", request.getHeader("Referer")); assertNull(request.getHeader("Cookie")) }
        }
    }
    @Test fun `Bili download boundary excludes Douyin and separated media while Douyin excludes Bili`() = runBlocking {
        expect(DownloadProblem.UNSAFE_URL) { VideoDownloader("Bilibili").download("https://www.douyin.com/video.mp4", temporary.newFile()) {} }
        expect(DownloadProblem.UNSAFE_URL) { VideoDownloader("Bilibili").download("https://upos.bilivideo.com/video.m4s", temporary.newFile()) {} }
        expect(DownloadProblem.UNSAFE_URL) { VideoDownloader().download("https://upos.bilivideo.com/video.mp4", temporary.newFile()) {} }
    }
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, data: ByteArray): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).use { it.writeInt(data.size + 8); it.writeBytes(type); it.write(data) }
    }.toByteArray()
    // Synthetic container for transport tests only; Android video track validation remains separate.
    private fun fixture(payload: Int = 128): ByteArray = box("ftyp", "isom0000".toByteArray()) +
        box("mdat", ByteArray(payload) { 12 }) + box("moov", box("free", byteArrayOf(1)))

    private fun client(server: MockWebServer, limit: Long = VideoDownloader.MAX_BYTES) =
        VideoDownloader(OkHttpClient(), { it.startsWith(server.url("/").toString()) }, limit)
    private fun mp4(bytes: ByteArray = fixture()) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
    private suspend fun expect(problem: DownloadProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected $problem") } catch (error: DownloadException) { assertEquals(problem, error.problem) }
    }

    @Test fun `public downloader rejects arbitrary and insecure inputs before requesting`() = runBlocking {
        for (url in listOf("http://www.douyin.com/a.mp4", "https://127.0.0.1/a.mp4", "https://douyin.com.evil.example/a.mp4", "https://user@www.douyin.com/a.mp4"))
            expect(DownloadProblem.UNSAFE_URL) { VideoDownloader().download(url, temporary.newFile()) {} }
    }

    @Test fun `follows declared redirects streams exact bytes and sends no credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/media?token=a%2Bb&x=1").setHeader("Set-Cookie", "secret=value"))
            server.enqueue(mp4())
            val file = temporary.newFile()
            val progress = mutableListOf<DownloadProgress>()
            val size = client(server).download(server.url("/play").toString(), file, progress::add)
            assertArrayEquals(fixture(), file.readBytes())
            assertEquals(size, file.length())
            assertEquals(100, progress.last().percent)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/media?token=a%2Bb&x=1", request.path)
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Range")); assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun `chunked response reports unknown size until fully received`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setChunkedBody(Buffer().write(fixture()), 11))
            val reports = mutableListOf<DownloadProgress>()
            client(server).download(server.url("/").toString(), temporary.newFile(), reports::add)
            assertNull(reports.first().total)
            assertEquals(fixture().size.toLong(), reports.last().total)
        }
    }

    @Test fun `html json hls and empty bodies never become saved videos`() = runBlocking {
        MockWebServer().use { server ->
            for (type in listOf("text/html", "application/json", "application/vnd.apple.mpegurl", "audio/mp4")) {
                server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("error"))
                val file = temporary.newFile()
                expect(DownloadProblem.NOT_VIDEO) { client(server).download(server.url("/").toString(), file) {} }
                assertFalse(file.exists())
            }
            for (body in listOf(byteArrayOf(), "<html>denied</html>".toByteArray(), "{}".toByteArray())) {
                server.enqueue(mp4(body))
                expect(DownloadProblem.NOT_VIDEO) { client(server).download(server.url("/").toString(), temporary.newFile()) {} }
            }
        }
    }

    @Test fun `expired links and service errors are classified without disclosing urls`() = runBlocking {
        MockWebServer().use { server ->
            for (code in listOf(401, 403, 404, 410, 429, 500, 206)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private details"))
                try { client(server).download(server.url("/secret?token=secret").toString(), temporary.newFile()) {}; fail() }
                catch (error: DownloadException) {
                    assertEquals(code, error.httpCode)
                    assertEquals(if (code in listOf(401, 403, 404, 410)) DownloadProblem.EXPIRED else DownloadProblem.HTTP, error.problem)
                    assertFalse(error.message.orEmpty().contains("secret"))
                }
            }
        }
    }

    @Test fun `unapproved redirect is never requested`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { forbidden ->
                forbidden.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", forbidden.url("/secret").toString()))
                expect(DownloadProblem.UNSAFE_URL) { client(server).download(server.url("/").toString(), temporary.newFile()) {} }
                assertEquals(0, forbidden.requestCount)
            }
        }
    }

    @Test fun `redirect loop and excessive redirects stop`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/"))
            expect(DownloadProblem.UNSAFE_URL) { client(server).download(server.url("/").toString(), temporary.newFile()) {} }
            assertEquals(1, server.requestCount)
            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/${it + 1}")) }
            expect(DownloadProblem.UNSAFE_URL) { client(server).download(server.url("/0").toString(), temporary.newFile()) {} }
            assertEquals(7, server.requestCount)
        }
    }

    @Test fun `declared and streaming size limits both clean the partial file`() = runBlocking {
        MockWebServer().use { server ->
            for (response in listOf(mp4(), MockResponse().setChunkedBody(Buffer().write(fixture()), 15))) {
                server.enqueue(response)
                val file = temporary.newFile()
                expect(DownloadProblem.TOO_LARGE) { client(server, 32).download(server.url("/").toString(), file) {} }
                assertFalse(file.exists())
            }
        }
    }

    @Test fun `truncated transfer fails and deletes partial bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(mp4(fixture(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val file = temporary.newFile()
            expect(DownloadProblem.NETWORK) { client(server).download(server.url("/").toString(), file) {} }
            assertFalse(file.exists())
        }
    }

    @Test fun `cancelling a stalled stream stops promptly and removes partial file`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(mp4(fixture(256_000)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val file = temporary.newFile()
            val began = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) { client(server).download(server.url("/").toString(), file) { if (it.bytes > 0) began.complete(Unit) } }
            withTimeout(4000) { began.await() }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertFalse(file.exists())
        }
    }

    @Test fun `mp4 box truncation and missing index are rejected`() {
        val bytes = fixture()
        for (data in listOf(bytes.copyOf(bytes.size - 1), box("ftyp", "isom0000".toByteArray()) + box("mdat", byteArrayOf(1)),
            box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf(1)),
            byteArrayOf(0, 0, 0, 1) + "ftyp".toByteArray() + ByteArray(8) { -1 })) {
            val file = temporary.newFile().apply { writeBytes(data) }
            try { Mp4Inspector.verify(file); fail() } catch (error: DownloadException) { assertEquals(DownloadProblem.NOT_VIDEO, error.problem) }
        }
    }

    @Test fun `safe filenames preserve unicode id and extension without escaping folder`() {
        val filename = DownloadNames.fileName("CON", "../坏:文件\\名\n?*\u202E标题".repeat(100), "7691299102264413480")
        assertTrue(filename.startsWith("_CON_")); assertTrue(filename.endsWith("_7691299102264413480.mp4"))
        assertTrue(filename.toByteArray().size < 220)
        assertFalse(Regex("[<>:\"/\\\\|?*\\p{Cc}\\p{Cf}]").containsMatchIn(filename))
        assertTrue(DownloadNames.fileName(null, null, "1").endsWith("_1.mp4"))
    }

    @Test fun `progress avoids invented percentages and estimates`() {
        assertNull(DownloadProgress(100).percent)
        assertNull(DownloadProgress(100, 1000).remainingSeconds)
        assertEquals(10, DownloadProgress(100, 1000, 100).percent)
        assertEquals(9L, DownloadProgress(100, 1000, 100).remainingSeconds)
        assertEquals(100, DownloadProgress(2000, 1000).percent)
    }
}
