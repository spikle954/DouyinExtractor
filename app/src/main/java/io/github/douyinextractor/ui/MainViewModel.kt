package io.github.douyinextractor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.douyinextractor.browser.BrowserRequest
import io.github.douyinextractor.core.browser.BrowserSnapshotResult
import io.github.douyinextractor.core.browser.BrowserRequestTrace
import io.github.douyinextractor.core.browser.BrowserUrlPolicy
import io.github.douyinextractor.core.browser.KuaishouBrowserPolicy
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.core.diagnostics.DiagnosticReport
import io.github.douyinextractor.core.diagnostics.BrowserPageEvent
import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.extractor.PlatformVideoService
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.MediaFormatSelector
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.model.ResolvedVideoLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class HomeState(
    val input: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    val isError: Boolean = false,
    val resolved: ResolvedVideoLink? = null,
    val media: MediaInfo? = null,
    val selectedFormat: Int = 0,
    val diagnostics: String = "",
    val browserAvailable: Boolean = false,
    val browserRequest: BrowserRequest? = null,
)

class MainViewModel(
    private val resolveLink: suspend (String, ResolutionDiagnostics) -> Result<ResolvedVideoLink> = PlatformVideoService()::resolve,
    private val extractMedia: suspend (String, ResolutionDiagnostics) -> Result<MediaInfo> = PlatformVideoService()::extract,
    autoBrowserInitiallyEnabled: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeState())
    val state = mutableState.asStateFlow()
    private val mutableAutoBrowserEnabled = MutableStateFlow(autoBrowserInitiallyEnabled)
    val autoBrowserEnabled = mutableAutoBrowserEnabled.asStateFlow()
    var initialIntentHandled = false
    private var request: Job? = null
    private var revision = 0L
    private var context: ExtractionContext? = null
    private var lastBrowserSignature: String? = null

    private data class ExtractionContext(
        val text: String, val url: String, val resolved: ResolvedVideoLink,
        val trace: ResolutionDiagnostics, val error: ExtractError?,
    )

    fun setAutoBrowserEnabled(enabled: Boolean) {
        mutableAutoBrowserEnabled.value = enabled
    }

    fun startBrowser() {
        val active = context ?: return
        if (!state.value.browserAvailable || state.value.busy || state.value.browserRequest != null) return
        revision++
        request?.cancel()
        request = null
        openBrowser(active, automatic = false)
    }

    /** Automatic handoff runs inside the HTTP job and must not cancel that same job. */
    private fun openBrowser(active: ExtractionContext, automatic: Boolean) {
        lastBrowserSignature = null
        context = active.copy(error = null)
        active.trace.clearBrowser()
        active.trace.note(if (automatic) "普通页面尚未取得媒体，按自动网页设置继续提取；未导入登录信息。"
            else "用户手动打开本机网页提取；未导入登录信息。")
        mutableState.update { it.copy(
            input = active.text, resolved = active.resolved, busy = false, browserAvailable = true,
            media = null, selectedFormat = 0,
            browserRequest = BrowserRequest(revision, if (active.resolved.platform == SocialUrls.KUAISHOU) KuaishouBrowserPolicy.sharePage(active.resolved.awemeId, active.resolved.finalUrl)
                else DouyinUrls.canonical(active.resolved.awemeId), active.resolved.awemeId, active.resolved.platform),
            message = if (automatic) "正在自动进入网页提取，请稍候…" else "正在打开${platformLabel(active.resolved.platform)}公开页面，加载后将再次检查视频信息…",
            isError = false,
            diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, null, active.trace),
        ) }
    }

    fun receiveBrowserPage(sessionId: Long, event: BrowserPageEvent, url: String) {
        val active = context ?: return
        if (sessionId != revision || state.value.browserRequest?.sessionId != sessionId ||
            !browserPageMatches(url, active.resolved)) return
        active.trace.browserPage(event, url)
        if (event != BrowserPageEvent.FINISHED) {
            context = active.copy(error = null)
            lastBrowserSignature = null
        }
        mutableState.update { it.copy(
            message = if (event == BrowserPageEvent.FINISHED) it.message else "正在加载当前作品的网页…",
            isError = if (event == BrowserPageEvent.FINISHED) it.isError else false,
            diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved,
                if (event == BrowserPageEvent.FINISHED) active.error else null, active.trace, it.media),
        ) }
    }

    fun receiveBrowserNetwork(sessionId: Long, trace: BrowserRequestTrace) {
        val active = context ?: return
        if (sessionId != revision || state.value.browserRequest?.sessionId != sessionId) return
        active.trace.browserNetwork(trace)
        mutableState.update { it.copy(
            diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, active.error, active.trace, it.media),
        ) }
    }

    fun receiveBrowserSnapshot(sessionId: Long, result: BrowserSnapshotResult) {
        val active = context ?: return
        if (sessionId != revision || state.value.browserRequest?.sessionId != sessionId) return
        val pageUrl = result.pageUrl
        if (pageUrl == null || !browserPageMatches(pageUrl, active.resolved)) {
            val reason = if (pageUrl == null) result.reason else "当前网页地址不是目标作品，未接受网页结果。"
            if (reason != lastBrowserSignature) {
                lastBrowserSignature = reason
                active.trace.browserSnapshot(null, reason, emptyList())
                mutableState.update { it.copy(diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, ExtractError.UnsupportedPage, active.trace)) }
            }
            return
        }
        val signature = "${if (active.resolved.platform == SocialUrls.KUAISHOU) KuaishouBrowserPolicy.safeUrl(pageUrl) else DouyinUrls.sanitizeForDiagnostics(pageUrl)}|${result.reason}|${result.attempts}|${result.media?.formats?.size}"
        if (signature != lastBrowserSignature) {
            lastBrowserSignature = signature
            active.trace.browserSnapshot(pageUrl, result.reason, result.attempts)
        }
        val media = result.media?.takeIf { it.platform.equals(active.resolved.platform, ignoreCase = true) && it.id == active.resolved.awemeId && it.formats.isNotEmpty() }
        if (media != null) {
            context = active.copy(error = null)
            mutableState.update { it.copy(
                media = media, browserRequest = null, browserAvailable = false, isError = false,
                selectedFormat = media.formats.indexOf(MediaFormatSelector.recommended(media.formats)).coerceAtLeast(0),
                message = "已从网页取得视频信息，找到 ${media.formats.size} 个资源，可以选择并下载。",
                diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, null, active.trace, media),
            ) }
        } else {
            val error = result.error ?: ExtractError.UnsupportedPage
            context = active.copy(error = error)
            mutableState.update { it.copy(
                message = when (error) {
                    ExtractError.VerificationRequired -> "抖音要求进行浏览器验证，请在页面中正常完成后再检查。"
                    ExtractError.KuaishouVerificationRequired -> "快手要求进行浏览器验证，请在页面中正常完成后再检查。"
                    ExtractError.LoginRequired -> "此页面要求登录，当前仅支持公开内容，请关闭网页。"
                    ExtractError.WebPlaybackUnavailable -> error.userMessage
                    else -> "网页已打开，尚未取得视频资源。可等待加载或尝试页面中的播放按钮。"
                },
                isError = error != ExtractError.UnsupportedPage,
                diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, error, active.trace),
            ) }
        }
    }

    fun finishBrowser(sessionId: Long, reason: String) {
        val active = context ?: return
        if (sessionId != revision || state.value.browserRequest?.sessionId != sessionId) return
        active.trace.note("本机网页结束：$reason")
        mutableState.update { it.copy(
            browserRequest = null,
            message = if (active.error == ExtractError.WebPlaybackUnavailable) ExtractError.WebPlaybackUnavailable.userMessage else reason,
            isError = it.media == null,
            diagnostics = DiagnosticReport.format(active.text, active.url, active.resolved, active.error, active.trace, it.media),
        ) }
    }

    fun editInput(text: String) {
        invalidateRequest()
        mutableState.value = if (text.length > MAX_INPUT) {
            HomeState(message = "分享内容过长，请只复制一条视频的分享内容", isError = true)
        } else HomeState(input = text)
    }

    fun receiveShare(text: String) {
        editInput(text)
        if (text.length <= MAX_INPUT) parse()
    }

    fun selectFormat(index: Int) {
        mutableState.update { if (index in (it.media?.formats?.indices ?: IntRange.EMPTY)) it.copy(selectedFormat = index) else it }
    }

    fun selectPage(number: Int) {
        val media = state.value.media ?: return
        if (media.platform == "Bilibili" && number != media.selectedPage && media.pages.any { it.number == number })
            receiveShare(BilibiliUrls.canonical(media.id, number))
    }

    fun cancel() {
        invalidateRequest()
        mutableState.update { it.copy(busy = false, message = "已取消解析", isError = false, browserRequest = null, browserAvailable = false) }
    }

    private fun invalidateRequest() {
        revision++
        request?.cancel()
        request = null
        context = null
        lastBrowserSignature = null
    }

    fun parse() {
        invalidateRequest()
        val current = revision
        val text = state.value.input
        val trace = ResolutionDiagnostics()
        val parsed = ShareTextParser().parse(text).getOrElse {
            val error = it as? ExtractError ?: ExtractError.InvalidUrl
            mutableState.value = HomeState(
                input = text,
                message = if (text.isBlank()) "请先粘贴视频分享链接" else error.userMessage,
                isError = true,
                diagnostics = DiagnosticReport.format(text, null, null, error, trace),
            )
            return
        }
        mutableState.value = HomeState(input = text, busy = true, message = "正在识别视频链接…")
        request = viewModelScope.launch {
            var resolved: ResolvedVideoLink? = null
            try {
                withTimeout(60_000) {
                    resolved = withTimeout(25_000) { resolveLink(parsed.url, trace).getOrThrow() }
                    if (current != revision) return@withTimeout
                    mutableState.update { it.copy(resolved = resolved, message = "链接已识别，正在提取视频信息…") }
                    val media = extractMedia(resolved!!.extractionUrl ?: resolved!!.canonicalUrl, trace).getOrThrow()
                    if (current != revision) return@withTimeout
                    if (media.id != resolved!!.awemeId || media.formats.isEmpty()) throw ExtractError.UnsupportedPage
                    mutableState.value = HomeState(
                        input = text, resolved = resolved, media = media,
                        selectedFormat = media.formats.indexOf(MediaFormatSelector.recommended(media.formats)).coerceAtLeast(0),
                        message = "视频信息已提取，找到 ${media.formats.size} 个资源，可以选择并下载。",
                        diagnostics = DiagnosticReport.format(text, parsed.url, resolved, null, trace, media),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                trace.note("解析已达到时间上限（链接识别最多 25 秒，整个流程最多 60 秒），已取消网络请求。")
                failIfCurrent(current, text, parsed.url, resolved, trace, ExtractError.PageLoadFailed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExtractError) {
                failIfCurrent(current, text, parsed.url, resolved, trace, error)
            } catch (_: Exception) {
                trace.note("解析发生未预期错误；未记录异常中的 URL 或凭据。")
                failIfCurrent(current, text, parsed.url, resolved, trace, ExtractError.PageLoadFailed)
            } finally {
                if (current == revision) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun failIfCurrent(current: Long, text: String, url: String, resolved: ResolvedVideoLink?, trace: ResolutionDiagnostics, error: ExtractError) {
        if (current == revision) {
            context = resolved?.let { ExtractionContext(text, url, it, trace, error) }
            val canContinueAutomatically = error == ExtractError.UnsupportedPage ||
                error == ExtractError.VerificationRequired || error == ExtractError.KuaishouVerificationRequired || error is ExtractError.ParserError
            if (resolved != null && resolved.platform in setOf("Douyin", SocialUrls.KUAISHOU) && canContinueAutomatically && autoBrowserEnabled.value) {
                trace.note("普通页面步骤：${error.javaClass.simpleName}；继续网页提取，尚无媒体结果。")
                openBrowser(context!!, automatic = true)
                return
            }
            mutableState.value = HomeState(
                input = text, resolved = resolved,
                message = if (resolved != null && resolved.platform in setOf("Douyin", SocialUrls.KUAISHOU) && error == ExtractError.UnsupportedPage)
                    "链接已识别，普通请求未取得视频。请点击「用网页继续提取」。" else error.userMessage,
                isError = true,
                browserAvailable = resolved != null && (resolved.platform == "Douyin" && error != ExtractError.InvalidUrl && error != ExtractError.VideoNotFound && error != ExtractError.LoginRequired ||
                    resolved.platform == SocialUrls.KUAISHOU && (canContinueAutomatically || error == ExtractError.PageLoadFailed)),
                diagnostics = DiagnosticReport.format(text, url, resolved, error, trace),
            )
        }
    }

    private fun browserPageMatches(url: String, link: ResolvedVideoLink) = if (link.platform == SocialUrls.KUAISHOU) KuaishouBrowserPolicy.navigation(url, link.awemeId)
        else link.platform == "Douyin" && BrowserUrlPolicy.navigation(url) && DouyinUrls.videoId(url) == link.awemeId
    private fun platformLabel(platform: String) = if (platform == SocialUrls.KUAISHOU) "快手" else "抖音"

    companion object { const val MAX_INPUT = 16_384 }
}
