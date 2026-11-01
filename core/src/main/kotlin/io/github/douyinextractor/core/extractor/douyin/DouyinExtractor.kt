package io.github.douyinextractor.core.extractor.douyin

import io.github.douyinextractor.core.extractor.VideoExtractor
import io.github.douyinextractor.core.extractor.douyin.parser.DouyinPageParser
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.model.RedirectStep
import io.github.douyinextractor.core.network.DouyinPageClient
import io.github.douyinextractor.core.network.DouyinPageTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Templates are reviewable local configuration, never downloaded or remotely executed. */
class DouyinPageConfig(pageTemplates: List<String> = DEFAULT_PAGE_TEMPLATES) {
    val pageTemplates: List<String> = pageTemplates.toList()
    init {
        require(pageTemplates.size in 1..6) { "页面策略数量必须为 1–6。" }
        require(pageTemplates.distinct().size == pageTemplates.size) { "页面策略不能重复。" }
        for (template in pageTemplates) {
            require(template.split("{id}").size == 2) { "每个页面策略必须包含一个作品 ID 占位符。" }
            val example = template.replace("{id}", "7000000000000000001")
            require(DouyinUrls.isAllowed(example) && DouyinUrls.videoId(example) == "7000000000000000001") {
                "页面策略仅允许抖音官方 HTTPS 普通视频地址。"
            }
            require('?' !in example && '#' !in example) { "页面策略不能携带查询参数或片段。" }
        }
    }

    companion object {
        val DEFAULT_PAGE_TEMPLATES = listOf(
            "https://www.douyin.com/video/{id}",
            "https://www.iesdouyin.com/share/video/{id}/",
            "https://m.douyin.com/share/video/{id}/",
        )
    }
}

sealed interface ExtractionEvent {
    data class Redirect(val step: RedirectStep) : ExtractionEvent
    /** Reasons are fixed structural messages. Consumers must redact URL queries before logging. */
    data class Page(val url: String, val status: Int?, val contentType: String?, val reason: String) : ExtractionEvent
    data class Parser(val pageUrl: String, val attempt: ParserAttempt) : ExtractionEvent
}

/** Receives an already resolved ordinary-video URL; share links are resolved by DouyinUrlResolver. */
class DouyinExtractor(
    private val transport: DouyinPageTransport = DouyinPageClient(),
    private val parser: DouyinPageParser = DouyinPageParser(),
    private val config: DouyinPageConfig = DouyinPageConfig(),
) : VideoExtractor {
    override fun canHandle(url: String): Boolean = DouyinUrls.isAllowed(url) && DouyinUrls.videoId(url) != null

    override suspend fun extract(url: String): Result<MediaInfo> = extract(url) {}

    suspend fun extract(url: String, observer: (ExtractionEvent) -> Unit): Result<MediaInfo> {
        currentCoroutineContext().ensureActive()
        if (!canHandle(url)) return Result.failure(ExtractError.InvalidUrl)
        val targetId = DouyinUrls.videoId(url) ?: return Result.failure(ExtractError.InvalidUrl)
        var lastError: ExtractError = ExtractError.UnsupportedPage
        for (template in config.pageTemplates) {
            currentCoroutineContext().ensureActive()
            val pageUrl = template.replace("{id}", targetId)
            val page = try {
                transport.fetch(pageUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ExtractError) {
                observer(ExtractionEvent.Page(pageUrl, null, null, "页面跳转未完成。"))
                lastError = failure
                continue
            } catch (_: IOException) {
                observer(ExtractionEvent.Page(pageUrl, null, null, "网络请求未完成或页面超过读取上限。"))
                lastError = ExtractError.PageLoadFailed
                continue
            }
            currentCoroutineContext().ensureActive()
            page.redirects.forEach { observer(ExtractionEvent.Redirect(it)) }
            // The transport is injectable; independently check final identity before parsing.
            if (!DouyinUrls.isAllowed(page.finalUrl) || DouyinUrls.normalize(page.finalUrl) == null ||
                (DouyinUrls.videoId(page.finalUrl) != null && DouyinUrls.videoId(page.finalUrl) != targetId)
            ) {
                observer(ExtractionEvent.Page(pageUrl, page.status, null, "页面跳转目标不安全或作品 ID 不一致。"))
                lastError = ExtractError.RedirectFailed
                continue
            }
            val type = page.contentType?.substringBefore(';')?.trim()?.lowercase()
            val statusError = when (page.status) {
                401 -> ExtractError.LoginRequired
                404, 410 -> ExtractError.VideoNotFound
                in 200..299 -> null
                else -> ExtractError.HttpError(page.status)
            }
            if (statusError != null) {
                observer(ExtractionEvent.Page(page.finalUrl, page.status, type, "页面返回 HTTP ${page.status}。"))
                if (statusError == ExtractError.LoginRequired) return Result.failure(statusError)
                lastError = statusError
                continue
            }
            if (page.body.isBlank() || (type != null && type !in HTML_TYPES)) {
                observer(ExtractionEvent.Page(page.finalUrl, page.status, type, "页面为空或响应类型不是 HTML。"))
                lastError = ExtractError.UnsupportedPage
                continue
            }
            observer(ExtractionEvent.Page(page.finalUrl, page.status, type, "已读取公开页面，开始检查目标视频信息。"))
            val parsed = withContext(Dispatchers.Default) { parser.parse(page.body, page.finalUrl, targetId) }
            currentCoroutineContext().ensureActive()
            parsed.attempts.forEach { observer(ExtractionEvent.Parser(page.finalUrl, it)) }
            val media = parsed.media
            if (media != null && media.id == targetId && media.formats.isNotEmpty()) return Result.success(media)
            val blocker = withContext(Dispatchers.Default) { visibleBlocker(page.body) }
            if (blocker != null) {
                observer(ExtractionEvent.Page(page.finalUrl, page.status, type, "页面显示明确的访问限制提示。"))
                return Result.failure(blocker)
            }
            lastError = ExtractError.UnsupportedPage
        }
        return Result.failure(lastError)
    }

    /** Script names and ordinary login buttons are not evidence of a blocking challenge. */
    private fun visibleBlocker(html: String): ExtractError? {
        val document = Jsoup.parse(html)
        document.select("script, style, template, noscript, [hidden], [aria-hidden=true]").remove()
        document.select("[style]").filter {
            Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(it.attr("style"))
        }.forEach { it.remove() }
        for (element in document.select("main, [role=main], form")) {
            val text = element.text()
            if (text.length > 180 || element.select("video, article").isNotEmpty()) continue
            if (isBlockingMessage(text, VERIFY_MESSAGES)) return ExtractError.VerificationRequired
            if (isBlockingMessage(text, LOGIN_MESSAGES)) return ExtractError.LoginRequired
        }
        return null
    }

    private fun isBlockingMessage(text: String, messages: List<String>): Boolean = messages.any { message ->
        Regex("^(?:安全验证\\s*|抖音\\s*)?${Regex.escape(message)}(?:[\\s，。！!].*)?$").matches(text)
    }

    companion object {
        private val HTML_TYPES = setOf("text/html", "application/xhtml+xml")
        private val VERIFY_MESSAGES = listOf("请先完成安全验证", "请完成下方验证", "请拖动滑块完成验证", "验证通过后继续访问")
        private val LOGIN_MESSAGES = listOf("登录后才能查看此视频", "请登录后查看该作品", "登录后才能访问此页面")
    }
}
