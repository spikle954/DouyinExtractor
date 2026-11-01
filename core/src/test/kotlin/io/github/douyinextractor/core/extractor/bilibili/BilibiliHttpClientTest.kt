package io.github.douyinextractor.core.extractor.bilibili

import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class BilibiliHttpClientTest {
    private fun client() = BilibiliHttpClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), false)
    @Test fun `anonymous JSON request has Bili referer and never replays server cookie`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"code\":0}").addHeader("Set-Cookie", "SESSDATA=secret"))
            server.enqueue(MockResponse().setBody("{\"code\":0}"))
            val client = client()
            repeat(2) { assertEquals(200, client.fetch(server.url("/api").toString(), true).status) }
            repeat(2) {
                val request = server.takeRequest()
                assertEquals(BilibiliHttpClient.REFERER, request.getHeader("Referer"))
                assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `redirect body is not read and redirect is not followed by transport`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example/").setBody("not-json"))
            val result = client().fetch(server.url("/short").toString(), false)
            assertEquals(302, result.status); assertEquals("", result.body); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `chunked body bound rejects oversized JSON`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(BilibiliHttpClient.MAX_BYTES + 1), 8192))
            try { client().fetch(server.url("/api").toString(), true); fail("Oversized JSON accepted") } catch (_: IOException) { }
        }
    }
    @Test fun `production transport refuses credentials unknown APIs and arbitrary hosts before network`() = runBlocking {
        for (url in listOf("https://api.bilibili.com/x/passport/login", "https://user@api.bilibili.com/x/web-interface/view", "http://api.bilibili.com/x/web-interface/view", "https://evil.example/")) {
            try { BilibiliHttpClient().fetch(url, true); fail("Unsafe request") } catch (_: IllegalArgumentException) { }
        }
    }
}
