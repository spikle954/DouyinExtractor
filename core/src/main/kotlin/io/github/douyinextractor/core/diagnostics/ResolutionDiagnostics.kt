package io.github.douyinextractor.core.diagnostics

import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.core.model.RedirectStep
import io.github.douyinextractor.core.extractor.douyin.ExtractionEvent
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import io.github.douyinextractor.core.browser.BrowserRequestTrace

enum class BrowserPageEvent(val label: String) { ENTRY("打开入口"), STARTED("页面开始"), FINISHED("页面完成") }

/** Request-scoped, memory-only trace. Never accepts cookie/request-header fields. */
class ResolutionDiagnostics {
    private val entries = mutableListOf<RedirectStep>()
    private val messages = mutableListOf<String>()
    private val extraction = mutableListOf<String>()
    private var browser = emptyList<String>()
    private var browserNetwork = emptyList<String>()
    private val browserPages = mutableListOf<String>()

    @Synchronized fun clearBrowser() {
        browser = emptyList()
        browserNetwork = emptyList()
        browserPages.clear()
    }

    @Synchronized fun browserPage(event: BrowserPageEvent, url: String) {
        val safeUrl = safeBrowserUrl(url)
        val line = "本机网页${event.label}：$safeUrl"
        if (browserPages.lastOrNull() != line) {
            browserPages += line
            if (browserPages.size > 12) browserPages.removeAt(0)
        }
        if (event != BrowserPageEvent.FINISHED) browser = emptyList()
    }

    @Synchronized fun browserPageSteps(): List<String> = browserPages.toList()

    /** Detached bounded snapshot; later WebView callbacks cannot change a copied report. */
    @Synchronized fun browserNetwork(trace: BrowserRequestTrace) {
        synchronized(trace) {
            browserNetwork = listOf("本机网页网络摘要：${DiagnosticReport.redact(trace.summary()).take(800)}") +
                trace.details().take(BrowserRequestTrace.MAX_ENTRIES).map {
                    "Browser Network ${DiagnosticReport.redact(it).replace('\n', ' ').replace('\r', ' ').take(512)}"
                }
        }
    }

    @Synchronized fun browserNetworkSteps(): List<String> = browserNetwork.toList()

    /** Keep the latest browser observation instead of filling the report with identical polling. */
    @Synchronized fun browserSnapshot(pageUrl: String?, reason: String, attempts: List<ParserAttempt>) {
        browser = listOf("本机网页最新状态：${DiagnosticReport.redact(reason).take(800)}",
            "网页地址：${pageUrl?.let(::safeBrowserUrl) ?: "暂未确认"}") + attempts.take(20).map {
            "Browser Parser ${DiagnosticReport.redact(it.parser).take(80)} | ${DiagnosticReport.redact(it.reason).take(300)} | MediaFormat：${it.formatCount}"
        }
    }

    @Synchronized fun browserSteps(): List<String> = browser.toList()

    @Synchronized fun extraction(event: ExtractionEvent) {
        if (extraction.size >= 96) return
        extraction += when (event) {
            is ExtractionEvent.Redirect -> "页面跳转：${event.step.status} ${DouyinUrls.sanitizeForDiagnostics(event.step.url)} → ${event.step.location?.let(DouyinUrls::sanitizeForDiagnostics) ?: "未提供"} | Content-Type：${event.step.contentType?.substringBefore(';')?.take(100) ?: "未提供"}"
            is ExtractionEvent.Page -> "页面：${DouyinUrls.sanitizeForDiagnostics(event.url)} | HTTP ${event.status ?: "未响应"} | Content-Type：${event.contentType?.substringBefore(';')?.take(100) ?: "未提供"} | ${DiagnosticReport.redact(event.reason).take(300)}"
            is ExtractionEvent.Parser -> "Parser ${DiagnosticReport.redact(event.attempt.parser).take(80)} | ${DouyinUrls.sanitizeForDiagnostics(event.pageUrl)} | ${DiagnosticReport.redact(event.attempt.reason).take(300)} | MediaFormat：${event.attempt.formatCount}"
        }
    }

    @Synchronized fun extractionSteps(): List<String> = extraction.toList()

    @Synchronized fun record(step: RedirectStep) {
        if (entries.size < 16) entries += step.copy(
            url = SocialUrls.normalize(step.url)?.substringBefore('?') ?: DouyinUrls.sanitizeForDiagnostics(step.url),
            location = step.location?.let { SocialUrls.normalize(it)?.substringBefore('?') ?: DouyinUrls.sanitizeForDiagnostics(it) },
            contentType = step.contentType?.substringBefore(';')?.take(100),
        )
    }

    @Synchronized fun note(message: String) {
        if (messages.size < 32) messages += DiagnosticReport.redact(message).take(512)
    }

    @Synchronized fun steps(): List<RedirectStep> = entries.toList()
    @Synchronized fun notes(): List<String> = messages.toList()
    private fun safeBrowserUrl(url: String) = SocialUrls.normalize(url)?.substringBefore('?') ?: DouyinUrls.sanitizeForDiagnostics(url)
}
