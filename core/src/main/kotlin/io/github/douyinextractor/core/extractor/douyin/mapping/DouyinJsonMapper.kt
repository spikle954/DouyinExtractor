package io.github.douyinextractor.core.extractor.douyin.mapping

import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Maps explicitly identified video entities, never a recursive search for the first playable URL. */
object DouyinJsonMapper {
    private const val MAX_NODES = 12_000
    private const val MAX_DEPTH = 48
    private const val MAX_FORMATS = 128
    private val idKeys = listOf("aweme_id", "awemeId", "item_id", "itemId", "id")
    private val addressKeys = listOf(
        "play_addr", "playAddr", "download_addr", "downloadAddr", "play_addr_h264", "playAddrH264",
        "play_addr_265", "playAddrH265", "play_addr_bytevc1", "playAddrBytevc1",
    )
    private val drmKeys = listOf(
        "is_drm", "isDrm", "drm", "drm_info", "drmInfo", "drm_type", "drmType",
        "license_url", "licenseUrl", "key_id", "keyId", "is_encrypted", "isEncrypted",
        "encrypted", "encryption", "encryption_info", "encryptionInfo", "encryption_type", "encryptionType",
    )

    fun map(root: JsonElement, targetId: String, source: String): MediaInfo? {
        if (!targetId.matches(Regex("[0-9]{1,25}"))) return null
        val queue = ArrayDeque<Pair<JsonElement, Int>>()
        queue.add(root to 0)
        val candidates = mutableListOf<MediaInfo>()
        var nodes = 0
        while (queue.isNotEmpty()) {
            if (++nodes > MAX_NODES) return null
            val (element, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) return null
            if (element is JsonObject) {
                val video = element.obj("video")
                val ids = idKeys.mapNotNull { element.string(it) }.distinct()
                if (video != null && ids.size == 1 && ids.single() == targetId) {
                    entity(element, video, targetId, source)?.let(candidates::add)
                    // Descendant videos belong to this entity; recommendations remain separate siblings.
                }
            }
            val children = when (element) {
                is JsonObject -> element.values
                is JsonArray -> element
                else -> emptyList()
            }
            if (nodes + queue.size + children.size > MAX_NODES) return null
            children.forEach { queue.add(it to depth + 1) }
        }
        if (candidates.isEmpty()) return null
        val first = candidates.first()
        val formats = candidates.flatMap { it.formats }.distinctBy { it.url }.take(MAX_FORMATS)
        return first.copy(
            title = candidates.firstNotNullOfOrNull { it.title },
            author = candidates.firstNotNullOfOrNull { it.author },
            coverUrl = candidates.firstNotNullOfOrNull { it.coverUrl },
            durationMs = candidates.firstNotNullOfOrNull { it.durationMs },
            formats = formats,
        )
    }

    private fun entity(entity: JsonObject, video: JsonObject, id: String, source: String): MediaInfo? {
        if (protected(entity) || protected(video) || audioOnly(entity) || audioOnly(video) || listOf("images", "image_list", "imageList").any {
                (entity[it] as? JsonArray)?.isNotEmpty() == true
            } || entity.obj("image_post_info", "imagePostInfo") != null) return null
        val formats = mutableListOf<MediaFormat>()
        // Variant metadata is more specific than the base address, so it wins duplicate URLs.
        video.array("bit_rate", "bitRate", "bitRateList", "bit_rate_list")?.take(64)?.forEach { item ->
            val variant = item as? JsonObject ?: return@forEach
            if (!protected(variant) && !audioOnly(variant)) addAddresses(variant, video, source, formats)
        }
        addAddresses(video, video, source, formats)
        val declared = formats.distinctBy { it.url }.take(MAX_FORMATS)
        val unique = declared.flatMap { original ->
            val alternative = DouyinPlaybackSource.alternative(original.url)
                ?.takeUnless { address -> declared.any { it.url == address } }
            if (alternative == null) listOf(original) else listOf(
                // A new route must not inherit the share file's metadata or watermark claim.
                MediaFormat(alternative, null, null, null, null, null, null,
                    "$source:public_play", watermarkFreeCandidate = true), original,
            )
        }.distinctBy { it.url }.take(MAX_FORMATS)
        if (unique.isEmpty()) return null
        val author = entity.obj("author")?.string("nickname", "nick_name", "name")
            ?: entity.string("author_name", "authorName")
        return MediaInfo(
            platform = "Douyin", id = id,
            title = entity.string("desc", "title", "description")?.take(4096),
            author = author?.take(256),
            coverUrl = listOf("origin_cover", "originCover", "cover", "dynamic_cover", "dynamicCover")
                .firstNotNullOfOrNull { key -> urls(video[key]).firstNotNullOfOrNull(MediaUrlPolicy::cover) },
            durationMs = video.positiveLong("duration", "duration_ms", "durationMs"),
            formats = unique,
        )
    }

    private fun addAddresses(
        holder: JsonObject, video: JsonObject, source: String, formats: MutableList<MediaFormat>,
    ) {
        for (key in addressKeys) {
            val value = holder[key] ?: continue
            val address = value as? JsonObject
            if (address != null && (protected(address) || audioOnly(address))) continue
            // A variant's missing properties do not imply the base stream's resolution/codec/size.
            // Base video properties describe its primary play address, not alternate download URLs.
            val metadata = if (holder === video && key !in setOf("play_addr", "playAddr"))
                listOfNotNull(address) else listOfNotNull(address, holder)
            val mime = metadata.firstNotNullOfOrNull { it.string("mime_type", "mimeType", "mime") }?.lowercase()
            if (mime != null && mime !in setOf("video/mp4", "application/mp4", "video/x-m4v")) continue
            if (metadata.any { it.string("format", "container")?.lowercase() in setOf("hls", "dash", "m3u8", "mpd") }) continue
            val codec = metadata.firstNotNullOfOrNull { it.string("codec", "codec_type", "codecType") }
                ?.take(64) ?: metadata.firstNotNullOfOrNull {
                    when {
                        it.flag("is_h265", "isH265") == true -> "h265"
                        it.flag("is_bytevc1", "isBytevc1") == true -> "bytevc1"
                        else -> null
                    }
                } ?: when (key) {
                "play_addr_h264", "playAddrH264" -> "h264"
                "play_addr_265", "playAddrH265" -> "h265"
                "play_addr_bytevc1", "playAddrBytevc1" -> "bytevc1"
                else -> null
            }
            if (codec?.lowercase() in setOf("aac", "mp3", "opus", "vorbis", "hls", "dash", "m3u8", "mpd")) continue
            urls(value).take(16).forEach { url ->
                val safe = MediaUrlPolicy.media(url) ?: return@forEach
                if (formats.any { it.url == safe }) return@forEach
                if (formats.size >= MAX_FORMATS) return
                formats += MediaFormat(
                    url = safe,
                    width = metadata.firstNotNullOfOrNull { it.dimension("width") },
                    height = metadata.firstNotNullOfOrNull { it.dimension("height") },
                    bitrate = metadata.firstNotNullOfOrNull { it.positiveLong("bit_rate", "bitRate", "bitrate") },
                    codec = codec,
                    mimeType = mime,
                    watermarked = if (DouyinPlaybackSource.isWatermarked(safe)) true else metadata.firstNotNullOfOrNull { it.flag("is_watermark", "isWatermark", "is_watermarked", "isWatermarked", "watermarked", "has_watermark", "hasWatermark") },
                    source = "$source:$key",
                    contentLength = metadata.firstNotNullOfOrNull { it.positiveLong("data_size", "dataSize", "file_size", "fileSize", "content_length", "contentLength") },
                )
            }
        }
    }

    private fun protected(obj: JsonObject): Boolean = drmKeys.any { key ->
        when (val value = obj[key]) {
            null, JsonNull -> false
            is JsonPrimitive -> value.content.lowercase() !in setOf("", "0", "false", "none", "null")
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
        }
    }

    private fun audioOnly(obj: JsonObject): Boolean =
        obj.flag("is_audio_only", "isAudioOnly", "audio_only", "audioOnly") == true ||
            obj.string("media_type", "mediaType", "video_type", "videoType")?.lowercase() == "audio" ||
            obj.string("mime_type", "mimeType", "mime")?.lowercase()?.startsWith("audio/") == true

    private fun urls(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.takeUnless { it is JsonNull }?.content)
        is JsonArray -> value.take(16).mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
        is JsonObject -> {
            val listed = value.array("url_list", "urlList", "urls")?.let(::urls).orEmpty()
            listed + listOfNotNull(value.string("url", "src"))
        }
        else -> emptyList()
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf(String::isNotEmpty)
    }
    private fun JsonObject.obj(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { this[it] as? JsonObject }
    private fun JsonObject.array(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { this[it] as? JsonArray }
    private fun JsonObject.positiveLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull {
        string(it)?.toLongOrNull()?.takeIf { number -> number > 0 }
    }
    private fun JsonObject.dimension(key: String): Int? = positiveLong(key)?.takeIf { it <= 32_768 }?.toInt()
    private fun JsonObject.flag(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull {
        when (string(it)?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    }
}
