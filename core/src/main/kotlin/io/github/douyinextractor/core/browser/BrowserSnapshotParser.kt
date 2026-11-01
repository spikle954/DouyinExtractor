package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.extractor.douyin.mapping.DouyinJsonMapper
import io.github.douyinextractor.core.extractor.douyin.mapping.MediaUrlPolicy
import io.github.douyinextractor.core.extractor.douyin.parser.DouyinPageParser
import io.github.douyinextractor.core.extractor.douyin.parser.JsonDocuments
import io.github.douyinextractor.core.extractor.douyin.parser.JsonLdParser
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import io.github.douyinextractor.core.extractor.douyin.parser.RenderDataParser
import io.github.douyinextractor.core.extractor.douyin.parser.RouterDataParser
import io.github.douyinextractor.core.extractor.douyin.parser.ScriptJsonParser
import io.github.douyinextractor.core.extractor.douyin.parser.SigiStateParser
import io.github.douyinextractor.core.extractor.douyin.parser.mergeMedia
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class BrowserSnapshotResult(
    val media: MediaInfo?,
    val pageUrl: String?,
    val attempts: List<ParserAttempt>,
    val reason: String,
    val error: ExtractError? = null,
)

/** Parses untrusted WebView callback data without evaluating it or retaining raw page snapshots. */
class BrowserSnapshotParser {
    // Runtime <video> needs stricter visibility, intrinsic size and encryption checks below. Do not
    // send those elements through the static HtmlMediaParser and bypass the runtime checks.
    private val pageParser = DouyinPageParser(listOf(
        RouterDataParser(), RenderDataParser(), SigiStateParser(), ScriptJsonParser(), JsonLdParser(),
    ))

    fun parseEvaluationResult(value: String, targetId: String): BrowserSnapshotResult {
        if (!targetId.matches(Regex("[0-9]{1,25}"))) return failed("目标视频编号无效", ExtractError.InvalidUrl)
        val snapshot = try {
            require(value.length <= MAX_CALLBACK_CHARS)
            val outer = parseBounded(value)
            val decoded = if (outer is JsonPrimitive && outer.isString) {
                require(outer.content.length <= MAX_SNAPSHOT_CHARS)
                parseBounded(outer.content)
            } else { require(value.length <= MAX_SNAPSHOT_CHARS); outer }
            require(decoded is JsonObject && decoded.text("version") == "1")
            decoded
        } catch (_: IllegalArgumentException) {
            return failed("浏览器快照格式无效或超过大小限制", ExtractError.ParserError("BrowserSnapshot", "Invalid snapshot"))
        }
        val pageUrl = snapshot.text("pageUrl")
        if (pageUrl == null || !BrowserUrlPolicy.navigation(pageUrl) || DouyinUrls.videoId(pageUrl) != targetId) {
            return failed("浏览器当前页面与目标视频不匹配", ExtractError.UnsupportedPage)
        }
        val safePageUrl = DouyinUrls.sanitizeForDiagnostics(pageUrl)
        val canonical = snapshot.text("canonical")
        if (snapshot.flag("canonicalConflict") || (canonical != null && DouyinUrls.videoId(canonical) != targetId)) {
            return failed("浏览器页面的视频标识存在冲突", ExtractError.UnsupportedPage, safePageUrl)
        }
        if (snapshot.flag("captureFailed")) return failed("浏览器暂未能读取页面数据", ExtractError.UnsupportedPage, safePageUrl)

        val observation = observation(snapshot)
        val attempts = mutableListOf(ParserAttempt("BrowserSnapshot", observation))
        val matches = mutableListOf<MediaInfo>()
        val html = snapshot.text("html")
        if (html != null && html.length <= MAX_HTML_CHARS) {
            val parsed = pageParser.parse(html, pageUrl, targetId)
            matches += listOfNotNull(parsed.media)
            attempts += parsed.attempts
        } else attempts += ParserAttempt("BrowserHtml", if (snapshot.flag("htmlOmitted") || html != null)
            "页面 HTML 超过大小限制，继续检查局部数据" else "未读取到页面 HTML")

        var stateBudget = MAX_STATE_CHARS
        val states = (snapshot["states"] as? JsonArray).orEmpty()
        for (state in states.take(8).filterIsInstance<JsonObject>()) {
            val name = state.text("name")?.takeIf { it in STATE_NAMES } ?: continue
            val raw = state.text("json") ?: continue
            if (raw.length > stateBudget) {
                attempts += ParserAttempt("BrowserState:$name", "页面状态超过大小限制")
                continue
            }
            stateBudget -= raw.length
            try {
                val json = if (name == "RENDER_DATA" && raw.trimStart().startsWith('%')) JsonDocuments.percentDecoded(raw) else raw
                val media = DouyinJsonMapper.map(JsonDocuments.parse(json), targetId, "BrowserState:$name")
                matches += listOfNotNull(media)
                attempts += ParserAttempt("BrowserState:$name", if (media == null)
                    "页面状态未提供目标视频的媒体资源" else "找到目标视频的页面状态数据", media?.formats?.size ?: 0)
            } catch (_: IllegalArgumentException) {
                attempts += ParserAttempt("BrowserState:$name", "页面状态数据无法解析")
            }
        }

        val videos = snapshot["videos"] as? JsonArray ?: JsonArray(emptyList())
        val runtime = runtimeMedia(snapshot, videos, targetId)
        matches += listOfNotNull(runtime)
        attempts += ParserAttempt("BrowserVideo", when {
            runtime != null -> "找到与目标页面绑定的已加载 MP4 视频"
            videos.any { (it as? JsonObject)?.text("currentSrc")?.startsWith("blob:") == true } ->
                "页面使用 blob 播放地址，无法作为 MP4 文件资源"
            videos.size > 1 -> "页面包含多个视频或没有可确认的目标视频资源"
            else -> "尚未找到已加载且可确认的目标 MP4 视频"
        }, runtime?.formats?.size ?: 0)
        if (matches.isNotEmpty()) return BrowserSnapshotResult(mergeMedia(matches), safePageUrl, attempts,
            "已从本机浏览器页面提取视频信息；尚未下载文件。$observation")

        val blocker = snapshot["blocker"] as? JsonObject
        val error = when {
            blocker?.flag("verification") == true -> ExtractError.VerificationRequired
            blocker?.flag("login") == true -> ExtractError.LoginRequired
            blocker?.flag("webPlaybackUnavailable") == true -> ExtractError.WebPlaybackUnavailable
            else -> ExtractError.UnsupportedPage
        }
        val reason = when (error) {
            ExtractError.VerificationRequired -> "当前页面显示验证提示，请在浏览器中手动完成后重新提取"
            ExtractError.LoginRequired -> "当前页面要求登录，本版本不处理登录内容"
            ExtractError.WebPlaybackUnavailable -> "抖音网页显示“抱歉出错了／请尝试在抖音内观看”，当前网页未提供视频资源"
            else -> "本机浏览器页面暂未提供目标视频的可识别媒体资源"
        }
        return BrowserSnapshotResult(null, safePageUrl, attempts, "$reason。$observation", error)
    }

    private fun runtimeMedia(snapshot: JsonObject, videos: JsonArray, targetId: String): MediaInfo? {
        if (videos.size > 32 || snapshot.number("videoCount")?.let { it > 32 || it < videos.size } == true) return null
        val visible = videos.filterIsInstance<JsonObject>().filter { it.flag("visible") }
        val singleDocumentVideo = videos.size == 1 && snapshot.number("videoCount") == 1L
        val candidates = visible.filter { video ->
            val explicitId = video.text("targetId")
            !video.flag("identityConflict") && !video.flag("encrypted") &&
                (explicitId == targetId || (explicitId == null && singleDocumentVideo))
        }
        val accepted = candidates.mapNotNull { video ->
            val width = video.number("width")?.takeIf { it in 1..32_768 }?.toInt() ?: return@mapNotNull null
            val height = video.number("height")?.takeIf { it in 1..32_768 }?.toInt() ?: return@mapNotNull null
            if ((video.number("readyState") ?: 0) !in 1..4) return@mapNotNull null
            val url = video.text("currentSrc")?.let(MediaUrlPolicy::media) ?: return@mapNotNull null
            val parsedUrl = url.toHttpUrlOrNull() ?: return@mapNotNull null
            val declaredMime = video.text("mimeType")?.substringBefore(';')?.trim()?.lowercase()
            if (declaredMime != null && declaredMime !in setOf("video/mp4", "video/x-m4v", "application/mp4")) return@mapNotNull null
            val mp4Path = parsedUrl.encodedPath.lowercase().let { it.endsWith(".mp4") || it.endsWith(".m4v") }
            val mp4Query = listOf("mime_type", "mimeType", "mime", "format").any { key ->
                parsedUrl.queryParameterValues(key).any { it?.lowercase() in setOf("video_mp4", "video/mp4", "mp4") }
            }
            if (!mp4Path && !mp4Query && declaredMime == null) return@mapNotNull null
            val durationMs = video.text("duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 604_800 }
                ?.let { (it * 1000).toLong() }
            MediaInfo("Douyin", targetId, snapshot.text("title")?.take(4096), null,
                video.text("poster")?.let(MediaUrlPolicy::cover), durationMs,
                listOf(MediaFormat(url, width, height, null, null, "video/mp4", null, "BrowserVideo:currentSrc")))
        }
        return accepted.takeIf { it.isNotEmpty() }?.let(::mergeMedia)
    }

    private fun parseBounded(text: String): JsonElement {
        require(text.length <= MAX_CALLBACK_CHARS)
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        return Json.parseToJsonElement(text)
    }

    private fun observation(snapshot: JsonObject): String {
        val state = snapshot.text("readyState")?.takeIf { it in setOf("loading", "interactive", "complete") } ?: "未知"
        val html = when {
            snapshot.flag("htmlOmitted") -> "超限已省略"
            snapshot.text("html") == null -> "无"
            else -> "已读取"
        }
        val stateCount = (snapshot["states"] as? JsonArray)?.size?.coerceAtMost(8) ?: 0
        val visible = (snapshot["videos"] as? JsonArray).orEmpty().take(32).filterIsInstance<JsonObject>().filter { it.flag("visible") }
        val blobCount = visible.count { it.text("currentSrc")?.startsWith("blob:") == true }
        val httpsCount = visible.count { it.text("currentSrc")?.startsWith("https:") == true }
        return "文档：$state；HTML：$html；状态块：$stateCount；可见视频：${visible.size}（HTTPS：$httpsCount，blob：$blobCount）"
    }

    private fun failed(reason: String, error: ExtractError, pageUrl: String? = null) =
        BrowserSnapshotResult(null, pageUrl, listOf(ParserAttempt("BrowserSnapshot", reason)), reason, error)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.flag(key: String): Boolean = text(key) == "true"
    private fun JsonObject.number(key: String): Long? = text(key)?.toLongOrNull()

    companion object {
        private const val MAX_CALLBACK_CHARS = 8 * 1024 * 1024
        private const val MAX_SNAPSHOT_CHARS = 3 * 1024 * 1024
        private const val MAX_HTML_CHARS = 1024 * 1024
        private const val MAX_STATE_CHARS = 512 * 1024
        private val STATE_NAMES = setOf("_ROUTER_DATA", "RENDER_DATA", "SIGI_STATE", "__INITIAL_STATE__",
            "__PRELOADED_STATE__", "__SSR_DATA__", "__APOLLO_STATE__", "__NEXT_DATA__")
    }
}
