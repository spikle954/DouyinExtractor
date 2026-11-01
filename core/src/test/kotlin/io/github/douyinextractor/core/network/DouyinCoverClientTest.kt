package io.github.douyinextractor.core.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DouyinCoverClientTest {
    @Test fun `cover body accepted only for supported image types`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/webp").setBody("fixture-bytes"))
            assertEquals("fixture-bytes", DouyinCoverClient(OkHttpClient()).fetch(server.url("/cover").toString()).decodeToString())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("not an image"))
            try { DouyinCoverClient(OkHttpClient()).fetch(server.url("/cover").toString()); fail() } catch (_: IOException) { }
        }
    }

    @Test fun `cover redirects do not leak request to another host`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.example/"))
            try { DouyinCoverClient(OkHttpClient()).fetch(server.url("/cover").toString()); fail() } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `chunked cover body respects actual byte cap`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setChunkedBody(Buffer().write(ByteArray(DouyinCoverClient.MAX_BYTES + 1)), 8192))
            try { DouyinCoverClient(OkHttpClient()).fetch(server.url("/cover").toString()); fail() } catch (_: IOException) { }
        }
    }

    @Test fun `public cover client rejects arbitrary hosts before connection`() = runBlocking {
        try { DouyinCoverClient().fetch("https://example.com/image.jpg"); fail() } catch (_: IllegalArgumentException) { }
    }
}
