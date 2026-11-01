package io.github.douyinextractor.core.extractor.douyin.parser

import io.github.douyinextractor.core.extractor.douyin.mapping.DouyinJsonMapper
import io.github.douyinextractor.core.model.MediaInfo
import org.jsoup.nodes.Document

abstract class StateParser : PageParser {
    protected abstract fun documents(document: Document): List<String>

    final override fun parse(html: String, pageUrl: String, targetId: String): MediaInfo? {
        return inspect(html, pageUrl, targetId).media
    }

    final override fun inspect(html: String, pageUrl: String, targetId: String): ParserInspection {
        var malformed = false
        val matches = mutableListOf<MediaInfo>()
        val states = documents(JsonDocuments.document(html, pageUrl)).take(64)
        if (states.isEmpty()) return ParserInspection(null, ParserState.MARKER_ABSENT)
        for (text in states) {
            try {
                DouyinJsonMapper.map(JsonDocuments.parse(text), targetId, name)?.let(matches::add)
            } catch (_: IllegalArgumentException) {
                malformed = true
            }
        }
        if (matches.isNotEmpty()) return ParserInspection(mergeMedia(matches), ParserState.FOUND)
        return ParserInspection(null, if (malformed) ParserState.INVALID_DATA else ParserState.NO_MEDIA)
    }

    protected fun assignments(document: Document, names: Set<String>): List<String> =
        document.select("script").take(64).flatMap { JsonDocuments.assignments(it.data(), names) }
}

class RouterDataParser : StateParser() {
    override val name = "RouterDataParser"
    override fun documents(document: Document): List<String> = assignments(document, setOf("_ROUTER_DATA"))
}

class RenderDataParser : StateParser() {
    override val name = "RenderDataParser"
    override fun documents(document: Document): List<String> = document.select("script#RENDER_DATA")
        .take(32).map {
            try { JsonDocuments.percentDecoded(it.data()) } catch (_: IllegalArgumentException) { "{invalid-json}" }
        } + assignments(document, setOf("RENDER_DATA"))
}

class SigiStateParser : StateParser() {
    override val name = "SigiStateParser"
    override fun documents(document: Document): List<String> = document.select("script#SIGI_STATE")
        .take(32).map { it.data() } + assignments(document, setOf("SIGI_STATE"))
}

class ScriptJsonParser : StateParser() {
    override val name = "ScriptJsonParser"
    override fun documents(document: Document): List<String> = document.select(
        "script[type=application/json], script#__NEXT_DATA__, script#__NUXT_DATA__",
    ).take(32).filter { it.id() !in setOf("RENDER_DATA", "SIGI_STATE") }.map { it.data() } +
        assignments(document, setOf("__INITIAL_STATE__", "__PRELOADED_STATE__", "__SSR_DATA__", "__APOLLO_STATE__"))
}

internal fun mergeMedia(values: List<MediaInfo>): MediaInfo {
    val first = values.first()
    return first.copy(
        title = values.firstNotNullOfOrNull { it.title },
        author = values.firstNotNullOfOrNull { it.author },
        coverUrl = values.firstNotNullOfOrNull { it.coverUrl },
        durationMs = values.firstNotNullOfOrNull { it.durationMs },
        formats = values.flatMap { it.formats }.groupBy { it.url }.values.take(64).map { duplicates ->
            duplicates.first().copy(
                width = duplicates.mapNotNull { it.width }.distinct().singleOrNull(),
                height = duplicates.mapNotNull { it.height }.distinct().singleOrNull(),
                bitrate = duplicates.mapNotNull { it.bitrate }.distinct().singleOrNull(),
                codec = duplicates.mapNotNull { it.codec }.distinct().singleOrNull(),
                mimeType = duplicates.mapNotNull { it.mimeType }.distinct().singleOrNull(),
                watermarked = duplicates.mapNotNull { it.watermarked }.distinct().singleOrNull(),
                contentLength = duplicates.mapNotNull { it.contentLength }.distinct().singleOrNull(),
                watermarkFreeCandidate = duplicates.any { it.watermarkFreeCandidate },
            )
        },
    )
}
