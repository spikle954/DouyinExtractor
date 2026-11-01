package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.extractor.social.SocialPageMapping
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import io.github.douyinextractor.core.model.*
import kotlinx.serialization.json.*

/** Only matching public photo state or the site's matching detail response can supply media. */
class KuaishouBrowserParser {
    fun parseEvaluationResult(value: String, targetId: String): BrowserSnapshotResult {
        if (!SocialUrls.validId(SocialUrls.KUAISHOU, targetId)) return failure("目标作品编号无效", ExtractError.InvalidUrl)
        val root = try {
            require(value.length <= 8 * 1024 * 1024)
            val decoded = parseBounded(value)
            val result = if (decoded is JsonPrimitive && decoded.isString) parseBounded(decoded.content) else decoded
            require(result is JsonObject && result.text("version") == "ks-1"); result
        } catch (_: Exception) { return failure("快手网页快照无效或超过大小限制", ExtractError.ParserError("KuaishouBrowser", "Invalid snapshot")) }
        val url = root.text("pageUrl")
        if (url == null || !KuaishouBrowserPolicy.navigation(url, targetId)) return failure("当前网页与目标快手作品不匹配", ExtractError.UnsupportedPage)
        val safeUrl = KuaishouBrowserPolicy.safeUrl(url)
        val observation = root["observation"] as? JsonObject
        fun count(key: String) = ((observation?.get(key) as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 32)
        val ready = root.text("readyState")?.takeIf { it in setOf("loading", "interactive", "complete") } ?: "未知"
        val states = (root["states"] as? JsonArray).orEmpty().take(4)
        val components = (root["components"] as? JsonArray).orEmpty().take(2)
        val details = (root["details"] as? JsonArray).orEmpty().take(4)
        val attempts = mutableListOf(ParserAttempt("KuaishouBrowserSnapshot", "文档：$ready；目标状态：${states.size}；动态作品：${components.size}；详情响应：${details.size}；可见视频：${count("visibleVideos")}；播放中：${count("playingVideos")}；HTTPS：${count("httpsVideos")}；blob：${count("blobVideos")}"))
        if ((root["captureFailed"] as? JsonPrimitive)?.booleanOrNull == true) return failure("尚未读取到快手网页数据", ExtractError.UnsupportedPage, safeUrl)
        var lastError: ExtractError? = null
        fun record(name: String, block: () -> MediaInfo?): MediaInfo? {
            return try { block()?.also { attempts += ParserAttempt(name, "找到匹配的目标快手作品", it.formats.size) }
                ?: run { attempts += ParserAttempt(name, "尚无目标作品的 MP4 数据"); null } }
            catch (e: ExtractError) { lastError = e; attempts += ParserAttempt(name, "当前数据未提供目标视频"); null }
            catch (_: Exception) { attempts += ParserAttempt(name, "数据无法解析或超过限制"); null }
        }
        fun success(media: MediaInfo) = BrowserSnapshotResult(media, safeUrl, attempts.toList(), "已从快手官方网页取得目标视频信息")
        root.text("html")?.takeIf { it.length <= 1024 * 1024 }?.let { html ->
            record("KuaishouBrowserHtml") { SocialPageMapping.parse(html, SocialUrls.KUAISHOU, targetId) }?.let { return success(it) }
        }
        var budget = 2 * 1024 * 1024
        for (state in states.filterIsInstance<JsonObject>()) {
            val name = state.text("name")?.takeIf { it in setOf("__APOLLO_STATE__", "INIT_STATE", "ApolloCache") } ?: continue
            val raw = state.text("json") ?: continue
            if (raw.length > 1024 * 1024 || raw.length > budget) continue
            budget -= raw.length
            record("KuaishouBrowserState:$name") { SocialPageMapping.kuaishouState(parseBounded(raw) as? JsonObject ?: return@record null, targetId) }?.let { return success(it) }
        }
        for (entry in components) {
            val raw = (entry as? JsonPrimitive)?.contentOrNull ?: continue
            if (raw.length > 1024 * 1024 || raw.length > budget) continue
            budget -= raw.length
            record("KuaishouBrowserVideoInfo") { SocialPageMapping.kuaishouDetail(parseBounded(raw) as? JsonObject ?: return@record null, targetId) }?.let { return success(it) }
        }
        for (entry in details) {
            val raw = (entry as? JsonPrimitive)?.contentOrNull ?: continue
            if (raw.length > 1024 * 1024 || raw.length > budget) continue
            budget -= raw.length
            record("KuaishouBrowserDetail") { SocialPageMapping.kuaishouDetail(parseBounded(raw) as? JsonObject ?: return@record null, targetId) }?.let { return success(it) }
        }
        val error = when (root.text("blocker")) { "verification" -> ExtractError.KuaishouVerificationRequired; "login" -> ExtractError.LoginRequired
            else -> lastError?.takeIf { it in setOf(ExtractError.NotVideo, ExtractError.VideoNotFound, ExtractError.LoginRequired, ExtractError.PlatformRejected) } ?: ExtractError.UnsupportedPage }
        return BrowserSnapshotResult(null, safeUrl, attempts, when(error) {
            ExtractError.KuaishouVerificationRequired -> "快手要求正常浏览器验证，请在可见页面中手动完成后再检查"
            ExtractError.LoginRequired -> "快手页面要求登录，当前仅支持公开访问"
            else -> "网页尚未提供目标快手视频的 MP4 数据；可等待加载或在页面中播放"
        }, error)
    }
    private fun parseBounded(text: String): JsonElement {
        require(text.length <= 4 * 1024 * 1024)
        var depth = 0; var quoted = false; var escaped = false
        for (ch in text) { if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
            else when (ch) { '"' -> quoted = true; '{','[' -> require(++depth <= 64); '}',']' -> depth-- } }
        return Json.parseToJsonElement(text)
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun failure(reason: String, error: ExtractError, url: String? = null) = BrowserSnapshotResult(null, url, emptyList(), reason, error)
}
