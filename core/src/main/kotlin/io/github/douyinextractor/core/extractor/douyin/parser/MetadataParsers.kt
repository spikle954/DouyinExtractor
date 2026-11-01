package io.github.douyinextractor.core.extractor.douyin.parser

import io.github.douyinextractor.core.extractor.douyin.mapping.MediaUrlPolicy
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document
import java.net.URI
import java.time.Duration

class JsonLdParser : PageParser {
    override val name = "JsonLdParser"

    override fun parse(html: String, pageUrl: String, targetId: String): MediaInfo? {
        return parseDocument(JsonDocuments.document(html, pageUrl), pageUrl, targetId)
    }

    override fun inspect(html: String, pageUrl: String, targetId: String): ParserInspection {
        val document = JsonDocuments.document(html, pageUrl)
        if (document.select("script[type=application/ld+json]").isEmpty()) return ParserInspection(null, ParserState.MARKER_ABSENT)
        val media = parseDocument(document, pageUrl, targetId)
        return ParserInspection(media, if (media == null) ParserState.NO_MEDIA else ParserState.FOUND)
    }

    private fun parseDocument(document: Document, pageUrl: String, targetId: String): MediaInfo? {
        val videos = mutableListOf<JsonObject>()
        var malformed = false
        val scripts = document.select("script[type=application/ld+json]")
        require(scripts.size <= 32) { "Too many structured metadata blocks" }
        scripts.forEach { script ->
            try {
                val documentVideos = mutableListOf<JsonObject>()
                collectVideos(JsonDocuments.parse(script.data()), documentVideos)
                videos += documentVideos
            } catch (_: IllegalArgumentException) {
                malformed = true
            }
        }
        val matches = videos.mapNotNull { video ->
            val references = listOfNotNull(video["url"], video["@id"], video["mainEntityOfPage"], video["identifier"])
                .flatMap(::identityStrings)
            val identities = references.mapNotNull { identityId(it, pageUrl) }.distinct()
            // An unassociated VideoObject is accepted only on an explicitly identified canonical
            // page with exactly one video. Conflicting IDs always fail closed.
            if (identities.any { it != targetId } || (targetId !in identities &&
                    !(!malformed && references.isEmpty() && videos.size == 1 && canonicalMatches(document, pageUrl, targetId)))) {
                return@mapNotNull null
            }
            if (video.text("isAccessibleForFree") == "false" || video.text("requiresSubscription") == "true") return@mapNotNull null
            val formats = mutableListOf<MediaFormat>()
            format(video.text("contentUrl"), video.text("encodingFormat"), video, name)?.let(formats::add)
            val encodings = when (val encoding = video["encoding"]) {
                is JsonArray -> encoding.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(encoding)
                else -> emptyList()
            }
            encodings.take(32).forEach { encoding ->
                format(encoding.text("contentUrl"), encoding.text("encodingFormat"), encoding, name)?.let(formats::add)
            }
            if (formats.isEmpty()) return@mapNotNull null
            val author = when (val value = video["author"] ?: video["creator"]) {
                is JsonObject -> value.text("name")
                is JsonArray -> value.firstOrNull()?.let { (it as? JsonObject)?.text("name") ?: it.string() }
                else -> value?.string()
            }
            val cover = when (val value = video["thumbnailUrl"]) {
                is JsonArray -> value.take(16).firstNotNullOfOrNull { it.string()?.let(MediaUrlPolicy::cover) }
                else -> value?.string()?.let(MediaUrlPolicy::cover)
            }
            MediaInfo("Douyin", targetId, (video.text("name") ?: video.text("description"))?.take(4096), author?.take(256), cover,
                video.text("duration")?.let(::durationMillis), formats.distinctBy { it.url })
        }
        if (matches.isNotEmpty()) return mergeMedia(matches)
        if (malformed) throw IllegalArgumentException("Malformed structured metadata")
        return null
    }

    private fun collectVideos(root: JsonElement, result: MutableList<JsonObject>) {
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty()) {
            require(++count <= 10_000 && result.size <= 64) { "Structured metadata exceeds limit" }
            val node = queue.removeFirst()
            when (node) {
                is JsonObject -> {
                    val types = when (val type = node["@type"]) {
                        is JsonArray -> type.mapNotNull { it.string() }
                        else -> listOfNotNull(type?.string())
                    }
                    if (types.any { it == "VideoObject" || it == "https://schema.org/VideoObject" || it == "http://schema.org/VideoObject" }) {
                        require(result.size < 64) { "Too many structured video objects" }
                        result += node
                    }
                }
                else -> Unit
            }
            val children = when (node) {
                is JsonObject -> node.values
                is JsonArray -> node
                else -> emptyList()
            }
            require(count + queue.size + children.size <= 10_000) { "Structured metadata exceeds limit" }
            children.forEach(queue::addLast)
        }
    }

    private fun identityStrings(value: JsonElement): List<String> = when (value) {
        is JsonObject -> listOfNotNull(value.text("@id"), value.text("url"), value.text("value"))
        is JsonArray -> value.take(16).flatMap(::identityStrings)
        else -> listOfNotNull(value.string())
    }

    private fun format(url: String?, mime: String?, value: JsonObject, source: String): MediaFormat? {
        val safe = progressiveMedia(url, mime) ?: return null
        return MediaFormat(safe, positiveDimension(value.text("width")), positiveDimension(value.text("height")),
            null, null, "video/mp4", null, source)
    }
}

class HtmlMediaParser : PageParser {
    override val name = "HtmlMediaParser"

    override fun parse(html: String, pageUrl: String, targetId: String): MediaInfo? {
        return parseDocument(JsonDocuments.document(html, pageUrl), pageUrl, targetId)
    }

    override fun inspect(html: String, pageUrl: String, targetId: String): ParserInspection {
        val document = JsonDocuments.document(html, pageUrl)
        if (document.select("video, meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]").isEmpty()) {
            return ParserInspection(null, ParserState.MARKER_ABSENT)
        }
        val media = parseDocument(document, pageUrl, targetId)
        return ParserInspection(media, if (media == null) ParserState.NO_MEDIA else ParserState.FOUND)
    }

    private fun parseDocument(document: Document, pageUrl: String, targetId: String): MediaInfo? {
        // HTML source tags contain no item identity. Require an explicit canonical page and
        // one video, or a video element that explicitly identifies this particular item.
        val videos = document.select("video").take(32)
        val pageBound = canonicalMatches(document, pageUrl, targetId)
        val candidates = videos.filter { video ->
            val explicit = video.attr("data-aweme-id").ifBlank { video.attr("data-video-id") }
            if (explicit.isNotBlank()) explicit == targetId
            else pageBound && videos.size == 1
        }
        val formats = candidates.flatMap { video ->
            val sources = listOf(video) + video.select("source").take(32)
            sources.mapNotNull { source ->
                val mime = source.attr("type").ifBlank { video.attr("type") }.ifBlank { null }
                val url = progressiveMedia(source.attr("src"), mime) ?: return@mapNotNull null
                // Video/source element dimensions describe layout, not intrinsic resource size.
                MediaFormat(url, null, null, null, null, "video/mp4", null, name)
            }
        }.toMutableList()
        // OpenGraph fallback must bind its own URL and declare video content, with no competing
        // video elements. Never treat a generic page's first MP4 link as the target video.
        if (videos.isEmpty() && pageBound && document.meta("og:type") in setOf("video", "video.other", "video.movie") &&
            document.meta("og:url")?.let { identityId(it, pageUrl) } == targetId) {
            val mime = document.meta("og:video:type")
            document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
                .take(16).mapNotNull { progressiveMedia(it.attr("content"), mime) }.forEach { url ->
                    formats += MediaFormat(url, positiveDimension(document.meta("og:video:width")),
                        positiveDimension(document.meta("og:video:height")), null, null, "video/mp4", null, name)
                }
        }
        if (formats.isEmpty()) return null
        return MediaInfo("Douyin", targetId,
            (document.meta("og:title") ?: document.title().takeIf { it.isNotBlank() })?.take(4096),
            document.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }?.take(256),
            (document.meta("og:image") ?: candidates.firstOrNull()?.attr("poster"))?.let(MediaUrlPolicy::cover),
            null, formats.distinctBy { it.url })
    }
}

private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" }
private fun JsonObject.text(key: String): String? = this[key]?.string()
private fun Document.meta(property: String): String? = select("meta[property]").firstOrNull { it.attr("property") == property }
    ?.attr("content")?.takeIf { it.isNotBlank() }

private fun identityId(value: String, pageUrl: String): String? {
    if (value.matches(Regex("[0-9]{1,25}"))) return value
    val absolute = try { URI(pageUrl).resolve(value).toString() } catch (_: IllegalArgumentException) { return null }
    return DouyinUrls.videoId(absolute)
}

private fun canonicalMatches(document: Document, pageUrl: String, targetId: String): Boolean {
    if (DouyinUrls.videoId(pageUrl) != targetId) return false
    val canonical = document.select("link[rel=canonical]").map { it.attr("href") }
    return canonical.isNotEmpty() && canonical.all { identityId(it, pageUrl) == targetId }
}

private fun progressiveMedia(url: String?, mime: String?): String? {
    val safe = url?.let(MediaUrlPolicy::media) ?: return null
    val mimeValue = mime?.substringBefore(';')?.trim()?.lowercase()
    if (mimeValue != null && mimeValue !in setOf("video/mp4", "video/x-m4v")) return null
    val path = try { URI(safe).path.lowercase() } catch (_: Exception) { return null }
    if (mimeValue == null && !path.endsWith(".mp4") && !path.endsWith(".m4v")) return null
    return safe
}

private fun positiveDimension(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1..16_384 }
private fun durationMillis(value: String): Long? = try {
    Duration.parse(value).toMillis().takeIf { it in 1..604_800_000L }
} catch (_: Exception) { null }
