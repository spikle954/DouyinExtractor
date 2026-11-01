package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.extractor.PlatformVideoService
import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.download.VideoDownloader
import io.github.douyinextractor.core.model.MediaFormatSelector
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Explicit manual probe only. Does not run in tests or production; raw URL is stdin-only. */
object SocialLiveProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1)
        val input=System.`in`.bufferedReader().readLine()
        val report=runBlocking {
            val trace=ResolutionDiagnostics()
            try {
                val parsed=ShareTextParser().parse(input).getOrThrow()
                val service=PlatformVideoService()
                val link=service.resolve(parsed.url,trace).getOrThrow()
                val media=service.extract(link.extractionUrl?:link.canonicalUrl,trace).getOrThrow()
                val selected=requireNotNull(MediaFormatSelector.recommended(media.formats))
                val file=File(args[0]);val bytes=VideoDownloader(media.platform).download(selected.url,file){}
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use {stream->val buffer=ByteArray(65536);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
                buildJsonObject {put("success",true);put("id",media.id);put("platform",media.platform);put("title",media.title)
                    put("formats",media.formats.size);put("width",selected.width);put("height",selected.height);put("durationMs",media.durationMs)
                    put("bytes",bytes);put("sha256",digest.digest().joinToString(""){"%02x".format(it)})
                    put("host",java.net.URI(selected.url).host);put("trace",trace.notes().joinToString("\n"))}
            } catch(e:Exception) {buildJsonObject {put("success",false);put("error",e.javaClass.simpleName);put("trace",trace.notes().joinToString("\n"))}}
        }
        println(report)
        kotlin.system.exitProcess(if(report["success"]?.jsonPrimitive?.booleanOrNull==true)0 else 1)
    }
}
