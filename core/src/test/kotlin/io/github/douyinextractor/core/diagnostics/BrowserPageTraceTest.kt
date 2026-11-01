package io.github.douyinextractor.core.diagnostics

import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import org.junit.Assert.*
import org.junit.Test

class BrowserPageTraceTest {
    @Test fun `page transitions remain bounded redacted and separate from parser observations`() {
        val trace = ResolutionDiagnostics()
        val url = "https://m.douyin.com/share/video/123?token=private"
        repeat(20) {
            trace.browserPage(BrowserPageEvent.ENTRY, url)
            trace.browserPage(BrowserPageEvent.STARTED, url)
        }
        assertEquals(12, trace.browserPageSteps().size)
        assertFalse(trace.browserPageSteps().joinToString().contains("private"))
        trace.browserSnapshot(url, "latest observation", listOf(ParserAttempt("test", "no media")))
        trace.browserPage(BrowserPageEvent.FINISHED, url)
        assertTrue(trace.browserSteps().joinToString().contains("latest observation"))
        trace.browserPage(BrowserPageEvent.ENTRY, "https://www.douyin.com/video/123")
        assertTrue(trace.browserSteps().isEmpty())
        trace.clearBrowser()
        assertTrue(trace.browserPageSteps().isEmpty())
    }
}
