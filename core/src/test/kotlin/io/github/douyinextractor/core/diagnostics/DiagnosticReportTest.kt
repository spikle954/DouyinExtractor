package io.github.douyinextractor.core.diagnostics

import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.RedirectStep
import io.github.douyinextractor.core.extractor.douyin.ExtractionEvent
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportTest {
    @Test fun `browser polling keeps latest bounded observation and strips secrets`() {
        val trace = ResolutionDiagnostics()
        repeat(100) { index ->
            trace.browserSnapshot("https://www.douyin.com/video/123?token=private-token", "快照 $index", listOf(ParserAttempt("BrowserState", "https://synthetic.douyinvod.com/a.mp4?secret=private-secret")))
        }
        val report = DiagnosticReport.format("分享", null, null, ExtractError.UnsupportedPage, trace)
        assertTrue(report.contains("快照 99"))
        assertFalse(report.contains("快照 98"))
        assertFalse(report.contains("private-"))
        assertEquals(3, trace.browserSteps().size)
    }
    @Test fun `extraction report includes evidence while redacting signed queries and credentials`() {
        val trace = ResolutionDiagnostics()
        trace.extraction(ExtractionEvent.Redirect(RedirectStep("https://www.douyin.com/video/123?token=private-token", 302, "https://www.iesdouyin.com/share/video/123/?did=private-device", "text/html")))
        trace.extraction(ExtractionEvent.Page("https://www.iesdouyin.com/share/video/123/?token=private-token", 200, "text/html; charset=utf-8", "状态为空"))
        trace.extraction(ExtractionEvent.Parser("https://www.iesdouyin.com/share/video/123/?token=private-token", ParserAttempt("RouterDataParser", "有效状态没有匹配媒体")))
        val report = DiagnosticReport.format("分享", null, null, ExtractError.UnsupportedPage, trace)
        assertFalse(report.contains("private-"))
        assertTrue(report.contains("RouterDataParser"))
        assertTrue(report.contains("HTTP 200"))
        assertTrue(report.contains("MediaFormat：0"))
    }

    @Test fun `protocol free root query and fragment are also redacted`() {
        listOf(
            "douyin.com?modal_id=123&did=private-device",
            "www.douyin.com#private-fragment",
            "v.douyin.com/code/?opaque=private-route",
            "https://user:private-password@www.douyin.com/video/123?token=private-token",
        ).forEach { assertFalse(DiagnosticReport.redact(it).contains("private-")) }
    }

    @Test fun `export retains debugging evidence without cookies tracking or URL credentials`() {
        val trace = ResolutionDiagnostics()
        trace.record(RedirectStep(
            "https://v.douyin.com/abc/?opaque=secret-query", 302,
            "https://www.iesdouyin.com/share/video/123/?did=private-device&share_sign=private-sign",
            "text/html; charset=utf-8",
        ))
        trace.note("Authorization: Bearer private-auth")
        val report = DiagnosticReport.format(
            "分享 https://v.douyin.com/abc/?did=private-user\nCookie: sessionid=private-cookie",
            "https://v.douyin.com/abc/?opaque=secret-query", null, ExtractError.RedirectFailed, trace,
        )
        listOf("secret-query", "private-device", "private-sign", "private-auth", "private-user", "private-cookie").forEach {
            assertFalse("Leaked $it", report.contains(it))
        }
        assertTrue(report.contains("302"))
        assertTrue(report.contains("share/video/123/"))
        assertTrue(report.contains("Content-Type：text/html"))
    }

    @Test fun `trace snapshots are not mutable internal storage`() {
        val trace = ResolutionDiagnostics()
        trace.note("first")
        val snapshot = trace.notes()
        trace.note("second")
        assertEquals(listOf("first"), snapshot)
        assertEquals(2, trace.notes().size)
    }

    @Test fun `oversized trace cannot grow without limit`() {
        val trace = ResolutionDiagnostics()
        repeat(100) {
            trace.note("n".repeat(2000))
            trace.record(RedirectStep("https://v.douyin.com/a/", 302, null, null))
        }
        assertEquals(32, trace.notes().size)
        assertEquals(16, trace.steps().size)
        assertTrue(trace.notes().all { it.length <= 512 })
    }
}
