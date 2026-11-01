package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.extractor.douyin.parser.JsonDocuments
import io.github.douyinextractor.core.model.*
import kotlinx.serialization.json.*

/** Consume only state bound to the selected note/photo. No JavaScript execution or recommendation scanning. */
object SocialPageMapping {
    internal fun kuaishouState(root: JsonObject, id: String): MediaInfo? {
        require(SocialUrls.validId(SocialUrls.KUAISHOU, id))
        return try { kuaishou(root, id, MappingStats()) } catch (_: NotVideoNote) { throw ExtractError.NotVideo }
    }
    internal fun kuaishouDetail(detail: JsonObject, id: String): MediaInfo? {
        val photo = detail.obj("photo")?.takeIf { it.text("id", "photoId") == id } ?: return null
        val key = "VisionVideoDetailPhoto:$id"
        val bound = buildJsonObject { put("photo", buildJsonObject { put("type", "id"); put("id", key) }); detail["author"]?.let { put("author", it) } }
        return kuaishouState(buildJsonObject { put("defaultClient", buildJsonObject { put(key, photo); put("detail", bound) }) }, id)
    }
    fun parse(html: String, platform: String, id: String, onDiagnostic: (String) -> Unit = {}): MediaInfo {
        require(SocialUrls.validId(platform, id))
        val doc = JsonDocuments.document(html, SocialUrls.canonical(platform, id))
        val names = if (platform == SocialUrls.KUAISHOU) setOf("__APOLLO_STATE__", "INIT_STATE") else setOf("__INITIAL_STATE__")
        val scripts = doc.select("script").take(96)
        val stats = MappingStats()
        var assignments = 0
        var decoded = 0
        var notVideo = false
        try {
            for (script in scripts) {
                for (raw in JsonDocuments.assignments(script.data(), names)) {
                    assignments++
                    val root = runCatching { JsonDocuments.parse(replaceUndefined(raw)) as? JsonObject }.getOrNull() ?: continue
                    decoded++
                    try {
                        val media = if (platform == SocialUrls.KUAISHOU) kuaishou(root, id, stats) else xiaohongshu(root, id, stats)
                        if (media != null) return media
                    } catch (_: NotVideoNote) { notVideo = true }
                }
            }
            if (notVideo) throw ExtractError.NotVideo
            // Evaluate access notices only after looking for the target; normal login buttons are not failures.
            val text = doc.body().text().take(4000)
            if (listOf("笔记不存在", "笔记已删除", "作品已删除", "作品不存在").any(text::contains)) throw ExtractError.VideoNotFound
            if (listOf("登录后查看此", "登录后才能查看", "请登录后查看").any(text::contains)) throw ExtractError.LoginRequired
            if (listOf("访问频次异常", "访问受限", "安全验证", "验证后继续").any(text::contains)) throw ExtractError.PlatformRejected
            throw ExtractError.UnsupportedPage
        } finally {
            onDiagnostic("Parser SocialState：脚本 ${scripts.size}；状态块 $assignments；可解析状态 $decoded；目标作品状态 ${stats.targets}；地址候选 ${stats.candidates}；受支持 MP4 ${stats.accepted}；被筛除地址 ${stats.rejected}。不记录页面内容或资源地址。")
        }
    }

    private class MappingStats(var targets: Int = 0, var candidates: Int = 0, var accepted: Int = 0, var rejected: Int = 0)
    private fun kuaishou(root: JsonObject, id: String, stats: MappingStats): MediaInfo? {
        val dc = root.obj("defaultClient")
        val keyed = dc?.obj("VisionVideoDetailPhoto:$id")
        val mobile = root.obj("photo")?.takeIf { it.text("id", "photoId") == id }
        val photo = keyed ?: mobile ?: return null
        if (photo.text("id", "photoId")?.let { it != id } == true) return null
        stats.targets++
        if (photo.text("photoType")?.let { it != "VIDEO" } == true) throw NotVideoNote()
        val formats = mutableListOf<MediaFormat>()
        val objects = dc ?: JsonObject(emptyMap())
        fun ref(value: JsonElement?): JsonElement? {
            var current = value
            val seen = mutableSetOf<String>()
            repeat(12) {
                val objectValue = current as? JsonObject ?: return current
                val key = objectValue.text("__ref") ?: objectValue.text("id").takeIf { objectValue.text("type") == "id" }
                if (key == null) return objectValue["json"] ?: current
                if (!seen.add(key)) return null
                current = objects[key] ?: return null
            }
            return null
        }
        fun urls(value: JsonElement?): List<String> = when (val resolved = ref(value)) {
            is JsonPrimitive -> listOfNotNull(resolved.contentOrNull)
            is JsonArray -> resolved.take(8).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        fun add(url: String, item: JsonObject, codec: String?, source: String) {
            stats.candidates++
            val safe = SocialUrls.pageMedia(url, SocialUrls.KUAISHOU) ?: run { stats.rejected++; return }
            stats.accepted++
            formats += MediaFormat(safe, item.positive("width")?.toInt(), item.positive("height")?.toInt(),
                item.positive("avgBitrate")?.takeIf { it <= 100_000_000 }?.times(1000), codec, "video/mp4", null, source,
                contentLength = item.positive("fileSize"), qualityLabel = item.label("qualityType", "qualityLabel"))
        }
        for ((field, codec) in listOf("manifest" to "h264", "manifestH265" to "hevc")) {
            val manifest = ref(photo[field]) as? JsonObject ?: continue
            for (set in manifest.arr("adaptationSet").take(8)) {
                val adaptation = ref(set) as? JsonObject ?: continue
                if (adaptation.text("mimeType")?.startsWith("audio/") == true) continue
                for (rep in adaptation.arr("representation").take(16)) {
                    val item = ref(rep) as? JsonObject ?: continue
                    if (item.text("mimeType")?.startsWith("audio/") == true) continue
                    (urls(item["url"]) + urls(item["backupUrl"])).forEach { add(it, item, codec, "Kuaishou:$field") }
                }
            }
        }
        photo.text("photoUrl")?.let { add(it, photo, null, "Kuaishou:photoUrl") }
        photo.text("photoH265Url")?.let { add(it, photo, "hevc", "Kuaishou:photoH265Url") }
        if (formats.isEmpty()) return null
        // Resolve the author's reference from the detail object that references this exact photo.
        val detail = objects.values.filterIsInstance<JsonObject>().firstOrNull { value ->
            (ref(value["photo"]) as? JsonObject)?.text("id") == id
        }
        val author = (ref(detail?.get("author")) as? JsonObject)?.text("name") ?: photo.text("userName")
        return MediaInfo(SocialUrls.KUAISHOU, id, photo.text("caption"), author,
            photo.text("coverUrl")?.let { SocialUrls.cover(it, SocialUrls.KUAISHOU) }, photo.positive("duration"),
            formats.distinctBy { it.url }.take(32), canonicalUrl = SocialUrls.canonical(SocialUrls.KUAISHOU, id))
    }

    private fun xiaohongshu(root: JsonObject, id: String, stats: MappingStats): MediaInfo? {
        val desktop = root.obj("note")?.obj("noteDetailMap")?.obj(id)?.obj("note")
        val mobile = root.obj("noteData")?.obj("data")?.obj("noteData")?.takeIf { it.text("noteId") == id }
        val note = desktop ?: mobile ?: return null
        if (note.text("noteId", "id")?.let { it != id } == true) return null
        stats.targets++
        if (note.text("type")?.let { it != "video" } == true) throw NotVideoNote()
        val video = note.obj("video") ?: return null
        if (video.obj("media")?.obj("video")?.positive("drmType") != null) throw ExtractError.RestrictedContent
        val stream = video.obj("media")?.obj("stream") ?: return null
        val formats = mutableListOf<MediaFormat>()
        for (key in listOf("h264", "h265", "av1")) {
            for (entry in stream.arr(key).take(16)) {
                val item = entry as? JsonObject ?: continue
                if (item.text("mimeType")?.let { it != "video/mp4" } == true || item.text("format")?.let { it.lowercase() != "mp4" } == true || item.positive("drmType") != null) continue
                val urls = listOfNotNull(item.text("masterUrl")) + item.arr("backupUrls").take(8).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                for (url in urls) {
                    stats.candidates++
                    val safe = SocialUrls.pageMedia(url, SocialUrls.XIAOHONGSHU) ?: run { stats.rejected++; null } ?: continue
                    stats.accepted++
                    formats += MediaFormat(safe, item.positive("width")?.toInt(), item.positive("height")?.toInt(), item.positive("avgBitrate"),
                        item.label("videoCodec") ?: key, "video/mp4", null, "Xiaohongshu:stream:$key",
                        contentLength = item.positive("size"), qualityLabel = item.label("qualityType"))
                }
            }
        }
        if (formats.isEmpty()) return null
        val image = note.arr("imageList").firstOrNull() as? JsonObject
        val cover = image?.text("urlDefault", "urlPre", "url")?.let { SocialUrls.cover(it, SocialUrls.XIAOHONGSHU) }
        val duration = video.obj("media")?.positive("duration") ?: stream.values.filterIsInstance<JsonArray>()
            .flatMap { it.filterIsInstance<JsonObject>() }.firstNotNullOfOrNull { it.positive("duration") }
        return MediaInfo(SocialUrls.XIAOHONGSHU, id, note.text("title") ?: note.text("desc"), note.obj("user")?.text("nickname", "nickName"),
            cover, duration, formats.distinctBy { it.url }.take(32), canonicalUrl = SocialUrls.canonical(SocialUrls.XIAOHONGSHU, id))
    }
    private class NotVideoNote : Exception()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = (this[key] as? JsonArray)?.toList().orEmpty()
    private fun JsonObject.text(vararg keys: String) = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() && value.length <= 16384 } }
    private fun JsonObject.positive(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && it <= 2_000_000_000L }
    private fun JsonObject.label(vararg keys: String) = text(*keys)?.takeIf { it.matches(Regex("[\\p{L}\\p{N} ._×-]{1,32}")) }
    /** Some SSR state uses undefined. Replace only a bare value outside JSON strings; never eval JS. */
    private fun replaceUndefined(text: String): String {
        val result = StringBuilder(); var index = 0; var quoted = false; var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (!quoted && text.startsWith("undefined", index) && (index == 0 || text[index - 1] in ":,[ \t\r\n") &&
                (index + 9 == text.length || text[index + 9] in ",}] \t\r\n")) { result.append("null"); index += 9; continue }
            result.append(char); index++
            if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
            else if (char == '"') quoted = true
        }
        return result.toString()
    }
}
