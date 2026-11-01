package io.github.douyinextractor.core.model

/** A platform URL taken from user-supplied text. This is not extracted media. */
data class ParsedShare(val url: String)

/** Identification only: neither page availability nor downloadable media is implied. */
data class ResolvedVideoLink(
    val originalUrl: String,
    val canonicalUrl: String,
    val awemeId: String,
    val finalUrl: String,
    val redirects: List<RedirectStep>,
    val platform: String = "Douyin",
    /** In-memory page reference; may carry a public share access token. Never persist or print it. */
    val extractionUrl: String? = null,
)

data class RedirectStep(
    val url: String,
    val status: Int,
    val location: String?,
    val contentType: String?,
)
