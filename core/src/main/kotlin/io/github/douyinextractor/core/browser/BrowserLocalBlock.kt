package io.github.douyinextractor.core.browser

import java.util.UUID

/** Identifies this session's synthetic response, never inferring its origin from status alone. */
class BrowserLocalBlock {
    private val marker = UUID.randomUUID().toString()
    val statusCode = 403
    val reasonPhrase = "Blocked by Shiying $marker"
    val headers: Map<String, String> = mapOf(HEADER to marker, "Cache-Control" to "no-store")

    fun matches(status: Int, reason: String?, responseHeaders: Map<String, String>?): Boolean =
        status == statusCode && (reason == reasonPhrase ||
            responseHeaders?.any { it.key.equals(HEADER, ignoreCase = true) && it.value == marker } == true)

    private companion object { const val HEADER = "X-Shiying-Local-Block" }
}
