package io.github.douyinextractor.core.extractor.bilibili

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.download.VideoDownloader
import io.github.douyinextractor.core.model.MediaFormatSelector
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Manual opt-in network/file probe, never executed by unit tests or the app. No media URL output. */
object BilibiliLiveProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val url = System.`in`.bufferedReader(Charsets.UTF_8).readLine()
        val report = runBlocking {
            try {
                val extractor = BilibiliExtractor()
                val resolved = extractor.resolve(url, ResolutionDiagnostics()).getOrThrow()
                val media = extractor.extract(resolved.canonicalUrl).getOrThrow()
                val selected = requireNotNull(MediaFormatSelector.recommended(media.formats))
                val file = File(args[0])
                val size = VideoDownloader(media.platform).download(selected.url, file) {}
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                buildJsonObject {
                    put("success", true); put("id", media.id); put("page", media.selectedPage); put("pageCount", media.pages.size)
                    put("formats", media.formats.size); put("quality", selected.qualityLabel); put("durationMs", media.durationMs)
                    put("bytes", size); put("platform", media.platform)
                    put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    put("host", java.net.URI(selected.url).host)
                }
            } catch (e: Exception) { buildJsonObject { put("success", false); put("error", e.javaClass.simpleName) } }
        }
        println(report)
        kotlin.system.exitProcess(if (report["success"]?.jsonPrimitive?.booleanOrNull == true) 0 else 1)
    }
}
