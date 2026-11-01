package io.github.douyinextractor.core.browser

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class BrowserRequestTraceTest {
    @Test fun `diagnostics omit query fragment and opaque filename`() {
        val trace = BrowserRequestTrace()
        trace.blocked("https://lf-unreviewed.bytecdn.com/private-path-secret/token-secret.js?Cookie=query-secret#fragment-secret", BrowserBlockReason.UNREVIEWED_RESOURCE)
        val text = trace.details().single()
        assertTrue(text.contains("lf-unreviewed.bytecdn.com"))
        assertTrue(text.contains("JS 资源"))
        listOf("private-path-secret", "token-secret", "query-secret", "fragment-secret", "Cookie", "?").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test fun `credentials and malformed authority never enter diagnostics`() {
        val invalidUrls = listOf(
            "https://username-secret:password-secret@www.douyin.com/secret",
            "https://@www.douyin.com/secret", "https://www.douyin.com./secret",
            "https://www..douyin.com/secret", "https://%77ww.douyin.com/secret",
            "https://www.douyin.com:444/secret", "https://www.douyin.com\\@evil.invalid/secret",
            "https://-secret.invalid/secret", "https://secret-.invalid/secret",
            "https://" + "a".repeat(64) + ".invalid/secret",
            "https://www.douyin.com/secret\nCookie: private-secret",
            "http://www.douyin.com/secret", "file:///private-secret", "data:text/html,private-secret",
        )
        invalidUrls.forEach { url ->
            val trace = BrowserRequestTrace()
            trace.blocked(url, BrowserBlockReason.UNSAFE_URL)
            assertEquals("阻止 | [invalid URL] | 地址已隐藏 | 不安全地址 | 次数 1", trace.details().single())
        }
    }

    @Test fun `overlong URL is discarded rather than truncated into a valid-looking endpoint`() {
        val trace = BrowserRequestTrace()
        trace.httpFailure("https://secret.invalid/" + "a".repeat(16_384), 403)
        assertFalse(trace.details().single().contains("secret.invalid"))
        assertTrue(trace.details().single().contains("[invalid URL]"))
    }

    @Test fun `known detail route exposes only reviewed static path`() {
        val trace = BrowserRequestTrace()
        trace.httpFailure("https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=123&msToken=hidden#hidden", 403)
        assertEquals("HTTP 错误 | www.douyin.com | /aweme/v1/web/aweme/detail/ | HTTP 403 | 次数 1", trace.details().single())
    }

    @Test fun `unknown API suffix and numeric video identifiers are omitted`() {
        val trace = BrowserRequestTrace()
        trace.networkFailure("https://www.douyin.com/aweme/v1/web/private-token/value-secret", -2)
        trace.networkFailure("https://www.iesdouyin.com/share/video/1234567890123456789/", -7)
        val text = trace.details().joinToString("\n")
        assertTrue(text.contains("/aweme/v1/web/…"))
        assertTrue(text.contains("视频页面"))
        listOf("private-token", "value-secret", "1234567890123456789").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun `encoded paths are categorized without printing their decoded text`() {
        val trace = BrowserRequestTrace()
        trace.blocked("https://lf-example.bytecdn.com/%63ollect/path-secret?hidden=secret", BrowserBlockReason.TRACKING)
        trace.blocked("https://lf-example.bytecdn.com/obj/rc-client-security/web/private-secret.js", BrowserBlockReason.UNREVIEWED_RESOURCE)
        val lines = trace.details()
        assertTrue(lines[0].contains("统计相关路径"))
        assertTrue(lines[1].contains("安全校验相关路径"))
        assertFalse(lines.joinToString().contains("secret"))
    }

    @Test fun `diagnostics preserve actual HTTP and network error codes separately`() {
        val trace = BrowserRequestTrace()
        trace.httpFailure("https://www.douyin.com/api", 429)
        trace.httpFailure("https://www.douyin.com/api", 503)
        trace.networkFailure("https://www.douyin.com/api", -2)
        val text = trace.details().joinToString("\n")
        assertTrue(text.contains("HTTP 429"))
        assertTrue(text.contains("HTTP 503"))
        assertTrue(text.contains("错误码 -2"))
        assertEquals("资源阻止：0 次；HTTP 错误（不含本机拦截）：2 次；网络错误：1 次；保留记录：3 类", trace.summary())
    }

    @Test fun `different secrets for the same safe endpoint aggregate into one count`() {
        val trace = BrowserRequestTrace()
        repeat(1_000) {
            trace.blocked("https://lf-example.bytecdn.com/token-$it.js?secret=$it", BrowserBlockReason.UNREVIEWED_RESOURCE)
        }
        assertEquals(1, trace.details().size)
        assertTrue(trace.details().single().endsWith("次数 1000"))
        assertTrue(trace.summary().contains("资源阻止：1000 次"))
    }

    @Test fun `bounded collection retains latest repeated endpoint and lifetime totals`() {
        val trace = BrowserRequestTrace()
        repeat(64) { trace.blocked("https://host$it.invalid/private", BrowserBlockReason.UNREVIEWED_RESOURCE) }
        trace.blocked("https://host0.invalid/another-secret", BrowserBlockReason.UNREVIEWED_RESOURCE)
        trace.blocked("https://host64.invalid/private", BrowserBlockReason.UNREVIEWED_RESOURCE)
        val lines = trace.details()
        assertEquals(64, lines.size)
        assertTrue(lines.any { it.contains("host0.invalid") && it.endsWith("次数 2") })
        assertFalse(lines.any { it.contains("| host1.invalid |") })
        assertTrue(lines.last().contains("host64.invalid"))
        assertTrue(trace.summary().contains("资源阻止：66 次"))
        assertTrue(trace.summary().contains("早期记录已省略"))
    }

    @Test fun `report snapshots remain detached from subsequent updates`() {
        val trace = BrowserRequestTrace()
        trace.networkFailure("https://www.douyin.com/api", -2)
        val snapshot = trace.details()
        trace.networkFailure("https://www.douyin.com/api", -2)
        assertTrue(snapshot.single().endsWith("次数 1"))
        assertTrue(trace.details().single().endsWith("次数 2"))
    }

    @Test fun `concurrent callbacks and report snapshots cannot lose counts`() {
        val trace = BrowserRequestTrace()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> Callable {
                repeat(250) {
                    trace.blocked("https://lf-example.bytecdn.com/path-$worker-$it.js", BrowserBlockReason.UNREVIEWED_RESOURCE)
                    assertTrue(trace.details().size <= BrowserRequestTrace.MAX_ENTRIES)
                    trace.summary()
                }
            } }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, trace.details().size)
        assertTrue(trace.details().single().endsWith("次数 1000"))
    }
}
