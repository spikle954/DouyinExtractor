package io.github.douyinextractor.core.input

import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.ParsedShare
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls

/** Extracts a single supported video reference without ever issuing a network request. */
class ShareTextParser {
    fun parse(text: String): Result<ParsedShare> {
        if (text.length > MAX_INPUT_LENGTH) return Result.failure(ExtractError.InvalidUrl)
        if (BilibiliUrls.validId(text.trim())) return Result.success(ParsedShare(BilibiliUrls.canonical(text.trim())))

        val candidates = linkedSetOf<String>()
        for (match in urlPattern.findAll(text)) {
            // First consume the entire non-whitespace token, including quotes. Otherwise an
            // attacker could put a quoted Douyin URL inside another site's query and get a
            // second independent match. Chinese prose is removed only after tokenization.
            val queryStart = match.value.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) match.value.length else it }
            val proseStart = match.value.indices.firstOrNull { index ->
                match.value[index] in chineseDelimiters ||
                    (index < queryStart && Character.UnicodeScript.of(match.value[index].code) == Character.UnicodeScript.HAN)
            } ?: -1
            val token = (if (proseStart < 0) match.value else match.value.take(proseStart))
                .trimEnd('.', ',', ';', '!', '?', ')', ']', '}', ':', '\'', '"')
            val raw = if ('?' !in token && '#' !in token) token.removeSuffix(":/") else token
            if (raw.length > DouyinUrls.MAX_URL_LENGTH) return Result.failure(ExtractError.InvalidUrl)
            val social = SocialUrls.normalize(raw)
            if (social != null) { candidates += social; continue }
            val bili = BilibiliUrls.normalize(raw)
            if (bili != null) { candidates += bili; continue }
            val normalized = DouyinUrls.normalize(raw) ?: continue
            if (DouyinUrls.videoId(normalized) != null || DouyinUrls.isShortLink(normalized)) {
                candidates += normalized
            }
        }
        // A text containing two different share links should never silently download one of them.
        if (candidates.size != 1) return Result.failure(ExtractError.InvalidUrl)
        return Result.success(ParsedShare(candidates.single()))
    }

    companion object {
        const val MAX_INPUT_LENGTH = 16_384
        private val chineseDelimiters = "“”‘’「」『』【】（），。！？；、…："

        // Consume an arbitrary URI as a whole, so an allowed URL embedded inside an evil URL's
        // query/path cannot become an independent match. The left boundary excludes URI syntax,
        // while allowing the Chinese text commonly adjacent to a pasted link.
        private val urlPattern = Regex(
            "(?i)(?<![a-z0-9_./:@?=&%+\\-])(?:[a-z][a-z0-9+.-]*://|" +
                "(?:(?:www|m|v)\\.)?(?:iesdouyin|douyin)\\.com(?=[/?#]|$)|(?:(?:www|m)\\.)?bilibili\\.com(?=[/?#]|$)|b23\\.tv(?=[/?#]|$)|" +
                "(?:(?:www|v)\\.)?kuaishou\\.com(?=[/?#]|$)|[a-z0-9]{1,32}\\.m\\.chenzhongtech\\.com(?=[/?#]|$)|(?:www\\.)?xiaohongshu\\.com(?=[/?#]|$)|(?:www\\.)?xhslink\\.(?:com|cn)(?=[/?#]|$))" +
                "[^\\s\\p{Z}<>]*",
        )
    }
}
