package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.model.MediaFormatSelector
import io.github.douyinextractor.core.download.VideoDownloader
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Opt-in local snapshot validation. Never logs snapshot content or signed media URLs. */
object KuaishouBrowserLiveProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..4)
        val file=File(args[0]);require(file.length() <= 8 * 1024 * 1024)
        val result=KuaishouBrowserParser().parseEvaluationResult(file.readText(),args[1])
        val selectedIndex=args.getOrNull(3)?.toInt()
        val format=result.media?.let { if(selectedIndex==null) MediaFormatSelector.recommended(it.formats) else it.formats[selectedIndex] }
        var bytes:Long?=null;var sha256:String?=null
        if(args.size>=3 && format!=null){
            val output=File(args[2]);bytes=runBlocking{VideoDownloader("Kuaishou").download(format.url,output){}}
            val digest=MessageDigest.getInstance("SHA-256")
            output.inputStream().use{stream->val buffer=ByteArray(65536);while(true){val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
            sha256=digest.digest().joinToString(""){"%02x".format(it)}
        }
        println(buildJsonObject {
            put("targetMatched",result.media?.id==args[1]);put("formats",result.media?.formats?.size?:0)
            selectedIndex?.let { put("selectedIndex",it) }
            put("width",format?.width);put("height",format?.height);put("error",result.error?.javaClass?.simpleName)
            put("reason",result.reason);put("attempts",buildJsonArray {result.attempts.forEach {add(buildJsonObject {put("parser",it.parser);put("formats",it.formatCount)})}})
            put("bytes",bytes);put("sha256",sha256)
        })
    }
}
