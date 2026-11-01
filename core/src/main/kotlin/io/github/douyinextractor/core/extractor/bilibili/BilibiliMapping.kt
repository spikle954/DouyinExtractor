package io.github.douyinextractor.core.extractor.bilibili

import io.github.douyinextractor.core.model.*
import kotlinx.serialization.json.*

data class BilibiliDetail(val target: String, val bvid: String, val cid: Long, val title: String?, val author: String?,
    val cover: String?, val pages: List<VideoPage>, val page: VideoPage)

/** Maps only the requested public UGC and selected CID. Stream URLs originate in its play response. */
object BilibiliMapping {
    fun data(root: JsonObject): JsonObject {
        when (root.long("code")) {
            0L -> Unit
            -404L, 62002L -> throw ExtractError.VideoNotFound
            -101L -> throw ExtractError.LoginRequired
            -403L, -412L, -352L -> throw ExtractError.PlatformRejected
            else -> throw ExtractError.UnsupportedPage
        }
        return root["data"] as? JsonObject ?: throw ExtractError.UnsupportedPage
    }
    fun detail(value: JsonObject, target: String, selected: Int): BilibiliDetail {
        val bvid = value.text("bvid")?.takeIf { it.startsWith("BV") && BilibiliUrls.validId(it) } ?: throw ExtractError.UnsupportedPage
        if (target != bvid && target != "av${value.long("aid")}") throw ExtractError.UnsupportedPage
        val rights = value["rights"] as? JsonObject
        if (value.text("redirect_url")?.contains("/bangumi/") == true || value.flag("is_upower_exclusive") || rights?.let { flags ->
                listOf("pay", "ugc_pay", "ugc_pay_preview", "arc_pay", "movie", "is_stein_gate").any { flags.flag(it) }
            } == true) throw ExtractError.RestrictedContent
        val raw = value["pages"] as? JsonArray ?: throw ExtractError.UnsupportedPage
        if (raw.size !in 1..1000) throw ExtractError.UnsupportedPage
        val pages = raw.map {
            val page = it as? JsonObject ?: throw ExtractError.UnsupportedPage
            val number = page.long("page")?.takeIf { n -> n in 1..10000 }?.toInt() ?: throw ExtractError.UnsupportedPage
            val cid = page.long("cid")?.takeIf { n -> n > 0 } ?: throw ExtractError.UnsupportedPage
            VideoPage(number, page.text("part")?.take(512) ?: "P$number", cid, page.long("duration")?.takeIf { n -> n in 1..864000 }?.times(1000))
        }
        if (pages.distinctBy { it.number }.size != pages.size || pages.distinctBy { it.cid }.size != pages.size) throw ExtractError.UnsupportedPage
        val page = pages.singleOrNull { it.number == selected } ?: throw ExtractError.PartNotFound
        return BilibiliDetail(target, bvid, page.cid, value.text("title")?.take(4096),
            (value["owner"] as? JsonObject)?.text("name")?.take(256), value.text("pic")?.let(BilibiliUrls::cover), pages, page)
    }
    fun media(detail: BilibiliDetail, value: JsonObject): MediaInfo {
        if (listOf("is_preview", "is_pay_preview", "is_drm", "is_encrypted").any { value.flag(it) } ||
            value["drm_tech_type"]?.let { it !is JsonNull && (it !is JsonPrimitive || it.content !in setOf("0", "", "null")) } == true) throw ExtractError.RestrictedContent
        // If the response includes its own identity, it must agree with the bound request.
        if (value.text("bvid")?.let { it != detail.bvid } == true || value.long("cid")?.let { it != detail.cid } == true) throw ExtractError.UnsupportedPage
        val durl = value["durl"] as? JsonArray ?: throw ExtractError.UnsupportedMedia
        if (durl.size != 1 || value.text("format")?.matches(Regex("(?:mp4(?:360|480|720|1080|2160)?|hdmp4)")) != true) throw ExtractError.UnsupportedMedia
        val stream = durl.single() as? JsonObject ?: throw ExtractError.UnsupportedMedia
        val duration = stream.long("length")?.takeIf { it > 0 } ?: value.long("timelength")?.takeIf { it > 0 }
        val expected = detail.page.durationMs
        if (duration != null && expected != null && duration + maxOf(3000L, expected / 50) < expected) throw ExtractError.RestrictedContent
        val addresses = (listOfNotNull(stream.text("url")) +
            (stream["backup_url"] as? JsonArray).orEmpty().take(16).mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            .mapNotNull(BilibiliUrls::media).distinct().take(8)
        if (addresses.isEmpty()) throw ExtractError.UnsupportedMedia
        val quality = value.long("quality")
        val label = when (quality) { 16L -> "360P"; 32L -> "480P"; 64L -> "720P"; 74L -> "720P 60帧"; 80L -> "1080P"; 112L -> "1080P+"; 116L -> "1080P 60帧"; 120L -> "4K"; else -> "平台返回画质" }
        val codec = when (value.long("video_codecid")) { 7L -> "h264"; 12L -> "h265"; 13L -> "av1"; else -> null }
        val formats = addresses.mapIndexed { index, url -> MediaFormat(url, null, null, null, codec, "video/mp4", null,
            "Bilibili:MP4:${if (index == 0) "primary" else "backup"}", stream.long("size")?.takeIf { it > 0 }, qualityLabel = label) }
        val title = if (detail.pages.size > 1) "${detail.title.orEmpty()} · P${detail.page.number} ${detail.page.title}" else detail.title
        return MediaInfo("Bilibili", detail.target, title, detail.author, detail.cover, duration ?: expected, formats,
            detail.pages, detail.page.number, BilibiliUrls.canonical(detail.target, detail.page.number))
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.flag(key: String) = text(key) in setOf("true", "1")
}
