package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.model.ExtractError
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DouyinPageClientTest {
    @Test fun `manual redirect keeps query and session cookie then reads page`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "?routing=opaque&did=transient")
                .setHeader("Set-Cookie", "session=local; Path=/; HttpOnly").setBody("skip"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<main>页面</main>"))
            server.start()
            val page = DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/video/1").toString())
            assertEquals(200, page.status)
            assertEquals("<main>页面</main>", page.body)
            assertEquals(server.url("/video/1?routing=opaque&did=transient").toString(), page.finalUrl)
            assertEquals(1, page.redirects.size)
            assertEquals(302, page.redirects.single().status)
            assertFalse(page.redirects.toString().contains("transient"))
            assertFalse(page.redirects.toString().contains("opaque"))
            val first = server.takeRequest(3, TimeUnit.SECONDS)!!
            val second = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", first.method)
            assertEquals(DouyinHttpClient.USER_AGENT, first.getHeader("User-Agent"))
            assertEquals("gzip", first.getHeader("Accept-Encoding"))
            assertEquals("session=local", second.getHeader("Cookie"))
            assertEquals("/video/1?routing=opaque&did=transient", second.path)
        }
    }

    @Test fun `redirect loops and missing locations fail safely`() = runBlocking {
        for (location in listOf("/start", null)) {
            MockWebServer().use { server ->
                val response = MockResponse().setResponseCode(302)
                if (location != null) response.setHeader("Location", location)
                server.enqueue(response)
                server.start()
                try {
                    DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/start").toString())
                    fail("Loop or missing redirect must fail")
                } catch (failure: ExtractError) { assertSame(ExtractError.RedirectFailed, failure) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `redirect limit permits at most eight followed redirects`() = runBlocking {
        MockWebServer().use { server ->
            repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop${it + 1}")) }
            server.start()
            try {
                DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/start").toString())
                fail("Redirect bound must be enforced")
            } catch (failure: ExtractError) { assertSame(ExtractError.RedirectFailed, failure) }
            assertEquals(9, server.requestCount)
        }
    }

    @Test fun `cross domain credentials ambiguous authority and backslash redirects are rejected`() = runBlocking {
        for (location in listOf("https://evil.example/", "http://user@127.0.0.1/", "///evil.example/", "http://evil.example\\@127.0.0.1/")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
                server.start()
                try {
                    DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/start").toString())
                    fail("Unsafe redirect must fail before next request")
                } catch (failure: ExtractError) { assertSame(ExtractError.RedirectFailed, failure) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `declared and chunked response bodies enforce two MiB limit`() = runBlocking {
        val largeBody = "x".repeat(DouyinPageClient.MAX_BODY_BYTES.toInt() + 1)
        for (chunked in listOf(false, true)) {
            MockWebServer().use { server ->
                val response = MockResponse()
                if (chunked) response.setChunkedBody(largeBody, 8192) else response.setBody(largeBody)
                server.enqueue(response)
                server.start()
                try {
                    DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/large").toString())
                    fail("Oversized body must not reach parser")
                } catch (_: IOException) { }
            }
        }
    }

    @Test fun `gzip limit is applied after decompression`() = runBlocking {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("x".repeat(DouyinPageClient.MAX_BODY_BYTES.toInt() + 1)) }
        assertTrue(compressed.size < 10000)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
            server.start()
            try {
                DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/compressed").toString())
                fail("Compressed body must not bypass size cap")
            } catch (_: IOException) { }
        }
    }

    @Test fun `non-success status avoids reading its body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("x".repeat(DouyinPageClient.MAX_BODY_BYTES.toInt() + 1)))
            server.start()
            val page = DouyinPageClient(DouyinHttpClient.newClient()).fetch(server.url("/login").toString())
            assertEquals(401, page.status)
            assertEquals("", page.body)
        }
    }

    @Test fun `cancel propagates to active network call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val cancelled = CountDownLatch(1)
            val http = DouyinHttpClient.newClient().newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val pending = async(Dispatchers.IO) { DouyinPageClient(http).fetch(server.url("/pending").toString()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun `cancellation also aborts reading a slow response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            server.start()
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val http = DouyinHttpClient.newClient().newBuilder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { started.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val pending = async(Dispatchers.IO) { DouyinPageClient(http).fetch(server.url("/slow").toString()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun `public construction never permits loopback credentials external or cleartext targets`() = runBlocking {
        val client = DouyinPageClient()
        for (url in listOf("http://127.0.0.1/", "https://evil.example/", "http://www.douyin.com/video/1", "https://user@www.douyin.com/video/1")) {
            try { client.fetch(url); fail("Public host boundary must reject target") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
