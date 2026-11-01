package io.github.douyinextractor.core.extractor.douyin.parser

import io.github.douyinextractor.core.model.MediaInfo

class DouyinPageParser(
    private val parsers: List<PageParser> = listOf(
        RouterDataParser(), RenderDataParser(), SigiStateParser(), ScriptJsonParser(), JsonLdParser(), HtmlMediaParser(),
    ),
) {
    fun parse(html: String, pageUrl: String, targetId: String): PageParseResult {
        if (html.length > JsonDocuments.MAX_HTML_CHARS) {
            return PageParseResult(null, listOf(ParserAttempt("PageLimit", "页面超过解析大小限制")))
        }
        val matches = mutableListOf<MediaInfo>()
        val attempts = parsers.map { parser ->
            try {
                val inspection = parser.inspect(html, pageUrl, targetId)
                val media = inspection.media
                if (media != null && media.id == targetId && media.formats.isNotEmpty()) {
                    matches += media
                    ParserAttempt(parser.name, "找到与目标视频匹配的媒体数据", media.formats.size)
                } else ParserAttempt(parser.name, when (inspection.state) {
                    ParserState.MARKER_ABSENT -> "页面未包含此类数据标记"
                    ParserState.INVALID_DATA -> "页面数据结构无法解析，已继续尝试其他解析器"
                    ParserState.NO_MEDIA, ParserState.FOUND -> "存在页面数据，但未找到与目标视频匹配的媒体资源"
                })
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ParserAttempt(parser.name, "页面数据结构无法解析，已继续尝试其他解析器")
            }
        }
        return PageParseResult(matches.takeIf { it.isNotEmpty() }?.let(::mergeMedia), attempts)
    }
}
