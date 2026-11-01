package io.github.douyinextractor.core.input

import io.github.douyinextractor.core.network.DouyinHttpResponse
import io.github.douyinextractor.core.network.DouyinTransport
import io.github.douyinextractor.core.network.DouyinUrlResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Uses the reported share texts and observed 302 target; network responses remain synthetic. */
class UserShareRegressionTest {
    @Test fun `reported prose HTTPS HTTP and bare short links resolve to same target`() = runTest {
        for (token in listOf("bh3WPFYIwso", "5A6uFUxGMFA")) {
            val short = "https://v.douyin.com/$token/"
            for (text in listOf(
                "2.51 复制打开抖音，看看【知物集的作品】连擦马桶都不配的工业垃圾，你居然天天拿来擦嘴？ #... $short :0pm ipd:/ g@b.an 03/21",
                short, short.replace("https:", "http:"), short.removePrefix("https://"),
            )) {
                val parsed = ShareTextParser().parse(text).getOrThrow()
                assertEquals(short, parsed.url)
                val requests = mutableListOf<String>()
                val transport = DouyinTransport { url ->
                    requests += url
                    DouyinHttpResponse(302, "https://www.iesdouyin.com/share/video/$ID/?share_sign=synthetic-secret", "text/html")
                }
                val result = DouyinUrlResolver(transport).resolve(parsed.url).getOrThrow()
                assertEquals(listOf(short), requests)
                assertEquals(ID, result.awemeId)
                assertEquals(DouyinUrls.canonical(ID), result.canonicalUrl)
                assertFalse(result.redirects.toString().contains("synthetic-secret"))
            }
        }
    }

    @Test fun `HTTP direct target input is upgraded and does not resolve through a network request`() = runTest {
        val parsed = ShareTextParser().parse("http://www.douyin.com/video/$ID").getOrThrow()
        val result = DouyinUrlResolver(DouyinTransport { error("Direct identity must not need a redirect request") })
            .resolve(parsed.url).getOrThrow()
        assertEquals(ID, result.awemeId)
        assertTrue(result.redirects.isEmpty())
    }

    private companion object { const val ID = "7690790070625783066" }
}
