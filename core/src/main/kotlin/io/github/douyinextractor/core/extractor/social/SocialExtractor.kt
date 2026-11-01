package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.extractor.VideoExtractor
import io.github.douyinextractor.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SocialExtractor(private val transport: SocialTransport = SocialHttpClient()) : VideoExtractor {
    override fun canHandle(url: String) = SocialUrls.normalize(url) != null
    suspend fun resolve(url: String, trace: ResolutionDiagnostics): Result<ResolvedVideoLink> = attempt(trace) {
        val original = SocialUrls.normalize(url) ?: throw ExtractError.InvalidUrl
        val platform = SocialUrls.platform(original) ?: throw ExtractError.InvalidUrl
        var current = original
        val seen = mutableSetOf<String>(); val steps = mutableListOf<RedirectStep>()
        var retried = false
        repeat(7) {
            currentCoroutineContext().ensureActive()
            val id = SocialUrls.videoId(current)
            if (id != null) {
                trace.note("已识别${SocialUrls.label(platform)}作品编号，将读取当前公开作品页面；未使用登录信息。")
                val canonical = SocialUrls.canonical(platform, id)
                // Both Kuaishou apps share the same public work. Avoid depending on mobile app-routing parameters.
                val extraction = if (platform == SocialUrls.KUAISHOU &&
                    current.toHttpUrlOrNull()?.host !in setOf("kuaishou.com", "www.kuaishou.com")) canonical else current
                return@attempt ResolvedVideoLink(original, canonical, id, current, steps, platform, extraction)
            }
            if (!SocialUrls.pageAllowed(current, platform) || !seen.add(current)) throw ExtractError.RedirectFailed
            val response = try { request(current, platform, trace, locating = true) }
                catch (failure: SocialRequestFailure) {
                    if (platform != SocialUrls.KUAISHOU || retried || !failure.canRetryConnection) throw failure
                    retried = true
                    trace.note("快手短链连接未完成，在同一官方入口重试一次；未收到拒绝访问响应。")
                    request(current, platform, trace, locating = true)
                }
            val next = response.location?.let { current.toHttpUrlOrNull()?.resolve(it)?.toString() }
            val step = RedirectStep(current, response.status, next, response.contentType)
            steps += step; trace.record(step)
            if (response.status !in REDIRECTS) { checkHttp(response.status); throw ExtractError.RedirectFailed }
            if (next == null || !SocialUrls.pageAllowed(next, platform)) throw redirectError(next, platform)
            current = SocialUrls.normalize(next) ?: throw ExtractError.RedirectFailed
        }
        throw ExtractError.RedirectFailed
    }
    override suspend fun extract(url: String) = extract(url, ResolutionDiagnostics())
    suspend fun extract(url: String, trace: ResolutionDiagnostics): Result<MediaInfo> = attempt(trace) {
        val platform = SocialUrls.platform(url) ?: throw ExtractError.InvalidUrl
        val id = SocialUrls.videoId(url) ?: throw ExtractError.InvalidUrl
        val normalized = SocialUrls.normalize(url) ?: throw ExtractError.InvalidUrl
        val pages = if (platform == SocialUrls.KUAISHOU)
            listOf(if (normalized.toHttpUrlOrNull()?.host in setOf("kuaishou.com", "www.kuaishou.com")) normalized
                else SocialUrls.canonical(platform, id), SocialUrls.kuaishouMobilePage(id)) else listOf(normalized)
        var requests = 0
        for ((index, page) in pages.withIndex()) {
            try {
                return@attempt extractPage(page, platform, id, trace) {
                    if (++requests > 7) throw ExtractError.RedirectFailed
                }
            } catch (error: Exception) {
                val mayContinue = error == ExtractError.UnsupportedPage || error is SocialRequestFailure && error.canTryOtherPage
                if (index == pages.lastIndex || !mayContinue) throw error
                trace.note("快手电脑版页面未取得目标媒体，尝试同一作品的官方手机分享入口一次；尚未提取成功。")
            }
        }
        throw ExtractError.UnsupportedPage
    }
    private suspend fun extractPage(page: String, platform: String, id: String, trace: ResolutionDiagnostics, beforeRequest: () -> Unit): MediaInfo {
        var current = page
        val seen = mutableSetOf<String>()
        repeat(7) {
            currentCoroutineContext().ensureActive()
            if (!SocialUrls.pageAllowed(current, platform) || !seen.add(current)) throw ExtractError.RedirectFailed
            beforeRequest()
            val response = request(current, platform, trace)
            if (response.status in REDIRECTS) {
                val next = response.location?.let { current.toHttpUrlOrNull()?.resolve(it)?.toString() }
                if (next == null || !SocialUrls.pageAllowed(next, platform)) throw redirectError(next, platform)
                if (SocialUrls.videoId(next)?.let { it != id } == true) {
                    trace.note("网页跳转指向其他作品，已停止；不会将推荐视频作为当前结果。")
                    throw TargetMismatch()
                }
                current = SocialUrls.normalize(next) ?: throw ExtractError.RedirectFailed
            } else {
                checkHttp(response.status)
                if (platform == SocialUrls.KUAISHOU && current.toHttpUrlOrNull()?.queryParameter("location") == SocialUrls.KUAISHOU_SHARE_LOCATION)
                    trace.note("快手作品页面：已保留官方分享路由标记；未保留用户标识或跟踪字段。")
                if (response.body.length > SocialHttpClient.MAX_BYTES || response.contentType?.substringBefore(';')?.lowercase()?.let { it !in setOf("text/html", "application/xhtml+xml") } == true) throw ExtractError.UnsupportedPage
                val media = withContext(Dispatchers.Default) { SocialPageMapping.parse(response.body, platform, id, trace::note) }
                trace.note("找到 ${media.formats.size} 个目标作品的公开 MP4 候选；是否可用及实际尺寸需下载验证。")
                return media
            }
        }
        throw ExtractError.RedirectFailed
    }
    private suspend fun request(url: String, platform: String, trace: ResolutionDiagnostics, locating: Boolean = false): SocialResponse {
        val stage = if (locating) "短链定位" else "页面提取"
        // These are validated public page URLs; never print path/query or exception messages here.
        val host = url.toHttpUrlOrNull()?.host ?: "未确认"
        val started = System.nanoTime()
        try {
            val response = if (locating && platform == SocialUrls.KUAISHOU) withTimeoutOrNull(10_000) { transport.fetch(url, platform) }
                ?: throw SocialRequestFailure(SocialRequestFailure.Kind.TIMEOUT) else transport.fetch(url, platform)
            val type = when (response.contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "text/html" -> "HTML"; "application/xhtml+xml" -> "XHTML"; "application/json" -> "JSON"; null -> "未提供"; else -> "其他类型"
            }
            trace.note("${SocialUrls.label(platform)}$stage：主机 $host；HTTP ${response.status}；类型 $type；已读取 ${response.body.length} 字符。")
            return response
        } catch (error: IOException) {
            val failure = SocialRequestFailure.classify(error)
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
            trace.note("${SocialUrls.label(platform)}${stage}失败：主机 $host；${failure.kind.label}（${failure.kind.name}）；HTTP ${failure.status ?: "未确认状态"}；耗时 ${elapsed} ms；未记录异常内容。")
            throw failure
        }
    }
    private fun redirectError(next: String?, platform: String): ExtractError {
        val url = next?.toHttpUrlOrNull()
        if (url != null && url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && SocialUrls.platform(next) == platform) {
            if (url.encodedPath.startsWith("/404") || url.encodedPath.startsWith("/error")) return ExtractError.ShareUnavailable
            if (url.encodedPath.startsWith("/login")) return ExtractError.LoginRequired
        }
        return ExtractError.RedirectFailed
    }
    private fun checkHttp(status: Int) {
        when (status) { 200 -> Unit; 401 -> throw ExtractError.LoginRequired; 404, 410 -> throw ExtractError.VideoNotFound; 403, 429 -> throw ExtractError.PlatformRejected; else -> throw ExtractError.HttpError(status) }
    }
    private class TargetMismatch : Exception()
    private suspend fun <T> attempt(trace: ResolutionDiagnostics, block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: ExtractError) { Result.failure(e) }
        catch (_: TargetMismatch) { Result.failure(ExtractError.UnsupportedPage) }
        catch (e: SocialRequestFailure) { Result.failure(if (e.kind in setOf(SocialRequestFailure.Kind.TOO_LARGE, SocialRequestFailure.Kind.MISSING_BODY)) ExtractError.UnsupportedPage else ExtractError.PageLoadFailed) }
        catch (_: IllegalArgumentException) { Result.failure(ExtractError.UnsupportedPage) }
        catch (_: Exception) {
            trace.note("处理发生未预期错误；未记录异常中的页面内容或凭据。")
            Result.failure(ExtractError.PageLoadFailed)
        }
    private companion object { val REDIRECTS = setOf(301, 302, 303, 307, 308) }
}
