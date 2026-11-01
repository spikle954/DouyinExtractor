package io.github.douyinextractor.core.extractor.social

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SocialHttpClientTest {
    private fun client() = SocialHttpClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), false)
    @Test fun `public page referer is platform-specific and cookies are not replayed`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("<html></html>").addHeader("Set-Cookie","session=SECRET")) }
            val transport=client()
            for (platform in listOf(SocialUrls.KUAISHOU,SocialUrls.XIAOHONGSHU)) {
                assertEquals(200,transport.fetch(server.url("/page").toString(),platform).status)
                val request=server.takeRequest()
                assertEquals(SocialUrls.referer(platform),request.getHeader("Referer"))
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `redirect and error bodies are skipped and no automatic redirects occur`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://evil.test/").setBody("x".repeat(1024)))
            val result=client().fetch(server.url("/short").toString(),SocialUrls.KUAISHOU)
            assertEquals(302,result.status);assertEquals("",result.body);assertEquals(1,server.requestCount)
        }
    }
    @Test fun `declared and chunked response limits both reject oversized pages`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a").setHeader("Content-Length",SocialHttpClient.MAX_BYTES+1))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(SocialHttpClient.MAX_BYTES+1),8192))
            repeat(2) {try {client().fetch(server.url("/page").toString(),SocialUrls.KUAISHOU);fail("oversized")}catch(_:IOException){}}
        }
    }
    @Test fun `cancel stops the active transport call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job=async {client().fetch(server.url("/page").toString(),SocialUrls.KUAISHOU)}
            withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))}
            job.cancel();withTimeout(3000) {job.join()};assertTrue(job.isCancelled)
        }
    }
    @Test fun `production transport blocks unrelated hosts cleartext and cross-platform pages`() = runBlocking {
        for (url in listOf("https://evil.test/","http://www.kuaishou.com/short-video/3xgp7zntw6w2zqq","https://www.kuaishou.com/graphql","https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9")) {
            try {SocialHttpClient().fetch(url,SocialUrls.KUAISHOU);fail("unsafe")}catch(_:IllegalArgumentException){}
        }
    }
}
