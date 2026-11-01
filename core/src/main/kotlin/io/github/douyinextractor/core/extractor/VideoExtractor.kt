package io.github.douyinextractor.core.extractor

import io.github.douyinextractor.core.model.MediaInfo

interface VideoExtractor {
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): Result<MediaInfo>
}
