package io.github.douyinextractor.core.extractor

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.extractor.bilibili.BilibiliExtractor
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.douyin.DouyinExtractor
import io.github.douyinextractor.core.network.DouyinUrlResolver
import io.github.douyinextractor.core.extractor.social.SocialExtractor
import io.github.douyinextractor.core.extractor.social.SocialUrls

/** Platform dispatch is separate from UI, public page parsers and file transport. */
class PlatformVideoService {
    private val bilibili = BilibiliExtractor()
    private val social = SocialExtractor()
    suspend fun resolve(url: String, trace: ResolutionDiagnostics) =
        when { SocialUrls.normalize(url) != null -> social.resolve(url, trace); BilibiliUrls.normalize(url) != null -> bilibili.resolve(url, trace); else -> DouyinUrlResolver().resolve(url, trace) }
    suspend fun extract(url: String, trace: ResolutionDiagnostics) =
        when { SocialUrls.normalize(url) != null -> social.extract(url, trace); BilibiliUrls.normalize(url) != null -> bilibili.extract(url, trace); else -> DouyinExtractor().extract(url, trace::extraction) }
}
