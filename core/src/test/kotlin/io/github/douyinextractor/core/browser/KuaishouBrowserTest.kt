package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.model.ExtractError
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KuaishouBrowserTest {
    private val id = "3xie2x2ahb2weu9"
    private val url = KuaishouBrowserPolicy.videoPage(id)
    private val media = "https://v1.kwaicdn.com/a.mp4?sig=SECRET"
    private val photo = """{"id":"$id","photoUrl":"$media","width":720,"height":1280}"""
    private val state = """{"defaultClient":{"VisionVideoDetailPhoto:$id":$photo}}"""
    private fun snapshot(page: String = url, states: List<Pair<String,String>> = emptyList(), details: List<String> = emptyList(), components: List<String> = emptyList(), blocker: String? = null, html: String? = null) = buildJsonObject {
        put("version", "ks-1"); put("pageUrl", page); put("readyState", "complete")
        put("states", buildJsonArray { states.forEach { (name,json) -> add(buildJsonObject { put("name",name); put("json",json) }) } })
        put("details", buildJsonArray { details.forEach { add(it) } }); put("components", buildJsonArray { components.forEach { add(it) } }); blocker?.let { put("blocker",it) }; html?.let { put("html",it) }
    }.toString()
    private fun parse(text: String) = KuaishouBrowserParser().parseEvaluationResult(text, id)
    @Test fun `share entry preserves only official matching landing otherwise uses standard share page`() {
        val landing="https://kphm5nf3.m.chenzhongtech.com/fw/photo/$id"
        assertEquals(landing,KuaishouBrowserPolicy.sharePage(id,"$landing?userId=PRIVATE&utm_source=SECRET"))
        for(input in listOf(url,"https://www.kuaishou.com/short-video/3xotherphoto1234", "https://evil.test/fw/photo/$id", "https://user@kphm5nf3.m.chenzhongtech.com/fw/photo/$id"))
            assertEquals("https://v.m.chenzhongtech.com/fw/photo/$id",KuaishouBrowserPolicy.sharePage(id,input))
    }
    @Test fun `dynamic player props work after verification when original page state is empty`() {
        val result=parse(snapshot(components=listOf("""{"photo":$photo,"author":{"name":"作者"}}"""),blocker="verification"))
        assertEquals(id,result.media?.id);assertEquals(media,result.media?.formats?.single()?.url)
        assertTrue(result.attempts.any { it.parser=="KuaishouBrowserVideoInfo" && it.formatCount==1 })
        assertFalse(result.attempts.toString().contains("SECRET"))
        assertNull(parse(snapshot(components=listOf("""{"photo":${photo.replace(id,"3xotherphoto1234")}}"""))).media)
        assertNull(parse(snapshot(components=listOf("""{"photo":${photo.replace("v1.kwaicdn.com","evil.test")}}"""))).media)
    }
    @Test fun `public page and official share navigation stay bound to current photo`() {
        assertTrue(KuaishouBrowserPolicy.navigation(url,id))
        assertTrue(KuaishouBrowserPolicy.navigation("https://kphm5nf3.m.chenzhongtech.com/fw/photo/$id",id))
        for (unsafe in listOf("https://www.kuaishou.com/short-video/3xotherphoto1234", "https://www.kuaishou.com/profile/$id", "https://www.douyin.com/video/123",
            "https://user@www.kuaishou.com/short-video/$id", "http://www.kuaishou.com/short-video/$id", "https://www.kuaishou.com:444/short-video/$id"))
            assertFalse(unsafe,KuaishouBrowserPolicy.navigation(unsafe,id))
    }
    @Test fun `only reviewed resources load while account and telemetry requests stop`() {
        for (allowed in listOf(url,"https://www.kuaishou.com/graphql","https://p2-plat.wskwai.com/a.js","https://static.yximgs.com/a.js","https://captcha.zt.kuaishou.com/rest/zt/captcha/verify"))
            assertNull(allowed,KuaishouBrowserPolicy.resourceBlockReason(allowed))
        for (blocked in listOf("https://wlog.ksapisrv.com/rest/wd/log","https://www.kuaishou.com/login/", "https://www.kuaishou.com/%63ollect/events", "https://evil.test/a.js",
            "https://p2-plat.wskwai.com.evil.test/a.js","javascript:alert(1)","https://127.0.0.1/a.js","file:///etc/hosts","https://user@www.kuaishou.com/graphql"))
            assertNotNull(blocked,KuaishouBrowserPolicy.resourceBlockReason(blocked))
    }
    @Test fun `hydrated Apollo cache provides matching MP4 while diagnostics omit queries`() {
        val result = parse(snapshot(states=listOf("ApolloCache" to state)))
        assertEquals(id,result.media?.id); assertEquals("Kuaishou",result.media?.platform); assertEquals(media,result.media?.formats?.single()?.url)
        assertFalse(result.pageUrl!!.contains('?')); assertFalse(result.reason.contains("SECRET")); assertFalse(result.attempts.toString().contains("SECRET"))
    }
    @Test fun `observed detail response maps target photo and its author without native API request`() {
        val result = parse(snapshot(details=listOf("""{"photo":$photo,"author":{"name":"作者"}}""")))
        assertEquals(id,result.media?.id); assertEquals("作者",result.media?.author)
    }
    @Test fun `mobile state and page HTML share the existing target mapper`() {
        assertEquals(id,parse(snapshot(states=listOf("INIT_STATE" to """{"photo":$photo}"""))).media?.id)
        assertEquals(id,parse(snapshot(html="<script>window.__APOLLO_STATE__=$state;</script>")).media?.id)
    }
    @Test fun `wrong page wrong photo and foreign CDN never yield target media`() {
        assertNull(parse(snapshot(page="https://www.kuaishou.com/short-video/3xotherphoto1234",states=listOf("ApolloCache" to state))).media)
        assertNull(parse(snapshot(details=listOf("""{"photo":${photo.replace(id,"3xotherphoto1234")}}"""))).media)
        assertNull(parse(snapshot(states=listOf("ApolloCache" to state.replace("v1.kwaicdn.com","evil.test")))).media)
    }
    @Test fun `unbound visible video and recommendations cannot become downloads`() {
        val root=Json.parseToJsonElement(snapshot()) as JsonObject
        val unbound=JsonObject(root+mapOf("videos" to buildJsonArray { add(buildJsonObject { put("url",media);put("visible",true);put("width",720);put("height",1280) }) }))
        assertNull(parse(unbound.toString()).media)
        assertNull(parse(snapshot(states=listOf("ApolloCache" to state.replace(id,"3xotherphoto1234")))).media)
    }
    @Test fun `verification is manual and login stays unsupported`() {
        assertEquals(ExtractError.KuaishouVerificationRequired,parse(snapshot(blocker="verification")).error)
        assertEquals(ExtractError.LoginRequired,parse(snapshot(blocker="login")).error)
        assertNotNull(parse(snapshot(blocker="login",states=listOf("ApolloCache" to state))).media)
    }
    @Test fun `malformed flags oversized and deeply nested snapshots are bounded failures`() {
        for(text in listOf("{bad}","[]","x".repeat(8*1024*1024+1),"[".repeat(70)+"0"+"]".repeat(70)))assertNull(parse(text).media)
        val root=Json.parseToJsonElement(snapshot()) as JsonObject
        assertNull(parse(JsonObject(root+mapOf("captureFailed" to buildJsonObject { put("unexpected",true) })).toString()).media)
        assertNotNull(parse(snapshot(states=listOf("ApolloCache" to state), html="x".repeat(1024*1024+1))).media)
    }
    @Test fun `Android quoted evaluation output decodes exactly once`() {
        assertNotNull(parse(JsonPrimitive(snapshot(states=listOf("ApolloCache" to state))).toString()).media)
        assertNull(parse(JsonPrimitive(JsonPrimitive(snapshot()).toString()).toString()).media)
    }
    @Test fun `browser image photo is not returned as MP4`() {
        val data=state.replace("\"photoUrl\":", "\"photoType\":\"VERTICAL_ATLAS\",\"photoUrl\":")
        assertEquals(ExtractError.NotVideo,parse(snapshot(states=listOf("ApolloCache" to data))).error)
    }
}
