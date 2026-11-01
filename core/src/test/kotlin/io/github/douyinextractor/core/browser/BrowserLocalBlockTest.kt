package io.github.douyinextractor.core.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserLocalBlockTest {
    @Test fun `synthetic 403 callbacks do not turn local blocks into server failures`() {
        val trace = BrowserRequestTrace()
        val local = BrowserLocalBlock()
        repeat(17) { trace.blocked("https://blocked.example/script.js?secret=$it", BrowserBlockReason.UNREVIEWED_RESOURCE) }
        repeat(15) {
            assertFalse(trace.httpFailure("https://blocked.example/script.js?secret=$it", 403, local,
                local.reasonPhrase, local.headers))
        }
        assertTrue(trace.summary().contains("资源阻止：17 次"))
        assertTrue(trace.summary().contains("HTTP 错误（不含本机拦截）：0 次"))
        assertEquals(1, trace.details().size)
        assertFalse(trace.details().single().contains("secret"))
    }

    @Test fun `real 403 on a previously blocked host still counts without local marker`() {
        val trace = BrowserRequestTrace()
        val local = BrowserLocalBlock()
        val url = "https://www.douyin.com/video/123"
        trace.blocked(url, BrowserBlockReason.OTHER_VIDEO)
        assertTrue(trace.httpFailure(url, 403, local, "Forbidden", emptyMap()))
        assertTrue(trace.httpFailure(url, 503, local, local.reasonPhrase, local.headers))
        assertTrue(trace.details().any { it.contains("HTTP 403") })
        assertTrue(trace.details().any { it.contains("HTTP 503") })
    }

    @Test fun `header case and reason-only callbacks identify this sessions response`() {
        val local = BrowserLocalBlock()
        assertTrue(local.matches(403, null, local.headers.mapKeys { it.key.lowercase() }))
        assertTrue(local.matches(403, local.reasonPhrase, null))
        assertFalse(local.matches(403, "Blocked", null))
        assertFalse(local.matches(403, null, mapOf("X-Shiying-Local-Block" to "server-value")))
    }

    @Test fun `another browser session cannot suppress this sessions HTTP failures`() {
        val first = BrowserLocalBlock()
        val second = BrowserLocalBlock()
        assertFalse(first.matches(403, second.reasonPhrase, second.headers))
    }
}
