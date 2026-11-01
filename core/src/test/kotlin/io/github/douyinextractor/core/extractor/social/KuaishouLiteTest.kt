package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.*
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.model.ExtractError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KuaishouLiteTest {
    private val id="3xucdsfddb9cz4m"
    private val short="https://v.kuaishou.com/nApsFvK2"
    private val mobile="https://kph8gvfz.m.chenzhongtech.com/fw/photo/$id"
    private val canonical="https://www.kuaishou.com/short-video/$id"
    @Test fun `full Lite share text and bare official mobile URL both resolve a supported reference`() {
        assertEquals(short,ShareTextParser().parse("$short 桃子”邪修怎么记？\"邪修英语 点击链接，打开【快手极速版】直接观看！").getOrThrow().url)
        assertEquals(mobile,ShareTextParser().parse(mobile.removePrefix("https://")).getOrThrow().url)
        assertEquals(id,SocialUrls.videoId(mobile))
    }
    @Test fun `dynamic official mobile alias resolves to canonical work without app routing secrets`() = runBlocking {
        var count=0;val trace=ResolutionDiagnostics()
        val extractor=SocialExtractor { url,platform ->
            assertEquals(short,url);assertEquals(SocialUrls.KUAISHOU,platform);count++
            SocialResponse(302,location="$mobile?photoId=$id&kpn=NEBULA&shareToken=SECRET&userId=PRIVATE")
        }
        val link=extractor.resolve(short,trace).getOrThrow()
        assertEquals(1,count);assertEquals(id,link.awemeId);assertEquals(SocialUrls.KUAISHOU,link.platform)
        assertEquals(canonical,link.canonicalUrl);assertEquals(canonical,link.extractionUrl)
        val report=DiagnosticReport.format(short,short,link,null,trace)
        assertTrue(report.contains("kph8gvfz.m.chenzhongtech.com/fw/photo/$id"))
        assertFalse(report.contains("SECRET"));assertFalse(report.contains("PRIVATE"));assertFalse(report.contains("shareToken"))
    }
    @Test fun `normal and Lite mobile entries share one canonical video and extraction route`() = runBlocking {
        val extractor=SocialExtractor { _,_->error("A direct ID must not make a resolution request") }
        val lite=extractor.resolve(mobile,ResolutionDiagnostics()).getOrThrow()
        val normal=extractor.resolve("https://v.m.chenzhongtech.com/fw/photo/$id",ResolutionDiagnostics()).getOrThrow()
        assertEquals(normal.platform,lite.platform);assertEquals(normal.awemeId,lite.awemeId)
        assertEquals(normal.canonicalUrl,lite.canonicalUrl);assertEquals(normal.extractionUrl,lite.extractionUrl)
    }
    @Test fun `new mobile suffix does not authorize lookalikes other routes ports or conflicting IDs`() {
        for(url in listOf("https://kph8gvfz.m.chenzhongtech.com.evil.test/fw/photo/$id","https://m.chenzhongtech.com/fw/photo/$id",
            "https://kph8gvfz.chenzhongtech.com/fw/photo/$id","https://other.a.m.chenzhongtech.com/fw/photo/$id",
            "https://user@kph8gvfz.m.chenzhongtech.com/fw/photo/$id","https://kph8gvfz.m.chenzhongtech.com:444/fw/photo/$id",
            "https://kph8gvfz.m.chenzhongtech.com/profile/$id","$mobile?photoId=3xanotherid1234"))assertNull(url,SocialUrls.normalize(url))
        assertFalse(SocialUrls.pageAllowed(mobile.replace("https://","http://"),SocialUrls.KUAISHOU))
        assertNull(SocialUrls.media("https://kph8gvfz.m.chenzhongtech.com/video.mp4",SocialUrls.KUAISHOU))
    }
    @Test fun `dynamic host cannot be extracted as a second URL embedded in an unrelated site`() {
        assertTrue(ShareTextParser().parse("https://evil.test/?link=$mobile").isFailure)
        assertTrue(ShareTextParser().parse("$short https://v.kuaishou.com/AnotherShare").isFailure)
    }
    @Test fun `unsupported dynamic redirect is rejected before a second network call`()=runBlocking {
        var requests=0
        val extractor=SocialExtractor { _,_->requests++;SocialResponse(302,location="https://kph8gvfz.m.chenzhongtech.com/profile/$id") }
        assertEquals(ExtractError.RedirectFailed,extractor.resolve(short,ResolutionDiagnostics()).exceptionOrNull())
        assertEquals(1,requests)
    }
    @Test fun `scheme-less dynamic-host diagnostic hides share parameters`() {
        val report=DiagnosticReport.redact("${mobile.removePrefix("https://")}?shareToken=SECRET&userId=PRIVATE")
        assertFalse(report.contains("SECRET"));assertFalse(report.contains("PRIVATE"))
    }
}
