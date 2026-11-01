package io.github.douyinextractor.core.extractor.bilibili

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.extractor.VideoExtractor
import io.github.douyinextractor.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BilibiliExtractor(private val transport: BilibiliTransport = BilibiliHttpClient()) : VideoExtractor {
    override fun canHandle(url: String) = BilibiliUrls.normalize(url) != null

    suspend fun resolve(url: String, trace: ResolutionDiagnostics): Result<ResolvedVideoLink> = attempt {
        val original = BilibiliUrls.normalize(url) ?: throw ExtractError.InvalidUrl
        var current = original
        val seen = mutableSetOf<String>()
        val steps = mutableListOf<RedirectStep>()
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val id = BilibiliUrls.videoId(current)
            if (id != null) {
                trace.note("已识别哔哩哔哩视频编号；将读取公开信息及当前分 P 的 MP4 资源。")
                return@attempt ResolvedVideoLink(original, BilibiliUrls.canonical(id, BilibiliUrls.page(current)), id, current, steps, "Bilibili")
            }
            if (!BilibiliUrls.short(current) || !seen.add(current)) throw ExtractError.RedirectFailed
            val response = transport.fetch(current, false)
            val next = response.location?.let { current.toHttpUrlOrNull()?.resolve(it)?.toString() }?.let(BilibiliUrls::normalize)
            val step = RedirectStep(current.substringBefore('?'), response.status, next?.substringBefore('?'), null)
            steps += step; trace.record(step)
            if (response.status !in setOf(301, 302, 303, 307, 308)) {
                checkHttp(response.status)
                throw ExtractError.RedirectFailed
            }
            current = next ?: throw ExtractError.RedirectFailed
        }
        throw ExtractError.RedirectFailed
    }

    override suspend fun extract(url: String): Result<MediaInfo> = extract(url, ResolutionDiagnostics())
    suspend fun extract(url: String, trace: ResolutionDiagnostics): Result<MediaInfo> = attempt {
        val target = BilibiliUrls.videoId(url) ?: throw ExtractError.InvalidUrl
        val part = BilibiliUrls.page(url)
        val identity = if (target.startsWith("BV")) "bvid=$target" else "aid=${target.removePrefix("av")}"
        val view = json("https://api.bilibili.com/x/web-interface/view?$identity", trace, "视频信息")
        val detail = BilibiliMapping.detail(view, target, part)
        val play = json("https://api.bilibili.com/x/player/playurl?bvid=${detail.bvid}&cid=${detail.cid}&qn=80&fnval=1&fnver=0&fourk=0", trace, "当前分 P 的 MP4 播放资源")
        val media = BilibiliMapping.media(detail, play)
        trace.note("哔哩哔哩 P$part / ${media.pages.size}；找到 ${media.formats.size} 个经校验的 MP4 下载地址，尚未下载。")
        media
    }
    private suspend fun json(url: String, trace: ResolutionDiagnostics, label: String): JsonObject {
        currentCoroutineContext().ensureActive()
        val response = transport.fetch(url, true)
        trace.note("哔哩哔哩$label：HTTP ${response.status}；未使用登录 Cookie。")
        checkHttp(response.status)
        if (response.body.length > BilibiliHttpClient.MAX_BYTES) throw ExtractError.UnsupportedPage
        // Bound nesting before parsing untrusted JSON, independent of the network client.
        var depth = 0; var quoted = false; var escape = false
        for (char in response.body) {
            if (quoted) { if (escape) escape = false else if (char == '\\') escape = true else if (char == '"') quoted = false }
            else when (char) { '"' -> quoted = true; '{', '[' -> if (++depth > 64) throw ExtractError.UnsupportedPage; '}', ']' -> depth-- }
        }
        val root = Json.parseToJsonElement(response.body) as? JsonObject ?: throw ExtractError.UnsupportedPage
        return BilibiliMapping.data(root)
    }
    private fun checkHttp(status: Int) {
        when (status) { 200 -> Unit; 401 -> throw ExtractError.LoginRequired; 404, 410 -> throw ExtractError.VideoNotFound; else -> throw ExtractError.HttpError(status) }
    }
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: ExtractError) { Result.failure(e) }
        catch (_: IllegalArgumentException) { Result.failure(ExtractError.UnsupportedPage) }
        catch (_: Exception) { Result.failure(ExtractError.PageLoadFailed) }
}
