package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.DiagnosticReport
import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.model.ExtractError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SocialRecoveryTest {
    private val id = "3xucdsfddb9cz4m"
    private val short = "https://v.kuaishou.com/nApsFvK2"
    private val canonical = "https://www.kuaishou.com/short-video/$id"
    private val mobile = "https://v.m.chenzhongtech.com/fw/photo/$id"
    private val mp4 = "https://v1.kwaicdn.com/sample.mp4?sig=SECRET"
    private val shell = "<script>window.__APOLLO_STATE__={\"defaultClient\":{}};</script>"
    private val video = "<script>window.__APOLLO_STATE__={\"defaultClient\":{\"VisionVideoDetailPhoto:$id\":{\"id\":\"$id\",\"photoUrl\":\"$mp4\"}}};</script>"

    @Test fun `desktop shell recovers through official mobile redirect to the same target`() = runBlocking {
        val visited = mutableListOf<String>(); val trace = ResolutionDiagnostics()
        val extractor = SocialExtractor { url, _ ->
            visited += url
            when (visited.size) {
                1 -> SocialResponse(200, shell, contentType = "text/html")
                2 -> { assertEquals(mobile, url); SocialResponse(302, location = canonical) }
                else -> SocialResponse(200, video, contentType = "text/html")
            }
        }
        val media = extractor.extract(canonical, trace).getOrThrow()
        assertEquals(listOf(canonical, mobile, canonical), visited)
        assertEquals(id, media.id); assertEquals(canonical, media.canonicalUrl); assertEquals(mp4, media.formats.single().url)
        val report = DiagnosticReport.format(canonical, canonical, null, null, trace, media)
        assertTrue(report.contains("目标作品状态 0")); assertTrue(report.contains("目标作品状态 1"))
        assertFalse(report.contains("SECRET")); assertFalse(report.contains("sample.mp4"))
    }
    @Test fun `successful desktop response never makes a fallback request`() = runBlocking {
        var calls = 0
        assertEquals(id, SocialExtractor { _, _ -> calls++; SocialResponse(200, video) }.extract(canonical).getOrThrow().id)
        assertEquals(1, calls)
    }
    @Test fun `missing resources stay a failure after only one alternative`() = runBlocking {
        val visited = mutableListOf<String>()
        val extractor = SocialExtractor { url, _ -> visited += url; SocialResponse(200, shell) }
        assertEquals(ExtractError.UnsupportedPage, extractor.extract(canonical).exceptionOrNull())
        assertEquals(listOf(canonical, mobile), visited)
    }
    @Test fun `refusal login deletion and mismatching work stop without recovery`() = runBlocking {
        for (response in listOf(SocialResponse(403), SocialResponse(429), SocialResponse(401), SocialResponse(404),
            SocialResponse(302, location = "https://www.kuaishou.com/short-video/3xanotherid1234"),
            SocialResponse(200, "<body>安全验证</body>"), SocialResponse(200, "<body>作品不存在</body>"))) {
            var calls = 0
            val result = SocialExtractor { _, _ -> calls++; response }.extract(canonical)
            assertTrue(result.isFailure); assertEquals(1, calls)
        }
    }
    @Test fun `redirect budgets stay global across desktop and mobile alternatives`() = runBlocking {
        var calls = 0
        val extractor = SocialExtractor { url, _ ->
            calls++
            when {
                calls == 1 -> SocialResponse(200, shell)
                url == mobile -> SocialResponse(302, location = "https://www.kuaishou.com/f/next$calls")
                else -> SocialResponse(302, location = "https://www.kuaishou.com/f/next$calls")
            }
        }
        assertEquals(ExtractError.RedirectFailed, extractor.extract(canonical).exceptionOrNull())
        assertEquals(7, calls)
    }
    @Test fun `Xiaohongshu does not get Kuaishou fallback or connection retry`() = runBlocking {
        var calls = 0
        val extractor = SocialExtractor { _, _ -> calls++; SocialResponse(200, "<html></html>") }
        assertEquals(ExtractError.UnsupportedPage, extractor.extract("https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9?xsec_token=SECRET").exceptionOrNull())
        assertEquals(1, calls)
        val failed = SocialExtractor { _, _ -> calls++; throw ConnectException("SECRET") }
        assertEquals(ExtractError.PageLoadFailed, failed.resolve("https://xhslink.cn/o/Abc123", ResolutionDiagnostics()).exceptionOrNull())
        assertEquals(2, calls)
    }
    @Test fun `short connection interruption retries once and keeps identity`() = runBlocking {
        var calls = 0; val trace = ResolutionDiagnostics()
        val extractor = SocialExtractor { url, _ ->
            assertEquals(short, url); calls++
            if (calls == 1) throw ConnectException("https://evil.test/?token=SECRET")
            SocialResponse(302, location = "$mobile?photoId=$id&shareToken=SECRET")
        }
        assertEquals(id, extractor.resolve(short, trace).getOrThrow().awemeId); assertEquals(2, calls)
        assertTrue(trace.notes().any { it.contains("CONNECT") }); assertFalse(trace.notes().joinToString().contains("SECRET"))
        var failures = 0
        val failed = SocialExtractor { _, _ -> failures++; throw SocketTimeoutException("SECRET") }
        assertEquals(ExtractError.PageLoadFailed, failed.resolve(short, ResolutionDiagnostics()).exceptionOrNull())
        assertEquals(2, failures)
    }
    @Test fun `own short deadline cancels stalled call and leaves time for one retry`() = runTest {
        var calls = 0; var firstCancelled = false
        val extractor = SocialExtractor { _, _ ->
            calls++
            if (calls == 1) try { awaitCancellation() } finally { firstCancelled = true }
            SocialResponse(302, location = canonical)
        }
        assertEquals(id, extractor.resolve(short, ResolutionDiagnostics()).getOrThrow().awemeId)
        assertEquals(2, calls); assertTrue(firstCancelled)
    }
    @Test fun `Xiaohongshu retains its existing request timeout rather than Kuaishou short deadline`() = runTest {
        var calls = 0
        val extractor = SocialExtractor { _, _ ->
            calls++; delay(12_000)
            SocialResponse(302, location = "https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9?xsec_token=SECRET")
        }
        assertTrue(extractor.resolve("https://xhslink.cn/o/Abc123", ResolutionDiagnostics()).isSuccess)
        assertEquals(1, calls)
    }
    @Test fun `DNS and TLS get specific diagnostics and no same-host short retry`() = runBlocking {
        for ((error, kind) in listOf(UnknownHostException("SECRET") to "DNS", SSLHandshakeException("SECRET") to "TLS")) {
            var calls = 0; val trace = ResolutionDiagnostics()
            val extractor = SocialExtractor { _, _ -> calls++; throw error }
            assertEquals(ExtractError.PageLoadFailed, extractor.resolve(short, trace).exceptionOrNull()); assertEquals(1, calls)
            val report = DiagnosticReport.format(short, short, null, ExtractError.PageLoadFailed, trace)
            assertTrue(report.contains("平台：Kuaishou")); assertTrue(report.contains("快手作品编号：未识别"))
            assertTrue(report.contains("($kind)") || report.contains("（$kind）")); assertFalse(report.contains("SECRET"))
        }
    }
    @Test fun `body failure reports received HTTP and is not a short connection retry`() = runBlocking {
        var calls = 0; val trace = ResolutionDiagnostics()
        val extractor = SocialExtractor { _, _ -> calls++; throw SocialRequestFailure(SocialRequestFailure.Kind.TIMEOUT, 200) }
        assertEquals(ExtractError.PageLoadFailed, extractor.resolve(short, trace).exceptionOrNull()); assertEquals(1, calls)
        assertTrue(trace.notes().single().contains("HTTP 200"))
    }
    @Test fun `TLS and response size failures never trigger page recovery`() = runBlocking {
        for (error in listOf(SSLHandshakeException("SECRET"), SocialRequestFailure(SocialRequestFailure.Kind.TOO_LARGE, 200))) {
            var calls = 0; val trace = ResolutionDiagnostics()
            assertTrue(SocialExtractor { _, _ -> calls++; throw error }.extract(canonical, trace).isFailure)
            assertEquals(1, calls); assertFalse(trace.notes().joinToString().contains("SECRET"))
        }
    }
    @Test fun `user cancellation and outer timeout cancel active request without retry`() = runBlocking {
        var calls = 0; var cancelled = false
        val extractor = SocialExtractor { _, _ ->
            calls++
            try { awaitCancellation() } finally { cancelled = true }
        }
        try { withTimeout(100) { extractor.resolve(short, ResolutionDiagnostics()) }; fail() } catch (_: TimeoutCancellationException) {}
        assertEquals(1, calls); assertTrue(cancelled)
        val job = async { extractor.extract(canonical) }
        yield(); job.cancelAndJoin(); assertEquals(2, calls)
    }
    @Test fun `parser diagnostics distinguish malformed state missing target and rejected resource without page values`() {
        val samples = listOf(
            "<html></html>" to "状态块 0",
            "<script>window.__APOLLO_STATE__={bad-json};</script>" to "可解析状态 0",
            shell to "目标作品状态 0",
            video.replace("v1.kwaicdn.com", "evil.test") to "被筛除地址 1",
        )
        for ((html, expected) in samples) {
            val trace = ResolutionDiagnostics()
            assertThrows(ExtractError::class.java) { SocialPageMapping.parse(html, SocialUrls.KUAISHOU, id, trace::note) }
            assertTrue(trace.notes().single().contains(expected)); assertFalse(trace.notes().single().contains("SECRET"))
            assertFalse(trace.notes().single().contains("evil.test"))
        }
    }
    @Test fun `wrapped failure classification remains bounded and hides original exception contents`() {
        val failure = SocialRequestFailure.classify(IOException("SECRET", SSLHandshakeException("PRIVATE")), 200)
        assertEquals(SocialRequestFailure.Kind.TLS, failure.kind); assertEquals(200, failure.status)
        assertNull(failure.cause); assertFalse(failure.message!!.contains("SECRET")); assertFalse(failure.canTryOtherPage)
    }
}
