package io.github.douyinextractor.core.download

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import io.github.douyinextractor.core.extractor.douyin.mapping.MediaUrlPolicy
import io.github.douyinextractor.core.extractor.douyin.mapping.DouyinPlaybackSource
import io.github.douyinextractor.core.extractor.douyin.mapping.DouyinJsonMapper
import io.github.douyinextractor.core.model.MediaFormatSelector
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Explicit developer tool, NOT a unit test or app entry point. URL arrives via stdin, never printed. */
object DownloadLiveProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size in 1..2)
        val inputUrl = readlnOrNull() ?: error("No input")
        val target = args.getOrNull(1)?.takeIf { it.startsWith("target:") }?.removePrefix("target:")
        val mapped = target?.let { requireNotNull(DouyinJsonMapper.map(Json.parseToJsonElement(inputUrl), it, "LiveProbe")) }
        val selected = mapped?.let { requireNotNull(MediaFormatSelector.recommended(it.formats)) }
        val url = selected?.url ?: if (args.getOrNull(1) == "public-play")
            requireNotNull(DouyinPlaybackSource.alternative(inputUrl)) { "No public alternative" } else inputUrl
        val file = File(args[0])
        val client = OkHttpClient.Builder()
        val routes = mutableListOf<String>()
        (System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy"))?.let { raw ->
            val proxy = URI(raw)
            client.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, if (proxy.port > 0) proxy.port else 80)))
        }
        try {
            val size = VideoDownloader(client.build(), { address ->
                val accepted = MediaUrlPolicy.media(address) != null
                val parsed = runCatching { URI(address) }.getOrNull()
                val host = parsed?.host?.takeIf { it.matches(Regex("[a-zA-Z0-9.-]+")) }.orEmpty()
                val scheme = parsed?.scheme?.takeIf { it in setOf("http", "https") }.orEmpty()
                routes += "{\"host\":\"$host\",\"scheme\":\"$scheme\",\"accepted\":$accepted}"
                accepted
            }).download(url, file) {}
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            println("{\"success\":true,\"formatCount\":${mapped?.formats?.size ?: 0},\"publicCandidate\":${selected?.watermarkFreeCandidate ?: false},\"bytes\":$size,\"routes\":[${routes.joinToString(",")}],\"container\":\"MP4\",\"sha256\":\"${hash.digest().joinToString("") { "%02x".format(it) }}\"}")
        } catch (error: DownloadException) {
            println("{\"success\":false,\"problem\":\"${error.problem.name}\",\"routes\":[${routes.joinToString(",")}],\"http\":${error.httpCode ?: "null"}}")
        }
    }
}
