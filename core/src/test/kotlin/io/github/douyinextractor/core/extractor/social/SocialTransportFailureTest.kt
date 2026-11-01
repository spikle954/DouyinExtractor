package io.github.douyinextractor.core.extractor.social

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class SocialTransportFailureTest {
    private fun client() = SocialHttpClient(OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build(), false)
    @Test fun `received oversized HTTP response stays a size failure instead of connectivity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", SocialHttpClient.MAX_BYTES + 1))
            val error = try { client().fetch(server.url("/page").toString(), SocialUrls.KUAISHOU); null } catch (e: SocialRequestFailure) { e }
            assertNotNull(error); assertEquals(SocialRequestFailure.Kind.TOO_LARGE, error!!.kind); assertEquals(200, error.status)
            assertFalse(error.canRetryConnection); assertFalse(error.canTryOtherPage)
        }
    }
    @Test fun `stalled headers report timeout without inventing an HTTP response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val error = try { client().fetch(server.url("/page").toString(), SocialUrls.KUAISHOU); null } catch (e: SocialRequestFailure) { e }
            assertNotNull(error); assertEquals(SocialRequestFailure.Kind.TIMEOUT, error!!.kind); assertNull(error.status)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `interrupted body retains HTTP response fact while hiding transport text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(4096)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val error = try { client().fetch(server.url("/page").toString(), SocialUrls.KUAISHOU); null } catch (e: SocialRequestFailure) { e }
            assertNotNull(error); assertEquals(SocialRequestFailure.Kind.CONNECTION, error!!.kind); assertEquals(200, error.status)
            assertFalse(error.canRetryConnection); assertEquals(1, server.requestCount)
        }
    }
}
