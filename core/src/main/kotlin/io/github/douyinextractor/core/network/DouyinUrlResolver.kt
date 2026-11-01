package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.RedirectStep
import io.github.douyinextractor.core.model.ResolvedVideoLink
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolves an identifier, not video metadata, availability, or a downloadable media URL. */
class DouyinUrlResolver(private val transport: DouyinTransport = DouyinHttpClient()) {
    suspend fun resolve(
        url: String,
        diagnostics: ResolutionDiagnostics = ResolutionDiagnostics(),
    ): Result<ResolvedVideoLink> {
        currentCoroutineContext().ensureActive()
        val original = DouyinUrls.normalize(url) ?: return Result.failure(ExtractError.InvalidUrl)
        if (!DouyinUrls.isAllowed(original)) return Result.failure(ExtractError.InvalidUrl)
        DouyinUrls.videoId(original)?.let { return success(original, original, it, diagnostics) }
        if (!DouyinUrls.isShortLink(original)) return Result.failure(ExtractError.UnsupportedPage)

        val visited = mutableSetOf<String>()
        var current = original
        var followedRedirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            // Check at the request boundary as well as when interpreting Location.
            if (!DouyinUrls.isAllowed(current) || !visited.add(current)) {
                diagnostics.note("跳转地址不允许或出现循环。")
                return Result.failure(ExtractError.RedirectFailed)
            }
            val response = try {
                transport.fetch(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                diagnostics.note("网络请求未完成，可检查网络后重试。")
                return Result.failure(ExtractError.PageLoadFailed)
            }
            currentCoroutineContext().ensureActive()
            val resolvedLocation = response.location?.let { redirectTarget(current, it) }
            diagnostics.record(
                RedirectStep(
                    url = DouyinUrls.sanitizeForDiagnostics(current),
                    status = response.status,
                    location = response.location?.let {
                        DouyinUrls.sanitizeForDiagnostics(resolvedLocation ?: it)
                    },
                    contentType = response.contentType?.substringBefore(';')?.take(120),
                ),
            )

            when {
                response.status in REDIRECT_CODES -> {
                    if (followedRedirects >= MAX_REDIRECTS) {
                        diagnostics.note("跳转次数超过上限。")
                        return Result.failure(ExtractError.RedirectFailed)
                    }
                    val target = resolvedLocation
                    if (target == null || target in visited) {
                        diagnostics.note("跳转地址缺失、不安全或出现循环。")
                        return Result.failure(ExtractError.RedirectFailed)
                    }
                    DouyinUrls.videoId(target)?.let { return success(original, target, it, diagnostics) }
                    current = target
                    followedRedirects++
                }
                response.status == 401 -> return Result.failure(ExtractError.LoginRequired)
                response.status == 404 || response.status == 410 -> return Result.failure(ExtractError.VideoNotFound)
                response.status !in 200..299 -> return Result.failure(ExtractError.HttpError(response.status))
                else -> {
                    diagnostics.note("页面未跳转到普通视频地址；本阶段未解析页面内容。")
                    return Result.failure(ExtractError.UnsupportedPage)
                }
            }
        }
    }

    private fun redirectTarget(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return try {
            if (location.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
            val referenceText = location.trim()
            val reference = URI(referenceText)
            if (reference.rawUserInfo != null) return null
            // Check the original authority spelling before HttpUrl canonicalizes it. In
            // particular, credentials, encoded hosts, and ambiguous /// authorities cannot
            // disappear during URL resolution and thereby pass the host allowlist.
            if (reference.isAbsolute &&
                (!reference.scheme.equals("https", ignoreCase = true) || reference.rawAuthority == null)
            ) return null
            if (referenceText.startsWith("//") && reference.rawAuthority == null) return null
            if (reference.rawAuthority != null) {
                val explicitTarget = if (reference.isAbsolute) referenceText else "https:$referenceText"
                if (!DouyinUrls.isAllowed(explicitTarget)) return null
            }
            // HttpUrl has browser URL semantics: ?token=x keeps the entire current path.
            // java.net.URI.resolve incorrectly drops the final path segment for this case.
            val target = current.toHttpUrlOrNull()?.resolve(referenceText) ?: return null
            val rawTarget = target.toString()
            if (!DouyinUrls.isAllowed(rawTarget)) return null
            // Validate identity, but keep all server-supplied query state for the next request.
            // A field commonly used for tracking may also be needed by an intermediate hop.
            DouyinUrls.normalize(rawTarget) ?: return null
            target.newBuilder().fragment(null).build().toString()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    private fun success(
        original: String,
        finalUrl: String,
        id: String,
        diagnostics: ResolutionDiagnostics,
    ): Result<ResolvedVideoLink> {
        diagnostics.note("已识别作品 ID，尚未验证作品可用性或提取媒体。")
        return Result.success(
            ResolvedVideoLink(original, DouyinUrls.canonical(id), id, finalUrl, diagnostics.steps()),
        )
    }

    companion object {
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
