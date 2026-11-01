package io.github.douyinextractor.core.extractor.douyin.parser

import io.github.douyinextractor.core.model.MediaInfo

/** Attempts contain fixed structural reasons only, never page values or URL query strings. */
data class ParserAttempt(val parser: String, val reason: String, val formatCount: Int = 0)
data class PageParseResult(val media: MediaInfo?, val attempts: List<ParserAttempt>)
enum class ParserState { MARKER_ABSENT, NO_MEDIA, INVALID_DATA, FOUND }
data class ParserInspection(val media: MediaInfo?, val state: ParserState)

interface PageParser {
    val name: String
    fun parse(html: String, pageUrl: String, targetId: String): MediaInfo?
    fun inspect(html: String, pageUrl: String, targetId: String): ParserInspection {
        val media = parse(html, pageUrl, targetId)
        return ParserInspection(media, if (media == null) ParserState.NO_MEDIA else ParserState.FOUND)
    }
}
