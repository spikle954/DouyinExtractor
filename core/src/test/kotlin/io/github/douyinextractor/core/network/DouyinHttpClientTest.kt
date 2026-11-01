package io.github.douyinextractor.core.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class DouyinHttpClientTest {
    @Test fun `real HTTP transport uses expected headers and leaves redirects to resolver`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")
                .setHeader("Content-Type", "text/html; charset=utf-8").setBody("redirect"))
            server.enqueue(MockResponse().setBody("Should not be requested"))
            server.start()
            val client = DouyinHttpClient(DouyinHttpClient.newClient())
            val response = client.fetch(server.url("/start").toString())
            assertEquals(302, response.status)
            assertEquals("/next", response.location)
            assertEquals("text/html; charset=utf-8", response.contentType)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals(DouyinHttpClient.USER_AGENT, request.getHeader("User-Agent"))
            assertEquals("https://www.douyin.com/", request.getHeader("Referer"))
            assertTrue(request.getHeader("Accept")!!.contains("text/html"))
            assertTrue(request.getHeader("Accept-Language")!!.contains("zh-CN"))
            assertEquals("gzip", request.getHeader("Accept-Encoding"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `response cookies remain within one client session`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=local-test; Path=/; HttpOnly").setBody("one"))
            server.enqueue(MockResponse().setBody("two"))
            server.enqueue(MockResponse().setBody("three"))
            server.start()
            val client = DouyinHttpClient(DouyinHttpClient.newClient())
            client.fetch(server.url("/one").toString())
            client.fetch(server.url("/two").toString())
            DouyinHttpClient(DouyinHttpClient.newClient()).fetch(server.url("/three").toString())
            assertNull(server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            assertEquals("session=local-test", server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Cookie"))
            assertNull(server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        }
    }

    @Test fun `cancellation cancels the actual OkHttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val cancelled = CountDownLatch(1)
            val http = DouyinHttpClient.newClient().newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val client = DouyinHttpClient(http)
            val pending = async(Dispatchers.IO) { client.fetch(server.url("/pending").toString()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun `network failure is surfaced with no hidden retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val client = DouyinHttpClient(DouyinHttpClient.newClient())
            try {
                client.fetch(server.url("/disconnect").toString())
                fail("Disconnected response must fail")
            } catch (_: IOException) {
                // Expected. Resolver maps this to PageLoadFailed.
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `public client refuses localhost external hosts and cleartext URLs`() = runBlocking {
        val client = DouyinHttpClient()
        for (url in listOf("http://127.0.0.1:1234/", "https://evil.example/", "http://v.douyin.com/start/")) {
            try {
                client.fetch(url)
                fail("Public transport must reject unsafe targets")
            } catch (_: IllegalArgumentException) {
                // Rejected before opening a socket.
            }
        }
    }

    @Test fun `cookie jar respects domain path secure expiration and replacement`() {
        val jar = SessionCookieJar()
        val source = "https://v.douyin.com/".toHttpUrl()
        fun cookie(text: String) = Cookie.parse(source, text)!!
        jar.saveFromResponse(source, listOf(
            cookie("session=first; Path=/; Secure"),
            cookie("path=only; Path=/limited; Secure"),
        ))
        assertEquals(listOf("session"), jar.loadForRequest(source).map { it.name })
        assertEquals(2, jar.loadForRequest("https://v.douyin.com/limited/video".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://evil.example/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://www.douyin.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://v.douyin.com/".toHttpUrl()).isEmpty())
        jar.saveFromResponse(source, listOf(cookie("session=second; Path=/; Secure")))
        assertEquals("second", jar.loadForRequest(source).single().value)
        jar.saveFromResponse(source, listOf(cookie("session=removed; Path=/; Max-Age=0; Secure")))
        assertTrue(jar.loadForRequest(source).isEmpty())
    }

    @Test fun `cookie storage is bounded`() {
        val jar = SessionCookieJar()
        val url = "https://v.douyin.com/".toHttpUrl()
        jar.saveFromResponse(url, (1..150).map { Cookie.parse(url, "cookie$it=value; Secure; Path=/")!! })
        val cookies = jar.loadForRequest(url)
        assertEquals(100, cookies.size)
        assertFalse(cookies.any { it.name == "cookie1" })
        assertTrue(cookies.any { it.name == "cookie150" })
    }
}
